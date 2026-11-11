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

import android.os.Handler;

import java.util.function.Consumer;

import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;

/**
 * The live subscriptions of a list of receiver rows, shared by the device sheet and the receivers on the top-level page: the shared
 * {@link RendererStatusMonitor} (state and track of the receivers), the group state of receivers with group support
 * ({@link YxcRowFeed}), the Snapcast groups ({@link SnapcastLiveSource}), player add/remove events, a 2 s poll (local status, session titles, volume and mute) and the volume updater.
 * <p>
 * {@link #start} subscribes everything once (a second call does nothing), {@link #stop} drops everything; a callback that still arrives
 * after {@link #stop} is ignored, and a poll that was already queued does not schedule itself again. Main thread only.
 * The world it talks to is behind {@link Sources}, so the logic is tested on the JVM.
 */
final class ReceiverLiveUpdates {
    static final long POLL_INTERVAL_MS = 2000;
    /**
     * The most receivers that are listed, polled and watched: a host of the network can announce any number of devices, and each
     * receiver costs a poll every 10 s. The same order of magnitude as the limit of the group feed.
     */
    static final int MAX_RECEIVERS = 64;

    /** The receivers that are listed and watched: the first {@link #MAX_RECEIVERS} (this phone first, then by name). */
    static <T> java.util.List<T> watched(java.util.Collection<T> receivers) {
        java.util.List<T> result = new java.util.ArrayList<>(Math.min(receivers.size(), MAX_RECEIVERS));
        for (T receiver : receivers) {
            if (result.size() >= MAX_RECEIVERS) {
                break;
            }
            result.add(receiver);
        }
        return result;
    }

    /** What the receiver rows need to be told; implemented by {@link DeviceSheetAdapter}. */
    interface Target {
        /** Rebuilds the rows from the current receivers and players. */
        void refresh();

        void onStatusChanged(RendererStatus status);

        void onGroupStateChanged(String udn);

        /** One step of the poll. */
        void refreshLiveState();

        void startLiveVolume();

        void stopLiveVolume();
    }

    /** The subscriptions and the clock; {@link #forApp} is the real one. */
    interface Sources {
        /** Subscribes with the receivers known now; the same listener again adds the receivers that came meanwhile. */
        void subscribeStatus(RendererStatusMonitor.StatusListener listener);

        void unsubscribeStatus(RendererStatusMonitor.StatusListener listener);

        /** Starts the group feed with the receivers known now; when it runs already, gives it the current receivers. */
        void startGroupFeed(Consumer<String> onChanged);

        void stopGroupFeed();

        void addPlayerListener(PlayerListChangeNotifier.Listener listener);

        void removePlayerListener(PlayerListChangeNotifier.Listener listener);

        void postDelayed(Runnable runnable, long delayMillis);

        void removeCallbacks(Runnable runnable);

        /**
         * Starts following the Snapcast groups (their servers' control connections are opened for as long as it runs);
         * {@code onChanged} is told, on the main thread and coalesced, when the groups changed. Nothing by default.
         */
        default void startSnapcast(Runnable onChanged) {
        }

        /** Stops following the Snapcast groups. */
        default void stopSnapcast() {
        }
    }

    private final Sources sources;
    private final Target target;
    private boolean running;

    private final RendererStatusMonitor.StatusListener statusListener = new RendererStatusMonitor.StatusListener() {
        @Override
        public void onStatusChanged(RendererStatus status) {
            if (running) {
                target.onStatusChanged(status);
            }
        }
    };

    private final PlayerListChangeNotifier.Listener playerListener = new PlayerListChangeNotifier.Listener() {
        @Override
        public void onPlayerAdded(Player player) {
            if (running) {
                target.refresh();
            }
        }

        @Override
        public void onPlayerRemoved(Player player) {
            if (running) {
                target.refresh();
            }
        }
    };

