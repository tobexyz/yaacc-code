/*
 *
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
package de.yaacc.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.multiroom.ui.SnapcastRendererGroupChoices;
import de.yaacc.multiroom.ui.SnapcastServerListModel;

/**
 * The chooser of the Snapcast group the YAACC renderer plays into (task 9.1), opened from the renderer settings. Two steps:
 * the server (the servers of the Snapcast server list: typed in, remembered, found on the network; nothing new is
 * discovered here), then the group (read from a status of that server: the server is connected for it and the list is
 * shown as soon as the status is there, after at most {@value #READ_LIMIT_MILLIS} ms). What is stored is the receiver id of
 * the chosen group ({@code snapcast:<server key>:<group id>}); the names on the screen are display text from the network
 * (cleaned by the choices) and are never stored. Thin Android glue; the lists are {@link SnapcastRendererGroupChoices} and
 * the id is {@link SnapcastGroupDevice#idOf}.
 *
 * <p>Nothing is left running when the window goes: the poll is a handler callback that is removed at every dismiss, and the
 * lifecycle of the activity ends it too; the connection to the server closes by itself after its grace period.
 */
final class SnapcastRendererGroupDialog {

    private static final long READ_LIMIT_MILLIS = 6_000L;
    private static final long POLL_MILLIS = 300L;

