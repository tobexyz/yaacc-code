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

    @Test
    public void stopBeforeStartIsFinalAndNoThreadEverRuns() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        Ends ends = new Ends();
        EngineHost host = new EngineHost(engine(sink, new DefaultDecoderFactory(null)), 5, EngineHostTest::daemon, ends);
        host.stop();
        host.start(); // the caller that was too late
        assertFalse(host.isRunning());
        assertTrue("the end was reported once", ends.ended.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse("still no thread", host.isRunning());
        assertEquals(EngineState.STOPPED, ends.last.get().state);
        host.stop(); // twice is fine
    }

    @Test
    public void startAndStopFromTwoThreadsAtOnceAlwaysEndWithNoThread() throws Exception {
        for (int round = 0; round < 200; round++) {
            SimulatedSink[] sink = new SimulatedSink[1];
            Ends ends = new Ends();
            final EngineHost host = new EngineHost(engine(sink, new DefaultDecoderFactory(null)), 2, EngineHostTest::daemon, ends);
            final CountDownLatch go = new CountDownLatch(1);
            Thread starter = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                host.start();
            });
            Thread stopper = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                host.stop();
            });
            starter.start();
            stopper.start();
            go.countDown();
            starter.join();
            stopper.join();
            host.stop();
            assertTrue("round " + round, ends.ended.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (host.isRunning() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertFalse("round " + round + ": a thread is left", host.isRunning());
        }
    }

    @Test
    public void theStatusIsPublishedEvenWhenTheNanoClockHasANegativeOrigin() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        Ends ends = new Ends();
        // System.nanoTime() may be negative (its origin is arbitrary); the first publish must not wait for it to turn positive
        EngineHost host = new EngineHost(engine(sink, new DefaultDecoderFactory(null)), 5, EngineHostTest::daemon, ends,
                () -> System.nanoTime() - Long.MAX_VALUE / 2);
        host.start();
        assertTrue(ends.gotStatus.await(5, TimeUnit.SECONDS));
        host.stop();
    }

    @Test
    public void anAbandonedHostReportsACopyAndDoesNotChangeTheStatusOthersHold() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        Ends ends = new Ends();
        EngineHost host = new EngineHost(engine(sink, new DefaultDecoderFactory(null)), 5, EngineHostTest::daemon, ends);
        host.start();
        assertTrue(ends.gotStatus.await(5, TimeUnit.SECONDS));
        EngineStatus held = host.latestStatus();
        EngineState before = held.state;
        host.abandon();
        assertEquals("the object somebody holds is not changed", before, held.state);
        assertEquals(EngineFailure.STALLED, host.latestStatus().failure);
    }

    @Test
    public void chunksThatFindNoRoomAreCountedInTheStatus() throws Exception {
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
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        long now = MicroClock.monotonic().nowMicros();
        host.onChunk(ClientFixtures.chunk(now + 100_000, 0, 960, 2, 16)); // the engine thread waits in the decoder
        Thread.sleep(300);
        for (int i = 0; i < 3000; i++) {
            host.onChunk(ClientFixtures.chunk(0, 0, 10, 2, 16));
        }
        assertTrue(host.droppedForLackOfRoom() > 0);
        release.countDown();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (host.latestStatus() == null || host.latestStatus().hostQueueDrops == 0) {
            assertTrue("the drops are in the status", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        host.stop();
    }

    // ---- security review W1 of Group 8: the thread must obey Stop, a round is bounded, the queue is bounded in bytes

    private static SnapcastClientEngine lenientEngine(SimulatedSink[] sinkOut, DecoderFactory decoders) {
        MicroClock clock = MicroClock.monotonic();
        SimulatedSink sink = SimulatedSink.typical(clock);
        sinkOut[0] = sink;
        // every chunk is "due now" and may be very late: the decoder is called for each one at once
        ClientEngineConfig config = new ClientEngineConfig().releaseLeadMs(60_000).lateToleranceMs(600_000);
        return new SnapcastClientEngine(config, sink, decoders, new ClientFixtures.FakeOffsets(0), clock);
    }

    private static final class SlowDecoders implements DecoderFactory {
        final java.util.concurrent.atomic.AtomicInteger decodes = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        final long millis;

        SlowDecoders(long millis) {
            this.millis = millis;
        }

        @Override
        public FrameDecoder create(de.yaacc.multiroom.protocol.CodecHeaderMessage header, int maxFrames) {
            return new SnapcastClientEngineTest.PassThroughDecoder() {
                @Override
                public byte[] decode(byte[] payload) throws DecoderException {
                    decodes.incrementAndGet();
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.decode(payload);
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }
    }

    private static void floodWithSlowChunks(EngineHost host, int count) {
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        for (int i = 0; i < count; i++) {
            host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16));
        }
    }

    @Test
    public void stopEndsTheThreadPromptlyAndNothingIsDecodedAfterItReturns() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        SlowDecoders decoders = new SlowDecoders(20);
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, decoders), 5, EngineHostTest::daemon, ends);
        host.start();
        floodWithSlowChunks(host, 700);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (decoders.decodes.get() < 10 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("the engine thread is decoding", decoders.decodes.get() >= 10);

        long started = System.nanoTime();
        host.stop();
        long tookMillis = (System.nanoTime() - started) / 1_000_000L;
        int atStop = decoders.decodes.get();
        Thread.sleep(400);
        assertTrue("stop() took " + tookMillis + " ms", tookMillis < 1000);
        assertFalse("the thread ended", host.isRunning());
        assertEquals("nothing is decoded after stop() returned", atStop, decoders.decodes.get());
        assertTrue(decoders.closes.get() >= 1);
    }

    @Test
    public void aFloodOfSlowChunksDoesNotLookLikeAStalledThread() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        SlowDecoders decoders = new SlowDecoders(20);
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, decoders), 5, EngineHostTest::daemon, ends);
        host.start();
        floodWithSlowChunks(host, 700);
        long until = System.nanoTime() + 3_500_000_000L;
        while (System.nanoTime() < until) {
            assertFalse("a thread that works through 20 ms events is not stuck (limit 1 s here, 5 s in the service)",
                    host.isStalled(1000));
            Thread.sleep(20);
        }
        assertTrue(decoders.decodes.get() > 50);
        host.stop();
    }

    @Test
    public void theHostQueueIsBoundedInBytesAndAChunkThatDoesNotFitIsReportedAsLost() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final CountDownLatch release = new CountDownLatch(1);
        DecoderFactory stuck = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.decode(payload);
            }
        };
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, stuck), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16)); // the engine thread sticks in this one
        Thread.sleep(300);
        de.yaacc.multiroom.protocol.WireChunkMessage big = ClientFixtures.rawChunk(now, new byte[3 * 1024 * 1024]);
        for (int i = 0; i < 100; i++) { // 300 MiB offered, the same object: only the accounting is real
            host.onChunk(big);
        }
        assertTrue("queued " + host.queuedBytes(), host.queuedBytes() <= EngineHost.MAX_QUEUED_BYTES);
        assertTrue("some were refused", host.droppedForLackOfRoom() >= 90);
        release.countDown();
        host.stop();
    }

    @Test
    public void anAbandonedHostClosesItsDecoderWhenTheStuckThreadComesBack() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final CountDownLatch stuck = new CountDownLatch(1);
        final CountDownLatch inside = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        DecoderFactory hang = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                inside.countDown();
                try {
                    stuck.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // the abandon interrupts: the thread comes back
                }
                return super.decode(payload);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, hang), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        host.onChunk(ClientFixtures.chunk(MicroClock.monotonic().nowMicros(), 0, 960, 2, 16));
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        host.abandon();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (host.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse("the thread left", host.isRunning());
        assertTrue("and closed the decoder on its way out", closes.get() >= 1);
        stuck.countDown();
    }

    // ---- security review W4 and S16 of Group 8 (cycle 2): no event is lost, no byte is counted for ever

    /** The first chunk takes {@code firstMillis}, every other one is instant. */
    private static final class FirstSlowDecoders implements DecoderFactory {
        final java.util.concurrent.atomic.AtomicInteger decodes = new java.util.concurrent.atomic.AtomicInteger();
        final long firstMillis;
        final CountDownLatch inFirst = new CountDownLatch(1);

        FirstSlowDecoders(long firstMillis) {
            this.firstMillis = firstMillis;
        }

        @Override
        public FrameDecoder create(de.yaacc.multiroom.protocol.CodecHeaderMessage header, int maxFrames) {
            return new SnapcastClientEngineTest.PassThroughDecoder() {
                @Override
                public byte[] decode(byte[] payload) throws DecoderException {
                    if (decodes.incrementAndGet() == 1) {
                        inFirst.countDown();
                        try {
                            Thread.sleep(firstMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return super.decode(payload);
                }
            };
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue("timeout: " + what, System.nanoTime() < deadline);
            Thread.sleep(5);
        }
    }

    @Test
    public void fourHundredChunksBehindOneSlowDecodeAreAllDecodedAndNoByteStaysCounted() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        FirstSlowDecoders decoders = new FirstSlowDecoders(300);
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, decoders), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16));
        assertTrue(decoders.inFirst.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 400; i++) { // queued while the engine thread is in the slow decode
            host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16));
        }
        waitUntil(() -> decoders.decodes.get() >= 401 && host.queuedBytes() == 0, "401 decodes");
        assertEquals("every chunk was decoded, none thrown away at the 257th place", 401, decoders.decodes.get());
        assertEquals(0, host.queuedBytes());
        host.stop();
    }

    @Test
    public void aSettingsMessageAsTheTwoHundredFiftySeventhEventIsNotLost() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        FirstSlowDecoders decoders = new FirstSlowDecoders(300);
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, decoders), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16)); // event 1 of the round, slow
        assertTrue(decoders.inFirst.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 256; i++) { // a full round of the next cycle ...
            host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16));
        }
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 37)); // ... and this one in the 257th place
        waitUntil(() -> host.latestStatus() != null && host.latestStatus().volume == 37, "the settings of the 257th event");
        host.stop();
    }

    @Test
    public void theAttackOfEightRoundsLeavesNoPhantomBytes() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final java.util.concurrent.atomic.AtomicInteger slowOnes = new java.util.concurrent.atomic.AtomicInteger();
        DecoderFactory decoders = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                if (payload.length == 1000 * 4) { // the slow chunk of a cycle
                    slowOnes.incrementAndGet();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.decode(payload);
            }
        };
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, decoders), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        de.yaacc.multiroom.protocol.WireChunkMessage big = ClientFixtures.rawChunk(now, new byte[3 * 1024 * 1024]);
        for (int cycle = 1; cycle <= 8; cycle++) {
            final int expected = cycle;
            host.onChunk(ClientFixtures.chunk(now, 0, 1000, 2, 16)); // slow
            waitUntil(() -> slowOnes.get() >= expected, "cycle " + expected);
            for (int i = 0; i < 255; i++) {
                host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100)); // small events
            }
            host.onChunk(big); // lands on the 257th place
            waitUntil(() -> host.queuedBytes() == 0, "queue drained in cycle " + expected);
        }
        assertEquals(0, host.queuedBytes());
        assertEquals("the cap was never filled with phantom bytes: nothing was refused", 0, host.droppedForLackOfRoom());
        host.stop();
    }

    @Test
    public void afterAStopNoByteIsStillCounted() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch inside = new CountDownLatch(1);
        DecoderFactory stuck = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                inside.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.decode(payload);
            }
        };
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, stuck), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16));
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        de.yaacc.multiroom.protocol.WireChunkMessage big = ClientFixtures.rawChunk(now, new byte[2 * 1024 * 1024]);
        for (int i = 0; i < 4; i++) {
            host.onChunk(big);
        }
        assertTrue(host.queuedBytes() >= 8 * 1024 * 1024);
        release.countDown();
        host.stop();
        assertEquals("what was queued when the host stopped is not counted any more", 0, host.queuedBytes());
    }

    @Test
    public void aHostThatNeverRanAndIsStoppedCountsNoBytes() {
        SimulatedSink[] sink = new SimulatedSink[1];
        EngineHost host = new EngineHost(lenientEngine(sink, new DefaultDecoderFactory(null)), 5, EngineHostTest::daemon, new Ends());
        host.onChunk(ClientFixtures.rawChunk(MicroClock.monotonic().nowMicros(), new byte[1024 * 1024]));
        assertEquals(1024 * 1024, host.queuedBytes());
        host.stop();
        assertEquals(0, host.queuedBytes());
    }

    @Test
    public void refusedChunksDoNotUseUpTheRoomOfSettingsHeadersAndLosses() throws Exception {
        SimulatedSink[] sink = new SimulatedSink[1];
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch inside = new CountDownLatch(1);
        DecoderFactory stuck = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                inside.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.decode(payload);
            }
        };
        Ends ends = new Ends();
        EngineHost host = new EngineHost(lenientEngine(sink, stuck), 5, EngineHostTest::daemon, ends);
        host.start();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 100));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        long now = MicroClock.monotonic().nowMicros();
        host.onChunk(ClientFixtures.chunk(now, 0, 960, 2, 16));
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        de.yaacc.multiroom.protocol.WireChunkMessage big = ClientFixtures.rawChunk(now, new byte[3 * 1024 * 1024]);
        for (int i = 0; i < 1500; i++) {
            host.onChunk(big);
        }
        long refused = host.droppedForLackOfRoom();
        host.onServerSettings(new ServerSettingsMessage(0, 0, false, 37));
        host.onCodecHeader(ClientFixtures.flacHeaderMessage(44100, 2, 16));
        host.onLost(null);
        assertEquals("settings, header and loss found room; only chunks were refused", refused, host.droppedForLackOfRoom());
        assertEquals("1495 of the 1500 chunks did not fit", 1495, refused);
        release.countDown();
        waitUntil(() -> host.latestStatus() != null && host.latestStatus().volume == 37, "the settings after the flood");
        host.stop();
    }
}
