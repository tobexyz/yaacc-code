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
package de.yaacc.multiroom;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.multiroom.feed.FeedClock;
import de.yaacc.multiroom.feed.FeedOutputFactory;
import de.yaacc.multiroom.feed.FeedSlots;
import de.yaacc.multiroom.feed.LinkBoundStreams;
import de.yaacc.multiroom.feed.SnapcastFeedController;
import de.yaacc.multiroom.feed.SnapcastStreams;

/**
 * Makes the {@link SnapcastFeedController} of a player: wires one controller to the link that {@link SnapcastReceivers}
 * holds for the group's server. The link is <b>retained</b> for the life of the controller (so the control connection
 * stays up while the player exists, also without a visible page) and let go when the controller is released. All
 * controllers of an app share one {@link FeedSlots}, so a second group of the same server is refused ({@code BUSY})
 * while the first is fed, and one marker store, so every stream of this phone carries the same marker.
 *
 * <p>Pure Java; the Android parts (the marker's preferences, the phone name) come in from the hub.
 */
public final class SnapcastFeedSessions {

    private final SnapcastReceivers receivers;
    private final SnapcastStreams.MarkerStore markerStore;
    private final RetryScheduler scheduler;
    private final FeedOutputFactory outputs;
    private final FeedSlots slots = new FeedSlots();

    public SnapcastFeedSessions(SnapcastReceivers receivers, SnapcastStreams.MarkerStore markerStore,
                                RetryScheduler scheduler, FeedOutputFactory outputs) {
        if (receivers == null || markerStore == null || scheduler == null || outputs == null) {
            throw new IllegalArgumentException("missing argument");
        }
        this.receivers = receivers;
        this.markerStore = markerStore;
        this.scheduler = scheduler;
        this.outputs = outputs;
    }

    /**
     * A controller for playing into one group. The caller must call {@link SnapcastFeedController#release()} when the
     * player ends: it gives the link back and ends the controller's worker.
     *
     * @param phoneName the name of this phone, from the same source as the UPnP server name; it is cut down for the
     *                  stream name by {@link SnapcastStreams}
     */
    public SnapcastFeedController newController(SnapcastGroupDevice group, String phoneName,
                                                SnapcastFeedController.Listener listener) {
        return newController(group, phoneName, listener, new SnapcastFeedController.Config());
    }

    SnapcastFeedController newController(SnapcastGroupDevice group, String phoneName,
                                         SnapcastFeedController.Listener listener, SnapcastFeedController.Config config) {
        if (group == null || listener == null) {
            throw new IllegalArgumentException("missing argument");
        }
        final SnapcastServerKey key = group.getServerKey();
        final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snapcast-feed-controller");
            thread.setDaemon(true);
            return thread;
        });
        final SessionFeedServer server = new SessionFeedServer(receivers, key);
        SnapcastFeedController controller = new SnapcastFeedController(
                new LinkBoundStreams(server, phoneName, markerStore), server, outputs,
                slots.slotFor(key.asString()), group.getGroupId(), FeedClock.SYSTEM, scheduler, worker, listener, config,
                () -> {
                    server.detach();
                    receivers.release(key);
                    worker.shutdown();
                });
        server.attach(controller);
        receivers.retain(key);
        return controller;
    }
}
