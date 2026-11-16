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
package de.yaacc.upnp.server.openhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererSources;
import de.yaacc.multiroom.ui.SnapcastRendererGroupChoices;

/**
 * The Android side of the source list of the OpenHome renderer (task 12.5): the preferences of the app, the groups the Snapcast hub
 * knows, the message to the user, and the two things that make the controller's list follow the world: a change of the output setting
 * (the settings screen, another controller) and a change of the groups. Both only ask the core to read its list again, after a
 * short pause so that a burst of changes is one read, on a thread of its own.
 *
 * <p>Nothing here makes the hub or opens a connection: the groups are the ones the hub knows (a server that answered since the app
 * started; the hub is made by the Receiver tab, a play into a group or the renderer's group output). When there is none, only this
 * device is listed. The listeners are removed in {@link #close}; the preference listener is kept in a field because the
 * preferences hold their listeners weakly.
 */
public final class AndroidOutputSources {

    private static final long COALESCE_MILLIS = 250L;
    private static final long TOAST_GAP_MILLIS = 5_000L;

    private final Context context;
    private final OhRendererCore core;
    private final RendererOutputSources provider;
    private final SharedPreferences preferences;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhome-sources");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile long lastToastAt = Long.MIN_VALUE;
    private volatile SnapcastHub hub;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (changed, key) -> {
        if (SnapcastRendererOutput.KEY_OUTPUT.equals(key) || SnapcastRendererOutput.KEY_GROUP.equals(key)) {
            scheduleRefresh();
        }
    };
    private final SnapcastReceivers.ChangeListener groupsListener = this::scheduleRefresh;
    private final Consumer<SnapcastHub> hubCreated = this::attach;

    /** Makes the provider of the core and starts following; {@link #close} undoes it. */
    public static AndroidOutputSources start(Context context, OhRendererCore core) {
        AndroidOutputSources sources = new AndroidOutputSources(context.getApplicationContext(), core);
        sources.begin();
        return sources;
    }

    private AndroidOutputSources(Context context, OhRendererCore core) {
        this.context = context;
        this.core = core;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.provider = new RendererOutputSources(
                key -> preferences.getString(key, null),
                values -> {
                    SharedPreferences.Editor editor = preferences.edit();
                    for (java.util.Map.Entry<String, String> value : values.entrySet()) {
                        editor.putString(value.getKey(), value.getValue());
                    }
                    editor.apply(); // one editor: the listeners read the two values together
                },
                this::knownGroups,
                new SnapcastRendererSources.Labels(context.getString(R.string.snapcast_group),
                        context.getString(R.string.renderer_source_unavailable)),
                this::tellTheUser);
    }

    private void begin() {
        core.setSourceProvider(provider);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        SnapcastHub.whenCreated(hubCreated);
    }

    /** Stops following. The core keeps the provider (it is only the preferences and the hub that are read). */
    public void close() {
        closed = true;
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        SnapcastHub.forgetWhenCreated(hubCreated);
        SnapcastHub attached = hub;
        if (attached != null) {
            attached.removeChangeListener(groupsListener);
            hub = null;
        }
        worker.shutdownNow();
    }

    private void attach(SnapcastHub created) {
        if (closed || hub != null) {
            return;
        }
        hub = created;
        created.addChangeListener(groupsListener);
        scheduleRefresh();
    }

    private List<SnapcastRendererSources.GroupRef> knownGroups() {
        SnapcastHub known = hub;
        List<SnapcastRendererSources.GroupRef> result = new ArrayList<>();
        if (known == null) {
            return result;
        }
        List<SnapcastGroupDevice> devices = known.groups();
        for (SnapcastRendererGroupChoices.Group group : SnapcastRendererGroupChoices.groups(devices)) {
            result.add(new SnapcastRendererSources.GroupRef(group.receiverId(), group.name()));
        }
        return result;
    }

    private void scheduleRefresh() {
        if (closed || !refreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.schedule(() -> {
                refreshQueued.set(false);
                if (!closed) {
                    core.refreshSources();
                }
            }, COALESCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutDown) {
            refreshQueued.set(false);
        }
    }

    /** A controller's choice was refused: one short message, not more often than every few seconds. */
    private void tellTheUser(RendererOutputSources.Refusal refusal) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastToastAt != Long.MIN_VALUE && now - lastToastAt < TOAST_GAP_MILLIS) {
            return;
        }
        lastToastAt = now;
        final int text = refusal == RendererOutputSources.Refusal.NOT_SAVED ? R.string.renderer_source_error_not_saved
                : R.string.renderer_source_error_group_gone;
        main.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }
}
