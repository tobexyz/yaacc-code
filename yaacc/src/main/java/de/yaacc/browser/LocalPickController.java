/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package de.yaacc.browser;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.Player;
import de.yaacc.share.BoundedCall;
import de.yaacc.share.DisplayName;
import de.yaacc.share.FolderListing;
import de.yaacc.share.OwnProviders;
import de.yaacc.share.PickContent;
import de.yaacc.share.PickedAction;
import de.yaacc.share.PickedQueue;
import de.yaacc.share.ProviderCallGate;
import de.yaacc.share.ProviderKeys;
import de.yaacc.share.ProviderProbe;
import de.yaacc.share.SharedContentPolicy;
import de.yaacc.share.ShareLimits;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * "Play files / a folder from this device": the user picks files or one folder with the system
 * picker, the files are checked and sorted into up to three queues (audio, video, image), the
 * receiver picker "Play on..." opens, and a confirm starts one player per queue and receiver. No
 * content is browsed and nothing is served: each file is shared just in time while it plays (see
 * {@code QueueShareWindow}).
 * <p>
 * The pick results come from our own system picker call, not from another app, but the names, types
 * and sizes the provider answers are still untrusted text: they go through the same cleaners and
 * bounded provider calls as a shared file. Provider calls run on a worker thread, never on the main
 * thread. Notices are fixed resource texts with numbers only (no names, no uris).
 */
final class LocalPickController {

    private static final String TAG = LocalPickController.class.getName();

    /** The most uris looked at after a pick of single files (more cannot be played anyway). */
    private static final int MAX_PICKED_URIS = 1000;
    /** The time all provider calls of one pick may take together. */
    private static final long PROBE_BUDGET_MILLIS = 20_000L;
    private static final long LISTING_TIMEOUT_MILLIS = 15_000L;

    private static final BoundedCall PROVIDER_CALLS = new BoundedCall(ShareLimits.PROVIDER_POOL_THREADS,
            "pick-provider", ProviderCallGate.createDefault());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pick-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final Fragment fragment;
    private final Yaacc app;
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> filesLauncher;
    private final ActivityResultLauncher<Uri> folderLauncher;

