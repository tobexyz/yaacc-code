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

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.multiroom.feed.FeedOperationsSource;
import de.yaacc.multiroom.feed.FeedServer;
import de.yaacc.multiroom.feed.SnapcastFeedController;

/**
 * The server of one group as the feed controller sees it: the link that {@link SnapcastReceivers} holds open for it.
 * The link is looked up at every use (the receivers may replace it), so nothing here keeps a connection object alive
 * and nothing is cached: the peer address in particular is asked for at the moment it is used.
 *
 * <p>When the link goes offline the controller hears of it ({@link SnapcastFeedController#controlLost()}); the listener
 * is attached to the link the first time the controller waited for it to be up.
 */
final class SessionFeedServer implements FeedServer, FeedOperationsSource {

    private static final long POLL_MILLIS = 50L;
    /** The most time one volume gesture may take in all (up to 256 control calls). */
    static final long VOLUME_BUDGET_MILLIS = 5_000L;

    private final SnapcastReceivers receivers;
    private final SnapcastServerKey key;
    private final AtomicReference<SnapcastFeedController> controller = new AtomicReference<>();
    private final SnapcastServerLink.Listener linkListener = new SnapcastServerLink.Listener() {
        @Override
        public void onLinkChanged(SnapcastServerLink link) {
            if (!link.isOnline()) {
                SnapcastFeedController target = controller.get();
                if (target != null) {
                    target.controlLost();
                }
            }
        }
    };

    // guarded by this
    private SnapcastServerLink listening;

    SessionFeedServer(SnapcastReceivers receivers, SnapcastServerKey key) {
        this.receivers = receivers;
        this.key = key;
    }

    /** The controller to tell when the link goes offline; set once, right after it was made. */
    void attach(SnapcastFeedController feedController) {
        controller.set(feedController);
    }

    /** Detaches from the link; no more calls to the controller. */
    void detach() {
        controller.set(null);
        SnapcastServerLink link;
        synchronized (this) {
            link = listening;
            listening = null;
        }
        if (link != null) {
            link.removeListener(linkListener);
        }
    }

    private SnapcastServerLink link() {
        return receivers.getLink(key);
    }

    @Override
    public SnapcastOperations operations() {
        SnapcastServerLink link = link();
        return link == null ? null : link.getOperations();
    }

    @Override
    public boolean awaitControl(long timeoutMillis) {
        long end = System.nanoTime() + Math.max(0, timeoutMillis) * 1_000_000L;
        boolean retried = false;
        while (true) {
            SnapcastServerLink link = link();
            if (link != null) {
                if (link.isOnline()) {
                    listenTo(link);
                    return true;
                }
                if (!retried) {
                    retried = true;
                    link.retry(); // a link that waits for its next attempt or gave up connects now
                }
            }
            if (System.nanoTime() >= end) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void listenTo(SnapcastServerLink link) {
        synchronized (this) {
            if (listening == link) {
                return;
            }
            if (listening != null) {
                listening.removeListener(linkListener);
            }
            listening = link;
        }
        link.addListener(linkListener);
    }

    @Override
    public long connectionGeneration() {
        SnapcastServerLink link = link();
        return link == null ? -1 : link.getConnectionGeneration();
    }

    @Override
    public InetAddress peerAddress() {
        SnapcastServerLink link = link();
        return link == null || !link.isOnline() ? null : link.getPeerAddress();
    }

    @Override
    public SnapcastGroup findGroup(String groupId) {
        SnapcastServerLink link = link();
        if (link == null || link.getStatus() == null) {
            return null;
        }
        return link.getStatus().findGroup(groupId);
    }

    private SnapcastOperations requireOperations() throws SnapcastException {
        SnapcastOperations operations = operations();
        if (operations == null) {
            throw new SnapcastException(SnapcastException.Kind.CLOSED, 0, "no connection to the server", null, null);
        }
        return operations;
    }

    @Override
    public void setGroupVolume(SnapcastGroup current, Map<String, Integer> startLevels, int percent)
            throws SnapcastException {
        requireOperations().setGroupVolume(current, startLevels, percent, VOLUME_BUDGET_MILLIS);
    }

    @Override
    public void setGroupMuted(String groupId, boolean muted) throws SnapcastException {
        requireOperations().setGroupMute(groupId, muted);
    }
}
