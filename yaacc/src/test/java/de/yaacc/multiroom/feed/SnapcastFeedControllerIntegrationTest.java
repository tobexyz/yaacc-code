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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SocketTransport;

import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The controller with the real parts: a {@code SnapcastRpc} over a loopback socket to a simulated snapserver,
 * {@link SnapcastStreams}, and a real {@link PcmFeeder} that connects to the control connection's peer address and the
 * requested port; the server side of the stream port reads the audio. No sleeping beyond the pacing of the feeder and
 * the drain before a close.
 */
public class SnapcastFeedControllerIntegrationTest {

    private static final int CHUNK = 3_840;
    private static final long WAIT_MILLIS = 8_000;

    private final SimulatedSnapcastServer simulation = new SimulatedSnapcastServer();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "controller-it-worker");
        thread.setDaemon(true);
        return thread;
    });
    private SnapcastRpc rpc;
    private LoopbackControlServer control;
    private SnapcastOperations operations;

    @After
    public void cleanUp() {
        worker.shutdownNow();
        if (rpc != null) {
            rpc.close();
        }
        if (control != null) {
            control.close();
        }
        simulation.close();
    }

    private void connect() throws Exception {
        simulation.bindRealListeners();
        simulation.addPipeStream("Default");
        simulation.addGroup("g1", "Default", "c1");
        control = new LoopbackControlServer(simulation);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        rpc = new SnapcastRpc(new SocketTransport(loopback, control.port()), new SnapcastRpc.Config(), null, null,
                MonotonicClock.SYSTEM);
        rpc.start();
        long end = System.nanoTime() + 5_000_000_000L;
        while (rpc.getState() != SnapcastRpc.State.CONNECTED && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(SnapcastRpc.State.CONNECTED, rpc.getState());
        operations = new SnapcastOperations(rpc);
    }

    private FeedServer feedServer() {
        return new FeedServer() {
            @Override
            public boolean awaitControl(long timeoutMillis) {
                return rpc.getState() == SnapcastRpc.State.CONNECTED;
            }

            @Override
            public InetAddress peerAddress() {
                return rpc.getPeerAddress();
            }

            @Override
            public SnapcastGroup findGroup(String groupId) {
                try {
                    return operations.getStatus().findGroup(groupId);
                } catch (SnapcastException failure) {
                    return null;
                }
            }

            @Override
            public void setGroupVolume(SnapcastGroup current, Map<String, Integer> startLevels, int percent)
                    throws SnapcastException {
                operations.setGroupVolume(current, startLevels, percent);
            }

            @Override
            public void setGroupMuted(String groupId, boolean muted) throws SnapcastException {
                operations.setGroupMute(groupId, muted);
            }
        };
    }

    private SnapcastStreams realStreams() {
        return new SnapcastStreams(operations, "Controller phone", new SnapcastStreams.MarkerStore() {
            private String marker;

            @Override
            public String load() {
                return marker;
            }

            @Override
            public void save(String value) {
                marker = value;
            }
        }, MonotonicClock.SYSTEM, SnapcastStreams.DEFAULT_IDLE_TIMEOUT_MILLIS, new Random(11));
    }

    private static final class Events implements SnapcastFeedController.Listener {
        final AtomicReference<FeedError> lost = new AtomicReference<>();

        @Override
        public void onFeedLost(FeedError error) {
            lost.set(error);
        }

        @Override
        public void onLog(String line) {
            // not needed here
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

    private SnapcastFeedController controller(SnapcastStreams streams, Events events, Runnable released) {
        return new SnapcastFeedController(streams, feedServer(), PcmFeederOutput.FACTORY, new FeedSlots().slotFor("it"),
                "g1", FeedClock.SYSTEM, (task, delay) -> {
        }, worker, events, new SnapcastFeedController.Config(), released);
    }

    private static byte[] audio(int chunks) {
        byte[] audio = new byte[chunks * CHUNK];
        new Random(3).nextBytes(audio);
        for (int i = 0; i < audio.length; i++) {
            audio[i] |= 1; // never digital silence
        }
        return audio;
    }

    @Test
    public void aGroupIsPlayedThroughTheWholeChainAndStopRestoresItAfterTheLastChunkWasSent() throws Exception {
        connect();
        SnapcastStreams streams = realStreams();
        Events events = new Events();
        SnapcastFeedController controller = controller(streams, events, null);
        Gate gate = new Gate();

        controller.start(gate);
        assertTrue(gate.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue("error: " + gate.error, gate.ready);
        assertEquals("the group plays YAACC's stream", streams.getStreamName(), simulation.groupStream("g1"));

        int port = simulation.streamPort(streams.getStreamName());
        ServerSocket listener = simulation.listener(port);
        assertNotNull(listener);
        final byte[] sent = audio(10);
        final byte[] received = new byte[sent.length];
        final AtomicReference<Exception> readFailure = new AtomicReference<>();
        final CountDownLatch read = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(5_000);
                new DataInputStream(socket.getInputStream()).readFully(received);
            } catch (Exception e) {
                readFailure.set(e);
            } finally {
                read.countDown();
            }
        }, "controller-it-reader");
        reader.setDaemon(true);
        reader.start();

        assertTrue(controller.sink().write(sent, 0, sent.length));
        controller.stop(); // right after the last write: the drain must let the tail through

        assertTrue(read.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(null, readFailure.get());
        assertArrayEquals("every byte, also the last chunk", sent, received);
        awaitIdle(controller);
        assertEquals("the group has its old stream again", "Default", simulation.groupStream("g1"));
        assertTrue("the stream stays for a quick restart", simulation.hasStream(streams.getStreamName()));
        assertFalse(controller.isFeeding());
    }

    @Test
    public void aReleaseRemovesTheStreamAndAStartAfterwardsMakesItAgain() throws Exception {
        connect();
        SnapcastStreams streams = realStreams();
        final CountDownLatch released = new CountDownLatch(1);
        SnapcastFeedController controller = controller(streams, new Events(), released::countDown);
        Gate first = new Gate();
        controller.start(first);
        assertTrue(first.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue(first.ready);

        controller.release();

        assertTrue(released.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertFalse(simulation.hasStream(streams.getStreamName()));
        assertEquals("Default", simulation.groupStream("g1"));
    }

    @Test
    public void aServerThatRefusesTheStreamGivesATypedErrorAndChangesNothing() throws Exception {
        connect();
        simulation.failAddStream(SimulatedSnapcastServer.error(-32603, "Internal error", "tcp sources are not allowed"));
        SnapcastFeedController controller = controller(realStreams(), new Events(), null);
        Gate gate = new Gate();

        controller.start(gate);

        assertTrue(gate.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertFalse(gate.ready);
        assertEquals(FeedError.Kind.REFUSED, gate.error.getKind());
        assertEquals(SnapcastStreams.HINT_STATIC_SOURCE, gate.error.getHintKey());
        assertEquals("Default", simulation.groupStream("g1"));
        assertFalse(controller.isFeeding());
    }

    @Test
    public void aServerThatClosesTheFeedEndsItCleanlyAndTheListenerHearsOnce() throws Exception {
        connect();
        SnapcastStreams streams = realStreams();
        Events events = new Events();
        SnapcastFeedController controller = controller(streams, events, null);
        Gate gate = new Gate();
        controller.start(gate);
        assertTrue(gate.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        ServerSocket listener = simulation.listener(simulation.streamPort(streams.getStreamName()));
        Socket accepted = listener.accept();
        accepted.close(); // the server drops the producer

        byte[] audio = audio(1);
        long end = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (events.lost.get() == null && System.nanoTime() < end) {
            controller.sink().write(audio, 0, audio.length); // writing finds out that the peer is gone
            Thread.sleep(20);
        }

        assertNotNull("the listener was told", events.lost.get());
        assertEquals(FeedError.Kind.FEED_FAILED, events.lost.get().getKind());
        long wait = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (controller.getState() != SnapcastFeedController.State.IDLE && System.nanoTime() < wait) {
            Thread.sleep(10);
        }
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
        assertEquals("Default", simulation.groupStream("g1"));
    }

    private static void awaitIdle(SnapcastFeedController controller) throws InterruptedException {
        long end = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (controller.getState() != SnapcastFeedController.State.IDLE && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
    }
}