    private final Consumer<String> groupCallback = new Consumer<String>() {
        @Override
        public void accept(String udn) {
            if (running) {
                target.onGroupStateChanged(udn);
            }
        }
    };

    private final Runnable snapcastCallback = new Runnable() {
        @Override
        public void run() {
            if (running) {
                target.refresh();
            }
        }
    };

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            target.refreshLiveState();
            sources.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    ReceiverLiveUpdates(Sources sources, Target target) {
        this.sources = sources;
        this.target = target;
    }

    boolean isRunning() {
        return running;
    }

    /**
     * Subscribes everything. Does nothing when already running.
     *
     * @param refreshFirst true if the rows may be stale (the list was not just built): they are rebuilt once before the
     *                     subscriptions start, since receivers and players may have come or gone while stopped
     */
    void start(boolean refreshFirst) {
        if (running) {
            return;
        }
        running = true;
        if (refreshFirst) {
            target.refresh();
        }
        sources.subscribeStatus(statusListener);
        sources.startGroupFeed(groupCallback);
        sources.addPlayerListener(playerListener);
        sources.startSnapcast(snapcastCallback);
        target.startLiveVolume();
        sources.postDelayed(poller, POLL_INTERVAL_MS);
    }

    /**
     * The receivers changed (one came or went): the status monitor and the group feed took the receivers of {@link #start} as a
     * snapshot, so a receiver found later would show no state. Does nothing while stopped.
     */
    void devicesChanged() {
        if (!running) {
            return;
        }
        sources.subscribeStatus(statusListener);
        sources.startGroupFeed(groupCallback);
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        sources.removeCallbacks(poller);
        target.stopLiveVolume();
        sources.unsubscribeStatus(statusListener);
        sources.stopGroupFeed();
        sources.removePlayerListener(playerListener);
        sources.stopSnapcast();
    }

    /** The real world: the shared monitors, the client's player list and the main thread's handler. */
    static Sources forApp(UpnpClient upnpClient, Handler handler) {
        return new Sources() {
            private YxcRowFeed groupFeed;
            private SnapcastLiveSource snapcast;

            @Override
            public void subscribeStatus(RendererStatusMonitor.StatusListener listener) {
                RendererStatusMonitor.getShared().subscribe(listener, watched(upnpClient.getDevicesProvidingAvTransportService()));
            }

            @Override
            public void unsubscribeStatus(RendererStatusMonitor.StatusListener listener) {
                RendererStatusMonitor.getShared().unsubscribe(listener);
            }

            @Override
            public void startGroupFeed(Consumer<String> onChanged) {
                if (groupFeed == null) {
                    groupFeed = new YxcRowFeed(onChanged::accept);
                }
                // again with the current receivers: the feed adds the ones that came meanwhile
                groupFeed.start(watched(upnpClient.getDevicesProvidingAvTransportService()));
            }

            @Override
            public void stopGroupFeed() {
                if (groupFeed != null) {
                    groupFeed.stop();
                    groupFeed = null;
                }
            }

            @Override
            public void addPlayerListener(PlayerListChangeNotifier.Listener listener) {
                upnpClient.addPlayerListChangeListener(listener);
            }

            @Override
            public void removePlayerListener(PlayerListChangeNotifier.Listener listener) {
                upnpClient.removePlayerListChangeListener(listener);
            }

            @Override
            public void startSnapcast(Runnable onChanged) {
                if (snapcast == null) {
                    snapcast = SnapcastLiveSource.forApp(upnpClient.getContext(), handler, onChanged);
                }
                snapcast.start();
            }

            @Override
            public void stopSnapcast() {
                if (snapcast != null) {
                    snapcast.stop();
                    snapcast = null;
                }
            }

            @Override
            public void postDelayed(Runnable runnable, long delayMillis) {
                handler.postDelayed(runnable, delayMillis);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                handler.removeCallbacks(runnable);
            }
        };
    }
}