    private final Context context;
    private final SharedPreferences preferences;
    private final Runnable onChosen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "renderer-group-chooser");
        thread.setDaemon(true);
        return thread;
    });
    private AlertDialog current;
    private boolean ended;
    private Lifecycle observedLifecycle;
    private LifecycleEventObserver lifecycleObserver;

    private SnapcastRendererGroupDialog(Context context, Runnable onChosen) {
        this.context = context;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.onChosen = onChosen;
    }

    /**
     * Opens the chooser.
     *
     * @param onChosen called on the main thread after the chosen group was stored
     */
    static void show(Context context, Runnable onChosen) {
        SnapcastRendererGroupDialog dialog = new SnapcastRendererGroupDialog(context, onChosen);
        dialog.observeLifecycle();
        dialog.loadServers();
    }

    // ---- step 1: the server

    private void loadServers() {
        try {
            worker.execute(() -> {
                List<SnapcastServerListModel.Row> rows = SnapcastHub.getShared(context).newServerListModel().rows();
                final List<SnapcastRendererGroupChoices.Server> servers = SnapcastRendererGroupChoices.servers(rows);
                main.post(() -> showServers(servers));
            });
        } catch (RejectedExecutionException closed) {
            // ended meanwhile
        }
    }

    private void showServers(final List<SnapcastRendererGroupChoices.Server> servers) {
        if (!canShow()) {
            return;
        }
        if (servers.isEmpty()) {
            current = new AlertDialog.Builder(context)
                    .setTitle(R.string.renderer_group_server_title)
                    .setMessage(R.string.renderer_group_no_servers)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            watch(current, null);
            current.show();
            return;
        }
        String[] items = new String[servers.size()];
        for (int i = 0; i < items.length; i++) {
            SnapcastRendererGroupChoices.Server server = servers.get(i);
            // the label starts with the address; the name from the network is cleaned display text
            items[i] = server.label() + "\n" + sourceText(server.kind());
        }
        current = new AlertDialog.Builder(context)
                .setTitle(R.string.renderer_group_server_title)
                .setItems(items, (d, which) -> readGroups(servers.get(which).key()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        watch(current, null);
        current.show();
    }

    private String sourceText(SnapcastServerListModel.Kind kind) {
        switch (kind) {
            case MANUAL:
                return context.getString(R.string.snapcast_servers_kind_manual);
            case REMEMBERED:
                return context.getString(R.string.snapcast_servers_kind_remembered);
            case DISCOVERED:
            default:
                return context.getString(R.string.snapcast_servers_kind_found);
        }
    }

    // ---- step 2: the group, from a status of the server

    private void readGroups(final SnapcastServerKey key) {
        if (!canShow()) {
            return;
        }
        final SnapcastHub hub = SnapcastHub.getShared(context);
        hub.getReceivers().wakeServer(key); // connects; the link closes by itself after its grace period
        final AlertDialog waiting = new AlertDialog.Builder(context)
                .setTitle(R.string.renderer_group_group_title)
                .setMessage(R.string.renderer_group_reading)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        final long start = android.os.SystemClock.elapsedRealtime();
        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (ended || !waiting.isShowing()) {
                    return;
                }
                List<SnapcastGroupDevice> groups = hub.getReceivers().groupsOf(key);
                if (groups != null) {
                    waiting.dismiss();
                    showGroups(SnapcastRendererGroupChoices.groups(groups));
                } else if (android.os.SystemClock.elapsedRealtime() - start >= READ_LIMIT_MILLIS) {
                    waiting.dismiss();
                    message(R.string.renderer_group_unreachable);
                } else {
                    main.postDelayed(this, POLL_MILLIS);
                }
            }
        };
        current = waiting;
        watch(waiting, () -> main.removeCallbacks(poll));
        waiting.show();
        main.postDelayed(poll, POLL_MILLIS);
    }

    private void showGroups(final List<SnapcastRendererGroupChoices.Group> groups) {
        if (!canShow()) {
            return;
        }
        if (groups.isEmpty()) {
            message(R.string.renderer_group_none);
            return;
        }
        String[] items = new String[groups.size()];
        for (int i = 0; i < items.length; i++) {
            SnapcastRendererGroupChoices.Group group = groups.get(i);
            items[i] = group.name() + "\n" + context.getString(R.string.renderer_group_members, group.clients());
        }
        current = new AlertDialog.Builder(context)
                .setTitle(R.string.renderer_group_group_title)
                .setItems(items, (d, which) -> choose(groups.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        watch(current, null);
        current.show();
    }

    private void choose(SnapcastRendererGroupChoices.Group group) {
        // the id is stored, never the name; the hub remembers the server of it while the output is the group
        preferences.edit().putString(SnapcastRendererOutput.KEY_GROUP, group.receiverId()).apply();
        if (onChosen != null) {
            onChosen.run();
        }
        end();
    }

    private void message(int text) {
        if (!canShow()) {
            return;
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    // ---- lifetime

    private boolean canShow() {
        if (ended) {
            return false;
        }
        Context walk = context;
        while (walk instanceof ContextWrapper && !(walk instanceof Activity)) {
            walk = ((ContextWrapper) walk).getBaseContext();
        }
        if (walk instanceof Activity && (((Activity) walk).isFinishing() || ((Activity) walk).isDestroyed())) {
            end();
            return false;
        }
        return true;
    }

    /**
     * What happens when a window of the chooser goes: {@code onDismiss} first, and when it was the last one that was
     * current (cancel, back, or the end of a step that shows nothing after it) the chooser ends, once the next step had its
     * chance to show a window.
     */
    private void watch(AlertDialog dialog, Runnable onDismiss) {
        dialog.setOnDismissListener(d -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
            if (d == current) {
                main.post(this::endIfNothingShown);
            }
        });
    }

    private void endIfNothingShown() {
        if (current == null || !current.isShowing()) {
            end();
        }
    }

    private void end() {
        ended = true;
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        if (observedLifecycle != null && lifecycleObserver != null) {
            observedLifecycle.removeObserver(lifecycleObserver);
        }
        observedLifecycle = null;
        AlertDialog last = current;
        current = null;
        if (last != null && last.isShowing()) {
            last.dismiss();
        }
    }

    private void observeLifecycle() {
        Context walk = context;
        while (walk instanceof ContextWrapper && !(walk instanceof LifecycleOwner)) {
            walk = ((ContextWrapper) walk).getBaseContext();
        }
        if (!(walk instanceof LifecycleOwner)) {
            return;
        }
        observedLifecycle = ((LifecycleOwner) walk).getLifecycle();
        lifecycleObserver = (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) {
                end();
            }
        };
        observedLifecycle.addObserver(lifecycleObserver);
    }
}
