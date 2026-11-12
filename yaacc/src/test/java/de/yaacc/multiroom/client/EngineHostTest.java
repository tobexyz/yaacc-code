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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The thread around the engine, with real threads and a real clock; waits are on latches. */
public class EngineHostTest {

    private static final class Ends implements EngineHost.Observer {
        final CountDownLatch ended = new CountDownLatch(1);
        final CountDownLatch gotStatus = new CountDownLatch(1);
        final AtomicReference<EngineStatus> last = new AtomicReference<>();

        @Override
        public void onStatus(EngineStatus status) {
            last.set(status);
            gotStatus.countDown();
        }

        @Override
        public void onEnded(EngineStatus status) {
            last.set(status);
            ended.countDown();
        }
    }

    private static Thread daemon(Runnable r) {
        Thread t = new Thread(r, "test-audio");
        t.setDaemon(true);
        return t;
    }

    private static SnapcastClientEngine engine(SimulatedSink[] sinkOut, DecoderFactory decoders) {
        MicroClock clock = MicroClock.monotonic();
        SimulatedSink sink = SimulatedSink.typical(clock);
        sinkOut[0] = sink;
        return new SnapcastClientEngine(new ClientEngineConfig(), sink, decoders, new ClientFixtures.FakeOffsets(0), clock);
    }

    @Test
    public void eventsReachTheEngineInOrderAndStopFreesTheOutput() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        Ends ends = new Ends();
        EngineHost host = new EngineHost(engine(sink, new DefaultDecoderFactory(null)), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(1000, 0, false, 40));
        host.onCodecHeader(ClientFixtures.pcmHeader(48000, 2, 16));
        assertTrue(ends.gotStatus.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!"pcm".equals(host.latestStatus().codec) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("pcm", host.latestStatus().codec);
        assertEquals(0.4f, sink[0].gain, 0.0001f);

        host.stop();
        assertTrue(ends.ended.await(5, TimeUnit.SECONDS));
        assertFalse(host.isRunning());
        assertEquals(EngineState.STOPPED, ends.last.get().state);
        assertFalse(sink[0].open);
        assertEquals(1, sink[0].releases);
    }

    @Test
    public void aFloodOfChunksNeverBlocksTheCallerAndIsBounded() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final CountDownLatch release = new CountDownLatch(1);
        DecoderFactory slow = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.decode(payload);
            }
        };
        Ends ends = new Ends();
        EngineHost host = new EngineHost(engine(sink, slow), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onCodecHeader(ClientFixtures.pcmHeader(48000, 2, 16));
        long start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            host.onChunk(ClientFixtures.chunk(0, 0, 10, 2, 16));
        }
        assertTrue("the listener thread was not held", System.nanoTime() - start < 2_000_000_000L);
        release.countDown();
        host.stop();
        assertTrue(ends.ended.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void aStuckEngineThreadIsReportedAsStalledAndCanBeAbandoned() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final CountDownLatch stuck = new CountDownLatch(1);
        final CountDownLatch inside = new CountDownLatch(1);
        DecoderFactory hang = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                inside.countDown();
                try {
                    stuck.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.decode(payload);
            }
        };
        Ends ends = new Ends();
        SnapcastClientEngine engine = engine(sink, hang);
        EngineHost host = new EngineHost(engine, 5, EngineHostTest::daemon, ends);
        host.start();
        host.onCodecHeader(ClientFixtures.pcmHeader(48000, 2, 16));
        // a chunk that is due at once: the clock offset is 0 and the timestamp is the buffer behind now
        long now = MicroClock.monotonic().nowMicros();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onChunk(ClientFixtures.chunk(now + 100_000, 0, 960, 2, 16));
        assertTrue("the decoder was entered", inside.await(5, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertTrue(host.isStalled(100));
        assertFalse(host.isStalled(60_000));

        host.abandon();
        assertTrue(ends.ended.await(5, TimeUnit.SECONDS));
        assertEquals(EngineFailure.STALLED, ends.last.get().failure);
        assertNotNull(host.latestStatus());
        stuck.countDown();
    }
}