    /** Call from {@code Fragment.onCreate}: the launchers must be registered before the fragment starts. */
    LocalPickController(Fragment fragment) {
        this.fragment = fragment;
        this.app = (Yaacc) fragment.requireContext().getApplicationContext();
        this.filesLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);
        this.folderLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);
    }

    void pickFiles() {
        try {
            filesLauncher.launch(new String[]{"audio/*", "video/*", "image/*"});
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "The file picker could not be opened (" + e.getClass().getSimpleName() + ")");
            toast(R.string.pick_picker_failed);
        }
    }

    void pickFolder() {
        try {
            folderLauncher.launch(null);
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "The folder picker could not be opened (" + e.getClass().getSimpleName() + ")");
            toast(R.string.pick_picker_failed);
        }
    }

    // ---- results (main thread) ----

    private void onFilesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        final List<Uri> picked = new ArrayList<>(uris.subList(0, Math.min(uris.size(), MAX_PICKED_URIS)));
        runOnWorker(() -> {
            List<PickedQueue.Entry> entries = probe(picked);
            boolean unread = entries.size() < picked.size();
            PickedQueue.Result queue = PickedQueue.build(entries, false);
            mainThread.post(() -> offer(queue, unread));
        });
    }

    private void onFolderPicked(Uri tree) {
        if (tree == null) {
            return;
        }
        runOnWorker(() -> {
            FolderListing.Result listing = list(tree);
            if (listing == null || listing.isFailed()) {
                toast(R.string.pick_folder_failed);
                return;
            }
            PickedQueue.Result queue = PickedQueue.build(listing.entries(), true);
            boolean unread = listing.isTruncated();
            mainThread.post(() -> offer(queue, unread));
        });
    }

    private void runOnWorker(Runnable work) {
        try {
            WORKER.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    YaaccLogger.w(TAG, "Checking the picked files failed (" + e.getClass().getSimpleName() + ")");
                    toast(R.string.pick_picker_failed);
                }
            });
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "The pick could not be scheduled (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---- provider calls (worker thread) ----

    /** Type and name of each picked file, one bounded call per file, within a total time budget. */
    private List<PickedQueue.Entry> probe(List<Uri> uris) {
        Context context = app.getApplicationContext();
        ContentResolver resolver = context.getContentResolver();
        String ownPackage = context.getPackageName();
        List<PickedQueue.Entry> entries = new ArrayList<>();
        long deadline = System.nanoTime() + PROBE_BUDGET_MILLIS * 1_000_000L;
        for (Uri uri : uris) {
            if (System.nanoTime() > deadline) {
                break;
            }
            String text = String.valueOf(uri);
            if (!isUsable(context, ownPackage, text)) {
                continue;
            }
            String key = ProviderKeys.of(context.getPackageManager(), SharedContentPolicy.authorityOf(text));
            ProviderProbe.Info info = PROVIDER_CALLS.call(key, () -> ProviderProbe.query(resolver, uri),
                    ShareLimits.PROVIDER_CALL_TIMEOUT_MILLIS, null);
            if (info == null) {
                continue;
            }
            entries.add(new PickedQueue.Entry(text, info.getDisplayName(), info.getProviderType(), 0L));
        }
        return entries;
    }

    private static boolean isUsable(Context context, String ownPackage, String uriText) {
        if (!SharedContentPolicy.isAcceptableUri(uriText, OwnProviders.fileProviderAuthority(ownPackage))) {
            return false;
        }
        return !OwnProviders.isOwnAuthority(context.getPackageManager(), ownPackage,
                SharedContentPolicy.authorityOf(uriText));
    }

    /** The files of the picked folder (not its sub folders), one bounded query. */
    private FolderListing.Result list(Uri tree) {
        Context context = app.getApplicationContext();
        String ownPackage = context.getPackageName();
        String treeText = String.valueOf(tree);
        if (!isUsable(context, ownPackage, treeText)) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        String key = ProviderKeys.of(context.getPackageManager(), SharedContentPolicy.authorityOf(treeText));
        FolderListing.RowSource rows = maxRows -> PROVIDER_CALLS.call(key,
                () -> readRows(resolver, tree, maxRows), LISTING_TIMEOUT_MILLIS, null);
        return FolderListing.list(rows, documentId -> {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
            return uri == null ? null : uri.toString();
        });
    }

    private static List<FolderListing.Row> readRows(ContentResolver resolver, Uri tree, int maxRows) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE};
        List<FolderListing.Row> rows = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (rows.size() < maxRows && cursor.moveToNext()) {
                rows.add(FolderListing.Row.capped(text(cursor, 0), text(cursor, 1), text(cursor, 2),
                        cursor.isNull(3) ? 0L : cursor.getLong(3)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return rows;
    }

    private static String text(Cursor cursor, int column) {
        try {
            return cursor.getType(column) == Cursor.FIELD_TYPE_STRING ? cursor.getString(column) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- hand-over to the receiver picker (main thread) ----

    /** @param unread some files were not read (a provider did not answer, the time ran out, a huge folder) */
    private void offer(PickedQueue.Result queue, boolean unread) {
        if (!fragment.isAdded()) {
            return;
        }
        if (queue.isEmpty()) {
            toast(R.string.pick_nothing_to_play);
            return;
        }
        BrowserHost host = fragment.getActivity() instanceof BrowserHost ? (BrowserHost) fragment.getActivity() : null;
        if (host == null) {
            return;
        }
        if (unread) {
            toast(R.string.pick_skipped_unread);
        }
        if (queue.skippedOverCap() > 0) {
            toast(fragment.getString(R.string.pick_skipped_over_cap, PickedQueue.MAX_FILES, queue.skippedOverCap()));
        } else if (queue.skippedNotMedia() > 0) {
            toast(fragment.getString(R.string.pick_skipped_not_media, queue.skippedNotMedia()));
        }
        boolean newLayout = host.providesReceiverBar();
        Collection<String> initial = newLayout ? null : new HashSet<>(app.getUpnpClient().getReceiverDeviceIds());
        String title = fragment.getString(R.string.pick_title_files, queue.queuedCount());
        boolean opened = host.pickReceivers(new PickedAction() {
            @Override
            public void onConfirmed(Set<String> receiverIds, Runnable done) {
                start(queue, receiverIds, newLayout, done);
            }

            @Override
            public void onCancelled() {
                // nothing was opened or shared
            }

            @Override
            public PickContent content() {
                return PickContent.of(queue);
            }
        }, DisplayName.clean(title), initial);
        if (!opened) {
            toast(R.string.receiver_pick_busy);
        }
    }

    /** The user confirmed: set the receivers, create the players on a worker, start them on the main thread. */
    private void start(PickedQueue.Result queue, Set<String> receiverIds, boolean newLayout, Runnable done) {
        // the selection to bring back is read now, before the chosen receivers replace it
        final UpnpClient upnpClient = app.getUpnpClient();
        final Runnable afterStart = ReceiverPickController.afterStartHook(upnpClient, newLayout);
        upnpClient.setReceiverDeviceIds(receiverIds);
        // what runs once the start was attempted, whatever became of it: the selection goes back, the flight ends
        final Runnable finish = () -> {
            try {
                afterStart.run();
            } finally {
                done.run();
            }
        };
        runOnWorkerThenMain(() -> upnpClient.initializeQueuePlayers(queue), players -> {
            try {
                if (players == null || players.isEmpty()) {
                    toast(R.string.pick_play_failed);
                    return;
                }
                for (Player player : players) {
                    try {
                        player.play();
                    } catch (RuntimeException e) {
                        YaaccLogger.w(TAG, "A player of the picked files did not start (" + e.getClass().getSimpleName() + ")");
                    }
                }
            } finally {
                finish.run();
            }
        }, finish);
    }

    private interface Result<T> {
        void accept(T value);
    }

    private <T> void runOnWorkerThenMain(java.util.concurrent.Callable<T> work, Result<T> then, Runnable onFailure) {
        try {
            WORKER.execute(() -> {
                T value = null;
                try {
                    value = work.call();
                } catch (Exception e) {
                    YaaccLogger.w(TAG, "Starting the picked files failed (" + e.getClass().getSimpleName() + ")");
                } finally {
                    // also after an Error: the flight and the receiver selection must be given back
                    final T result = value;
                    mainThread.post(() -> then.accept(result));
                }
            });
        } catch (RuntimeException e) {
            onFailure.run();
        }
    }

    private void toast(@StringRes int text) {
        mainThread.post(() -> toast(app.getString(text)));
    }

    private void toast(String text) {
        if (text == null) {
            return;
        }
        mainThread.post(() -> {
            try {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                YaaccLogger.w(TAG, "Could not show a message (" + e.getClass().getSimpleName() + ")");
            }
        });
    }
}
