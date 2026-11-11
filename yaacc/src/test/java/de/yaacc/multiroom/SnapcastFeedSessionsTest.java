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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerInfo;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.feed.FeedError;
import de.yaacc.multiroom.feed.FeedOutputFactory;
import de.yaacc.multiroom.feed.SnapcastFeedController;
import de.yaacc.multiroom.feed.SnapcastStreams;

/**
 * The wiring of a controller to the receivers' link: the link is retained while the controller lives, the feed target
 * is the link's peer address (asked for at the moment of use), a link that goes offline ends the feed, and nothing
 * touches a server that is not there. Plain JVM with fake links.
 */
public class SnapcastFeedSessionsTest {

    private static final long GRACE = 30_000L;
    private static final SnapcastGroupDevice.Labels LABELS = new SnapcastGroupDevice.Labels("Snapcast group", "offline");

    private static final class FakeLink implements SnapcastServerLink {
        final de.yaacc.multiroom.discovery.SnapcastServerKey key;
        SnapcastServerStatus status;
        volatile boolean online;
        volatile InetAddress peer;
        boolean started;
        boolean closed;
        final AtomicInteger retries = new AtomicInteger();
        final List<Listener> listeners = new ArrayList<>();

        FakeLink(de.yaacc.multiroom.discovery.SnapcastServerKey key) {
            this.key = key;
        }

        @Override
        public de.yaacc.multiroom.discovery.SnapcastServerKey getKey() {
            return key;
        }

        @Override
        public SnapcastServerStatus getStatus() {
            return status;
        }

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public InetAddress getPeerAddress() {
            return peer;
        }

        @Override
        public void addListener(Listener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void retry() {
            retries.incrementAndGet();
        }

        @Override
        public void close() {
            closed = true;
            online = false;
        }

        void update(boolean nowOnline) {
            online = nowOnline;
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.onLinkChanged(this);
            }
        }
    }

    private static final class Scheduler implements RetryScheduler {
        final List<Runnable> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(task);
        }
    }

    private static final class Events implements SnapcastFeedController.Listener {
        final AtomicReference<FeedError> lost = new AtomicReference<>();
        final CountDownLatch lostLatch = new CountDownLatch(1);

        @Override
        public void onFeedLost(FeedError error) {
            lost.set(error);
            lostLatch.countDown();
        }

        @Override
        public void onLog(String line) {
            // not needed
        }
    }

    private static final class Gate implements SnapcastFeedController.StartCallback {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean ready;
        volatile FeedError error;

        @Override
        public void onReady() {
            ready = true;
            done.countDown();
        }

        @Override
        public void onFailed(FeedError failure) {
            error = failure;
            done.countDown();
        }
    }

    private final AtomicLong now = new AtomicLong(1_000);
    private final MonotonicClock clock = now::get;
    private final Scheduler scheduler = new Scheduler();
    private final List<FakeLink> links = new ArrayList<>();
    private SnapcastDirectory directory;
    private SnapcastReceivers receivers;
    private SnapcastFeedSessions sessions;
    private final List<String> opened = new ArrayList<>();
    private final List<InetAddress> openedAddresses = new ArrayList<>();
    private SnapcastGroupDevice device;

    @Before
    public void setUp() throws Exception {
        directory = new SnapcastDirectory(clock, GRACE);
        receivers = new SnapcastReceivers(directory, endpoint -> {
            FakeLink link = new FakeLink(endpoint.toKey());
            links.add(link);
            return link;
        }, scheduler, () -> LABELS, GRACE);
        FeedOutputFactory outputs = (address, port, onFailed) -> {
            opened.add(port + "");
            openedAddresses.add(address);
            throw new IOException("feed connection failed");
        };
        SnapcastStreams.MarkerStore memory = new SnapcastStreams.MarkerStore() {
            String value;

            @Override
            public String load() {
                return value;
            }

            @Override
            public void save(String marker) {
                value = marker;
            }
        };
        sessions = new SnapcastFeedSessions(receivers, memory, scheduler, outputs);
        directory.serviceFound("S2", InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 1705);
        SnapcastClient member = new SnapcastClient("m", "member", "", true, new SnapcastVolume(40, false), 0);
        SnapcastGroup group = new SnapcastGroup("g1", "Room", false, "Default", Collections.singletonList(member));
        device = SnapcastGroupDevice.of(SnapcastEndpoint.parse("10.0.0.2").toKey(), group, false, LABELS);
    }

    private SnapcastFeedController controller(Events events) {
        return sessions.newController(device, "Phone", events,
                new SnapcastFeedController.Config().controlWaitMillis(300).slotWaitMillis(100));
    }

    private FakeLink link() {
        assertEquals(1, links.size());
        return links.get(0);
    }

    @Test
    public void theLinkIsOpenedAndHeldForAsLongAsTheControllerLivesWithoutAPage() {
        SnapcastFeedController controller = controller(new Events());

        assertEquals("a link was opened for the group's server", 1, links.size());
        assertTrue(link().started);
        assertSame(link(), receivers.getLink(device.getServerKey()));

        controller.release();
        awaitReleased();
        for (Runnable task : new ArrayList<>(scheduler.tasks)) {
            task.run(); // the close after the grace
        }
        assertTrue("the link is let go after the release", link().closed);
    }

    /** The release runs on the controller's worker and ends by asking the receivers for a close after the grace. */
    private void awaitReleased() {
        long end = System.nanoTime() + 5_000_000_000L;
        while (scheduler.tasks.isEmpty() && System.nanoTime() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertFalse("the receivers were asked to close the link after the grace", scheduler.tasks.isEmpty());
    }

    @Test
    public void aStartWithAnOfflineLinkAsksItToRetryAndFailsWithNoControlAfterTheWait() throws Exception {
        SnapcastFeedController controller = controller(new Events());
        Gate gate = new Gate();

        controller.start(gate);

        assertTrue(gate.done.await(5, TimeUnit.SECONDS));
        assertEquals(FeedError.Kind.NO_CONTROL, gate.error.getKind());
        assertTrue("the link was asked to connect now", link().retries.get() >= 1);
        assertTrue("nothing connected", opened.isEmpty());
        controller.release();
    }

    @Test
    public void aLinkWithoutOperationsFailsTheStartWithATypedErrorAndNeverConnects() throws Exception {
        SnapcastFeedController controller = controller(new Events());
        link().peer = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        link().online = true; // up, but a fake: no control calls behind it
        Gate gate = new Gate();

        controller.start(gate);

        assertTrue(gate.done.await(5, TimeUnit.SECONDS));
        assertFalse(gate.ready);
        assertEquals(FeedError.Kind.SERVER_FAILED, gate.error.getKind());
        assertTrue(opened.isEmpty());
        controller.release();
    }

    @Test
    public void whenTheLinkGoesOfflineTheControllerIsTold() throws Exception {
        Events events = new Events();
        SnapcastFeedController controller = controller(events);
        link().peer = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        link().online = true;
        Gate gate = new Gate();
        int before = link().listeners.size();
        controller.start(gate); // fails (no operations) but has attached its listener on the way
        assertTrue(gate.done.await(5, TimeUnit.SECONDS));
        assertEquals("the controller listens to the link", before + 1, link().listeners.size());

        link().update(false);

        // no feed was open, so no news; the listener is the point of this test
        assertNull(events.lost.get());
        controller.release();
        long end = System.nanoTime() + 5_000_000_000L;
        while (link().listeners.size() != before && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals("released: the controller no longer listens", before, link().listeners.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingGroupIsRefused() {
        sessions.newController(null, "Phone", new Events());
    }
}
