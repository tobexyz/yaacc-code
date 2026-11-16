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
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SocketTransport;

import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The whole path on the loopback address with real sockets and no sleeping beyond the 140 ms of real-time pacing: the
 * real {@code SnapcastRpc} talks to a simulated snapserver, {@link SnapcastStreams} adds the stream, a group is moved
 * to it, and a {@link PcmFeeder} connects to the <b>peer address of the control connection</b> and the port that was
 * asked for, and the server side reads the audio.
 */
public class SnapcastFeedIntegrationTest {

    private static final int CHUNK = 3_840;

    private final SimulatedSnapcastServer server = new SimulatedSnapcastServer();
    private SnapcastRpc rpc;
    private LoopbackControlServer control;
    private PcmFeeder feeder;

    @After
    public void cleanUp() {
        if (feeder != null) {
            feeder.close();
        }
        if (rpc != null) {
            rpc.close();
        }
        if (control != null) {
            control.close();
        }
        server.close();
    }

    @Test
    public void aGroupIsFedThroughTheControlConnectionsPeerAddressAndTheRequestedPort() throws Exception {
        server.bindRealListeners();
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        control = new LoopbackControlServer(server);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        rpc = new SnapcastRpc(new SocketTransport(loopback, control.port()), new SnapcastRpc.Config(), null, null,
                MonotonicClock.SYSTEM);
        rpc.start();
        long end = System.nanoTime() + 5_000_000_000L;
        while (rpc.getState() != SnapcastRpc.State.CONNECTED && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(SnapcastRpc.State.CONNECTED, rpc.getState());

        final String[] markerHolder = new String[1];
        SnapcastStreams streams = new SnapcastStreams(new SnapcastOperations(rpc), "Integration phone",
                new SnapcastStreams.MarkerStore() {
                    @Override
                    public String load() {
                        return markerHolder[0];
                    }

                    @Override
                    public void save(String marker) {
                        markerHolder[0] = marker;
                    }
                }, MonotonicClock.SYSTEM, SnapcastStreams.DEFAULT_IDLE_TIMEOUT_MILLIS, new Random(5));
        SnapcastStreams.EnsureResult ensured = streams.ensure();
        assertEquals(SnapcastStreams.Status.READY, ensured.getStatus());
        assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("g1"));
        assertEquals(streams.getStreamName(), server.groupStream("g1"));

        // the feed target: the peer of the control connection and the port YAACC asked for, nothing from a reply
        InetAddress peer = rpc.getPeerAddress();
        assertNotNull(peer);
        assertEquals(loopback, peer);
        final ServerSocket listener = server.listener(ensured.getPort());
        assertNotNull("the simulated server listens on the requested port", listener);
        final byte[] received = new byte[10 * CHUNK];
        final AtomicReference<Exception> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(5_000);
                new DataInputStream(socket.getInputStream()).readFully(received);
            } catch (Exception e) {
                readFailure.set(e);
            }
        }, "integration-reader");
        reader.setDaemon(true);
        reader.start();

        feeder = PcmFeeder.connect(peer, ensured.getPort(), PcmFormat.SNAPCAST_DEFAULT, null,
                SocketFeedConnection.FACTORY, FeedClock.SYSTEM, null);
        byte[] audio = new byte[10 * CHUNK];
        new Random(9).nextBytes(audio);
        for (int i = 0; i < audio.length; i++) {
            audio[i] |= 1; // not silence
        }
        assertTrue(feeder.write(audio, 0, audio.length));
        streams.noteAudio();
        reader.join(5_000);

        assertEquals(null, readFailure.get());
        assertArrayEquals(audio, received);

        feeder.close();
        assertEquals(SnapcastStreams.ReleaseResult.RESTORED, streams.release("g1"));
        assertEquals("Default", server.groupStream("g1"));
        assertTrue(streams.shutdown());
        assertFalse(server.hasStream(streams.getStreamName()));
        assertEquals(null, server.listener(ensured.getPort()));
    }

    @Test
    public void anAlreadyUsedPortOfTheRangeIsSkippedOverTheRealConnection() throws Exception {
        server.bindRealListeners();
        server.takePort(4953);
        control = new LoopbackControlServer(server);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        rpc = new SnapcastRpc(new SocketTransport(loopback, control.port()), new SnapcastRpc.Config(), null, null,
                MonotonicClock.SYSTEM);
        rpc.start();
        long end = System.nanoTime() + 5_000_000_000L;
        while (rpc.getState() != SnapcastRpc.State.CONNECTED && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        SnapcastStreams streams = new SnapcastStreams(new SnapcastOperations(rpc), "Integration phone",
                new SnapcastStreams.MarkerStore() {
                    @Override
                    public String load() {
                        return null;
                    }

                    @Override
                    public void save(String marker) {
                        // not kept
                    }
                }, MonotonicClock.SYSTEM, SnapcastStreams.DEFAULT_IDLE_TIMEOUT_MILLIS, new Random(6));

        SnapcastStreams.EnsureResult ensured = streams.ensure();

        // the server error came over the wire as -32603 with the reason in data, and the next port was tried
        assertEquals(SnapcastStreams.Status.READY, ensured.getStatus());
        assertTrue("port " + ensured.getPort(), ensured.getPort() > 4953);
        assertNotNull(server.listener(ensured.getPort()));
        assertTrue(streams.shutdown());
    }
}
