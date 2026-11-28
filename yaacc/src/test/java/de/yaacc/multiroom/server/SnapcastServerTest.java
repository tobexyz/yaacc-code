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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.SnapcastConnection;
import de.yaacc.multiroom.protocol.WireChunkMessage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The stream port of the built-in server against the app's own binary client (issue #330, task 2.3): Hello, settings, codec header,
 * chunks with the stream clock, the time exchange, a change of stream, and the limits.
 */
public class SnapcastServerTest {

    private final MicroClock clock = MicroClock.monotonic();
    private final AtomicInteger groups = new AtomicInteger();
    private final List<JSONObject> notifications = new CopyOnWriteArrayList<>();
    private final ServerState state = new ServerState("Phone", "0.35.0-yaacc", 1000, () -> "g" + groups.incrementAndGet(),
            System::currentTimeMillis);
    private SnapcastServer server;
    private final List<SnapcastConnection> clients = new ArrayList<>();

    @After
    public void tearDown() {
        for (SnapcastConnection c : clients) {
            c.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private SnapcastServer start(java.util.function.Predicate<InetAddress> accept, int maxClients) throws Exception {
        server = new SnapcastServer(state, clock, accept, maxClients, notifications::addAll);
        server.start(0);
        return server;
    }

    private static final class Recorder implements SnapcastConnection.Listener {
        final BlockingQueue<ServerSettingsMessage> settings = new LinkedBlockingQueue<>();
        final BlockingQueue<CodecHeaderMessage> headers = new LinkedBlockingQueue<>();
        final BlockingQueue<WireChunkMessage> chunks = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> offsets = new LinkedBlockingQueue<>();
        final BlockingQueue<SnapcastConnection.Loss> losses = new LinkedBlockingQueue<>();

        @Override
        public void onServerSettings(ServerSettingsMessage s) {
            settings.add(s);
        }

        @Override
        public void onCodecHeader(CodecHeaderMessage h) {
            headers.add(h);
        }

        @Override
        public void onChunk(WireChunkMessage c) {
            chunks.add(c);
        }

        @Override
        public void onOffsetUpdate(long offsetMicros, long jitterMicros) {
            offsets.add(offsetMicros);
        }

        @Override
        public void onLost(SnapcastConnection.Loss loss) {
            losses.add(loss);
        }
    }

    private Recorder join(String id) throws Exception {
        Recorder r = new Recorder();
        SnapcastConnection c = SnapcastConnection.forAddress(InetAddress.getLoopbackAddress(), server.streamPort(),
                HelloMessage.create(id, "host-" + id, "YAACC", "Android", "arm64", "1.0"));
        c.addListener(r);
        clients.add(c);
        c.start();
        return r;
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(what, condition.getAsBoolean());
    }

    private StreamPipeline stream(String id) {
        return server.addStream(id, "tcp://0.0.0.0:4953?name=" + id, 48000, 2, 16, new ChunkEncoder.Pcm(48000, 2, 16));
    }

    @Test(timeout = 60000)
    public void aClientGetsItsSettingsThenTheCodecHeaderThenTheChunksOfItsStream() throws Exception {
        start(a -> true, 16);
        StreamPipeline pipeline = stream("main");
        Recorder r = join("c1");
        ServerSettingsMessage settings = r.settings.poll(10, TimeUnit.SECONDS);
        assertNotNull(settings);
        assertEquals(1000, settings.getBufferMs());
        assertEquals(100, settings.getVolume());
        assertFalse(settings.isMuted());
        CodecHeaderMessage header = r.headers.poll(10, TimeUnit.SECONDS);
        assertNotNull(header);
        assertEquals(48000, header.getSampleRate());
        assertEquals(2, header.getChannels());
        assertEquals(16, header.getBitsPerSample());

        byte[] pcm = new byte[pipeline.chunkBytes()];
        pcm[0] = 7;
        long t0 = clock.nowMicros();
        pipeline.write(pcm, 0, pcm.length, t0);
        WireChunkMessage chunk = r.chunks.poll(10, TimeUnit.SECONDS);
        assertNotNull("a chunk arrives", chunk);
        assertEquals(t0, chunk.timestampMicros());
        assertEquals(3840, chunk.getPayloadLength());
        assertEquals(7, chunk.getPayload()[0]);
    }

    @Test(timeout = 60000)
    public void theClientIsInTheStateWhileItIsConnectedAndDisconnectedAfterwards() throws Exception {
        start(a -> true, 16);
        stream("main");
        Recorder r = join("c1");
        assertNotNull(r.settings.poll(10, TimeUnit.SECONDS));
        waitFor("connected in the state", () -> {
            try {
                return SnapcastServerStatus.fromResult(state.status()).findClient("c1") != null
                        && SnapcastServerStatus.fromResult(state.status()).findClient("c1").isConnected();
            } catch (Exception e) {
                return false;
            }
        });
        clients.get(0).close();
        waitFor("disconnected in the state", () -> {
            try {
                return !SnapcastServerStatus.fromResult(state.status()).findClient("c1").isConnected();
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue("the notifications told both", notifications.toString().contains("Client.OnConnect")
                && notifications.toString().contains("Client.OnDisconnect"));
    }

    @Test(timeout = 60000)
    public void theTimeExchangeGivesTheClientAnOffsetNearZeroOnTheSameClock() throws Exception {
        start(a -> true, 16);
        stream("main");
        Recorder r = join("c1");
        Long offset = r.offsets.poll(20, TimeUnit.SECONDS);
        assertNotNull("the client synchronised", offset);
        assertTrue("the offset is small: " + offset, Math.abs(offset) < 20_000L);
    }

    @Test(timeout = 60000)
    public void aGroupThatMovesToAnotherStreamGetsTheNewHeader() throws Exception {
        start(a -> true, 16);
        stream("a");
        server.addStream("b", "tcp://0.0.0.0:4954?name=b", 44100, 2, 16, new ChunkEncoder.Pcm(44100, 2, 16));
        Recorder r = join("c1");
        assertEquals(48000, r.headers.poll(10, TimeUnit.SECONDS).getSampleRate());
        waitFor("known", () -> state.knowsClient("c1"));
        state.setGroupStream(state.groupIdOf("c1"), "b");
        server.applyChange();
        CodecHeaderMessage second = r.headers.poll(10, TimeUnit.SECONDS);
        assertNotNull(second);
        assertEquals(44100, second.getSampleRate());
    }

    @Test(timeout = 60000)
    public void aVolumeSetOnTheServerIsSentToTheClient() throws Exception {
        start(a -> true, 16);
        stream("main");
        Recorder r = join("c1");
        assertNotNull(r.settings.poll(10, TimeUnit.SECONDS));
        waitFor("known", () -> state.knowsClient("c1"));
        state.setClientVolume("c1", 30, false);
        server.applyChange();
        ServerSettingsMessage next = r.settings.poll(10, TimeUnit.SECONDS);
        assertNotNull(next);
        assertEquals(30, next.getVolume());
    }

    @Test(timeout = 60000)
    public void anAddressTheFilterRefusesIsClosedBeforeAnythingIsRead() throws Exception {
        start(a -> false, 16);
        stream("main");
        Recorder r = join("c1");
        assertNotNull("the client is let go", r.losses.poll(20, TimeUnit.SECONDS));
        assertFalse(state.knowsClient("c1"));
    }

    @Test(timeout = 60000)
    public void atMostTheGivenNumberOfClientsAreServedAtOnce() throws Exception {
        start(a -> true, 1);
        stream("main");
        Recorder first = join("c1");
        assertNotNull(first.settings.poll(10, TimeUnit.SECONDS));
        Recorder second = join("c2");
        assertNotNull("the second is let go", second.losses.poll(20, TimeUnit.SECONDS));
        assertFalse(state.knowsClient("c2"));
    }

    @Test(timeout = 60000)
    public void aClientThatStopsReadingIsDroppedWhenItsQueueIsFull() throws Exception {
        start(a -> true, 16);
        StreamPipeline pipeline = stream("main");
        java.net.Socket raw = new java.net.Socket(InetAddress.getLoopbackAddress(), server.streamPort());
        try {
            raw.getOutputStream().write(helloFrame());
            raw.getOutputStream().flush();
            waitFor("connected", () -> state.knowsClient("slow"));
            byte[] pcm = new byte[pipeline.chunkBytes()];
            long now = clock.nowMicros();
            // the raw client never reads: far more than the cap is queued
            for (int i = 0; i < 4000 && server.sessionOf("slow") != null && !server.sessionOf("slow").isClosed(); i++) {
                pipeline.write(pcm, 0, pcm.length, now);
                now += 20_000L;
            }
            waitFor("the session is closed", () -> server.sessionOf("slow") == null || server.sessionOf("slow").isClosed());
        } finally {
            raw.close();
        }
    }

    private static byte[] helloFrame() {
        return de.yaacc.multiroom.protocol.SnapcastMessageCodec.encode(
                HelloMessage.create("slow", "slow", "YAACC", "Android", "arm64", "1.0"), 1, 0, 0, 0, 0, 0);
    }
}
