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
package de.yaacc.multiroom.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.multiroom.SnapcastFeedSessions;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.SnapcastSession;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.control.SocketTransport;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * Removing a server (or switching discovery off for a discovered one) must stop a feed that runs to it (security review
 * W1): the receivers close the link, a closed link tells its listeners that it is offline, the controller closes the feed
 * and reports CONTROL_LOST, and the player stops. The whole chain with the real parts: a {@link SnapcastSession} over a
 * loopback socket to a simulated snapserver, the receivers and the directory, {@link SnapcastFeedSessions}, the real
 * controller and a real {@link PcmFeeder} that connects to the stream port.
 */
public class SnapcastFeedLinkRemovalTest {

    private static final long WAIT_MILLIS = 8_000;
    private static final SnapcastGroupDevice.Labels LABELS = new SnapcastGroupDevice.Labels("Snapcast group", "offline");

    private final SimulatedSnapcastServer simulation = new SimulatedSnapcastServer();
    private final List<Runnable> timers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<SnapcastSession> sessions = new ArrayList<>();
    private LoopbackControlServer control;
    private SnapcastDirectory directory;
    private SnapcastReceivers receivers;
    private SnapcastFeedSessions feedSessions;
    private SnapcastEndpoint endpoint;
    private SnapcastGroupDevice device;

    private static final class Events implements SnapcastFeedController.Listener {
        final AtomicReference<FeedError> lost = new AtomicReference<>();
        final CountDownLatch lostLatch = new CountDownLatch(1);
        volatile int lostCount;

        @Override
        public void onFeedLost(FeedError error) {
            lost.set(error);
            lostCount++;
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

    @Before
    public void setUp() throws Exception {
        simulation.bindRealListeners();
        simulation.addPipeStream("Default");
        simulation.addGroup("g1", "Default", "c1");
        control = new LoopbackControlServer(simulation);
        endpoint = SnapcastEndpoint.parse("127.0.0.1:" + control.port());
        directory = new SnapcastDirectory(MonotonicClock.SYSTEM, 30_000L);
        receivers = new SnapcastReceivers(directory, ep -> {
            SnapcastSession session = new SnapcastSession(ep.toKey(), new SocketTransport(ep.getAddress(), ep.getPort()),
                    new SnapcastRpc.Config(), MonotonicClock.SYSTEM, null);
            sessions.add(session);
            return session;
        }, (task, delay) -> timers.add(task), () -> LABELS, 30_000L);
        feedSessions = new SnapcastFeedSessions(receivers, new SnapcastStreams.MarkerStore() {
            private String marker;

            @Override
            public String load() {
                return marker;
            }

            @Override
            public void save(String value) {
                marker = value;
            }
        }, (task, delay) -> timers.add(task), PcmFeederOutput.FACTORY);
        SnapcastClient member = new SnapcastClient("c1", "member", "", true, new SnapcastVolume(40, false), 0);
        device = SnapcastGroupDevice.of(endpoint.toKey(),
                new SnapcastGroup("g1", "Room", false, "Default", Collections.singletonList(member)), false, LABELS);
    }

    @After
    public void tearDown() {
        for (SnapcastSession session : sessions) {
            session.close();
        }
        control.close();
        simulation.close();
    }

    /** A feed that is READY to a group of the (typed-in or discovered) server; returns the accepted feed socket. */
    private Socket startFeed(SnapcastFeedController controller) throws Exception {
        Gate gate = new Gate();
        controller.start(gate);
        assertTrue(gate.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue("error: " + gate.error, gate.ready);
        assertEquals(SnapcastFeedController.State.READY, controller.getState());
        String streamName = simulation.groupStream("g1");
        ServerSocket listener = simulation.listener(simulation.streamPort(streamName));
        assertNotNull(listener);
        listener.setSoTimeout((int) WAIT_MILLIS);
        return listener.accept();
    }

    private static void assertSocketClosedByPhone(Socket accepted) throws Exception {
        accepted.setSoTimeout((int) WAIT_MILLIS);
        boolean ended;
        try {
            ended = accepted.getInputStream().read() == -1; // end of stream: the phone closed its side
        } catch (java.io.IOException reset) {
            ended = true; // a reset is a close as well
        }
        assertTrue("the feed connection was closed by the phone", ended);
    }

    private void assertFeedEnded(SnapcastFeedController controller, Events events, Socket accepted) throws Exception {
        assertTrue("the listener was told", events.lostLatch.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(FeedError.Kind.CONTROL_LOST, events.lost.get().getKind());
        assertEquals(1, events.lostCount);
        long end = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (controller.getState() != SnapcastFeedController.State.IDLE && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
        assertFalse(controller.isFeeding());
        assertSocketClosedByPhone(accepted);
    }

    @Test
    public void removingATypedInServerEndsARunningFeed() throws Exception {
        directory.setManualServers(Collections.singletonList(endpoint));
        Events events = new Events();
        SnapcastFeedController controller = feedSessions.newController(device, "Phone", events);
        Socket accepted = startFeed(controller);

        directory.setManualServers(Collections.<SnapcastEndpoint>emptyList()); // what "remove server" does

        assertFeedEnded(controller, events, accepted);
        accepted.close();
        controller.release();
    }

    @Test
    public void switchingDiscoveryOffEndsARunningFeedToADiscoveredServer() throws Exception {
        assertTrue(directory.serviceFound("Snapcast", InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), control.port()));
        Events events = new Events();
        SnapcastFeedController controller = feedSessions.newController(device, "Phone", events);
        Socket accepted = startFeed(controller);

        directory.discoveryStopped(); // what the gate does when the setting is switched off

        assertFeedEnded(controller, events, accepted);
        accepted.close();
        controller.release();
    }

    @Test
    public void aServerThatStaysListedKeepsItsFeed() throws Exception {
        directory.setManualServers(Collections.singletonList(endpoint));
        Events events = new Events();
        SnapcastFeedController controller = feedSessions.newController(device, "Phone", events);
        Socket accepted = startFeed(controller);

        directory.discoveryStopped(); // typed-in servers stay

        Thread.sleep(300);
        assertEquals(0, events.lostCount);
        assertEquals(SnapcastFeedController.State.READY, controller.getState());
        accepted.close();
        controller.release();
    }
}
