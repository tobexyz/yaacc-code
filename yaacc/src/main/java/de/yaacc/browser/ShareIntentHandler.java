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

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Plays a link another app shared to yaacc ({@code ACTION_SEND}, {@code text/plain}).
 * <p>
 * The shared text is untrusted. It is only checked by {@link ShareUriPolicy} (length cap, http or
 * https only), parsed with {@link Uri#parse(String)} and handed to
 * {@link UpnpClient#createPlayableItem(Uri)}. It is never used to build an intent, a
 * PendingIntent or a file path, and it is never logged.
 * <p>
 * {@link #handle(Intent, Runnable)} and {@link #shutdown()} are for the main thread. Only the
 * application context is kept.
 */
public final class ShareIntentHandler {

    private static final String TAG = ShareIntentHandler.class.getName();
    private static final long WAIT_FOR_RECEIVERS_MS = 3000L;
    private static final long WAIT_FOR_PLAYER_SERVICE_MS = 3000L;

    private final Context appContext;
    private final UpnpClient upnpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private boolean shutDown = false;

    /**
     * @param appContext any context; only the application context is kept
     * @param upnpClient the client used to find receivers and create the playable item
     */
    public ShareIntentHandler(Context appContext, UpnpClient upnpClient) {
        this.appContext = appContext.getApplicationContext();
        this.upnpClient = upnpClient;
    }

    /**
     * Reads a shared link from the intent and plays it. Does nothing for other actions. The text
     * is removed from the intent so that a recreated activity does not play it twice.
     *
     * @param intent           the intent received by the activity
     * @param onPlayersStarted runs on the main thread after the players have been started, may be
     *                         null
     */
    public void handle(Intent intent, Runnable onPlayersStarted) {
        if (shutDown || intent == null) {
            return;
        }
        String text = readAndClearSharedText(intent);
        if (text == null) {
            return;
        }
        if (!ShareUriPolicy.isAllowed(text)) {
            YaaccLogger.w(TAG, "Ignoring shared text: not an http(s) link or longer than "
                    + ShareUriPolicy.MAX_TEXT_LENGTH + " characters");
            return;
        }
        final Uri uri = Uri.parse(text);

        long delayedExecution = 0;
        if (upnpClient.getReceiverDevicesReadyCount() == 0) {
            //schedule execution to be sure the receiver devices come up
            delayedExecution = WAIT_FOR_RECEIVERS_MS;
        }
        if (!upnpClient.isPlayerServiceInitialized()) {
            //schedule execution to be sure the player devices come up
            upnpClient.startService();
            delayedExecution += WAIT_FOR_PLAYER_SERVICE_MS;
        }

        Runnable execution = () -> prepareAndPlay(uri, onPlayersStarted);
        try {
            if (delayedExecution > 0) {
                executor.schedule(execution, delayedExecution, TimeUnit.MILLISECONDS);
                Toast.makeText(appContext, String.format("start in %s seconds", delayedExecution / 1000), Toast.LENGTH_LONG).show();
            } else {
                executor.execute(execution);
            }
        } catch (RejectedExecutionException e) {
            YaaccLogger.w(TAG, "Share handler already shut down, ignoring shared link");
        }
    }

    /**
     * Releases the worker thread. Call from {@code onDestroy}. A share that is already
     * waiting for receivers still plays (a rotation must not drop it): a graceful
     * {@code shutdown()} lets scheduled tasks run and ends the thread afterwards. The handler
     * ignores later calls to {@link #handle(Intent, Runnable)}.
     */
    public void shutdown() {
        shutDown = true;
        executor.shutdown();
    }

    /** Worker thread. Nothing here may throw or log the shared link. */
    private void prepareAndPlay(Uri uri, Runnable onPlayersStarted) {
        try {
            if (upnpClient.getReceiverDevicesReadyCount() == 0) {
                mainHandler.post(() -> Toast.makeText(appContext, "no receiver found using local device", Toast.LENGTH_LONG).show());
                upnpClient.setReceiverDeviceIds(Set.of(UpnpClient.LOCAL_UID));
            }
            PlayableItem item = upnpClient.createPlayableItem(uri);
            mainHandler.post(() -> play(item, onPlayersStarted));
        } catch (Exception e) {
            // not e.getMessage() or the exception itself: they may contain the shared link
            YaaccLogger.w(TAG, "Could not prepare shared content (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Main thread. */
    private void play(PlayableItem item, Runnable onPlayersStarted) {
        try {
            List<PlayableItem> items = new ArrayList<>();
            items.add(item);
            List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
            for (Player player : players) {
                player.play();
            }
            if (onPlayersStarted != null) {
                onPlayersStarted.run();
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not start shared content (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Returns the shared text and removes it from the intent, or null. The text comes from
     * {@code EXTRA_TEXT}, else from the text of the last {@code ClipData} item that has one. A
     * clip item's URI is never used.
     */
    private static String readAndClearSharedText(Intent intent) {
        try {
            if (!Intent.ACTION_SEND.equals(intent.getAction())) {
                return null;
            }
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            ClipData clipData = intent.getClipData();
            if (text == null && clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    ClipData.Item item = clipData.getItemAt(i);
                    CharSequence itemText = item == null ? null : item.getText();
                    // check the length first so that a huge text is not copied into a String
                    if (itemText != null && itemText.length() <= ShareUriPolicy.MAX_TEXT_LENGTH) {
                        text = itemText.toString();
                    }
                }
            }
            //clear intent
            intent.removeExtra(Intent.EXTRA_TEXT);
            if (clipData != null) {
                intent.setClipData(null);
            }
            return text;
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not read the shared intent (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }
}
