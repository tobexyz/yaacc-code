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
package de.yaacc.upnp.server.renderingcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.yaacc.browser.SnapcastUi;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.control.GroupVolume;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.ui.SnapcastToastGate;
import de.yaacc.player.PlayerService;

/**
 * The volume of the YAACC renderer for the app (task 9.1): one {@link OutputRoutedVolume} for the process, shared by the
 * RenderingControl service and the OpenHome renderer, so that both follow the output setting the same way and the sets share
 * one sender thread. Thin Android glue; every decision is in {@link OutputRoutedVolume} and {@link SnapcastRendererOutput},
 * which are tested on the JVM.
 *
 * <p>The group side reads the model of the servers in memory ({@link SnapcastReceivers#stateOf}) and sets through the one
 * {@code SnapcastControls} of the app, so the coalescing, the clamping and the failure toast of the group slider are the same.
 * A problem that stops a set (the server is not listed, does not answer, the group is gone, no group is chosen) is shown as
 * the same toast a refused play shows, at most one in a few seconds.
 */
public final class RendererVolumes {

    private static final long TOAST_GAP_MILLIS = 3_000L;

    private static OutputRoutedVolume shared;

    private RendererVolumes() {
    }

    /** The volume of the renderer; the same instance for every caller. */
    public static synchronized RendererVolume forContext(final Context context) {
        if (shared == null) {
            final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
            final Handler main = new Handler(Looper.getMainLooper());
            final SnapcastToastGate gate = new SnapcastToastGate(MonotonicClock.SYSTEM, TOAST_GAP_MILLIS);
            final Executor sender = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "renderer-group-volume");
                thread.setDaemon(true);
                return thread;
            });
            shared = new OutputRoutedVolume(
                    () -> SnapcastRendererOutput.read(key -> preferences.getString(key, null)),
                    new SystemVolume(app), new GroupSide(app), sender, new SnapcastRendererOutput.Waiter() {
                @Override
                public long nowMillis() {
                    return MonotonicClock.SYSTEM.nowMillis();
                }

                @Override
                public void sleep(long millis) throws InterruptedException {
                    Thread.sleep(millis);
                }
            }, MonotonicClock.SYSTEM, problem -> {
                final int text = PlayerService.textFor(problem);
                if (text != 0 && gate.allow()) {
                    main.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
                }
            });
        }
        return shared;
    }

    /** The watcher of the RenderingControl service: the system broadcast, the groups and the setting. */
    static VolumeWatcher watcherFor(final Context context) {
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        return new RoutedVolumeWatcher(new SystemVolumeBroadcastWatcher(context), onChange -> {
            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
            final SnapcastHub hub = SnapcastHub.getShared(app);
            final SnapcastReceivers.ChangeListener groups = () -> {
                if (SnapcastRendererOutput.read(key -> preferences.getString(key, null)).mode() == SnapcastRendererOutput.Mode.GROUP) {
                    onChange.run();
                }
            };
            // the preferences hold their listeners weakly: the closure below keeps it alive until it is let go
            final SharedPreferences.OnSharedPreferenceChangeListener setting = (changed, key) -> {
                if (SnapcastRendererOutput.KEY_OUTPUT.equals(key) || SnapcastRendererOutput.KEY_GROUP.equals(key)) {
                    onChange.run();
                }
            };
            hub.addChangeListener(groups);
            preferences.registerOnSharedPreferenceChangeListener(setting);
            return () -> {
                hub.removeChangeListener(groups);
                preferences.unregisterOnSharedPreferenceChangeListener(setting);
            };
        });
    }

    /** The group's side: the model for reading, the controls for setting. */
    private static final class GroupSide implements OutputRoutedVolume.GroupSide {
        private final Context context;

        GroupSide(Context context) {
            this.context = context;
        }

        @Override
        public SnapcastRendererOutput.GroupFacts facts(String receiverId) {
            return SnapcastReceivers.factsOf(SnapcastHub.getShared(context).getReceivers().stateOf(receiverId));
        }

        @Override
        public void wake(String receiverId) {
            SnapcastHub.getShared(context).wake(Collections.singletonList(receiverId));
        }

        @Override
        public OutputRoutedVolume.Level level(String receiverId) {
            SnapcastReceivers.GroupState state = SnapcastHub.getShared(context).getReceivers().stateOf(receiverId);
            SnapcastGroup group = state == null ? null : state.getGroup();
            if (group == null) {
                return null;
            }
            return new OutputRoutedVolume.Level(Math.max(0, GroupVolume.average(GroupVolume.levels(group))), group.isMuted());
        }

        @Override
        public void setVolume(String receiverId, int percent) {
            SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(receiverId);
            if (id != null) {
                SnapcastUi.controls(context).setGroupVolume(id.getServerKey(), id.getGroupId(), percent);
            }
        }

        @Override
        public void setMuted(String receiverId, boolean muted) {
            SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(receiverId);
            if (id != null) {
                SnapcastUi.controls(context).setGroupMuted(id.getServerKey(), id.getGroupId(), muted);
            }
        }
    }
}
