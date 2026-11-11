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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * The feeder with a fake connection and a clock that the test moves, plus a few runs over a real loopback socket.
 * Format 48000:16:2, chunks of 20 ms = 3,840 bytes, look-ahead 3 chunks, queue of 8 chunks.
 */
public class PcmFeederTest {

    private static final PcmFormat FORMAT = PcmFormat.SNAPCAST_DEFAULT;
    private static final int CHUNK = 3_840;
    private static final long CHUNK_NANOS = 20_000_000L;
    private static final long LOOK_AHEAD_NANOS = 3 * CHUNK_NANOS;
    private static final InetAddress PEER = address(192, 0, 2, 10);

    private final ManualFeedClock clock = new ManualFeedClock();
    private final FakeFactory factory = new FakeFactory(clock);
    private final List<PcmFeeder> feeders = new ArrayList<>();
    private final int threadsBefore = feedThreads();

    @After
    public void closeFeeders() {
        for (PcmFeeder feeder : feeders) {
            feeder.close();
        }
    }

    // ---- fakes

    private static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A connection that records every write with the virtual time; a test can make it block, fail or run a hook. */
    private static final class FakeConnection implements FeedConnection {
        private final ManualFeedClock clock;
        private final List<long[]> times = new ArrayList<>(); // {virtual nanos}
        private final List<byte[]> chunks = new ArrayList<>();
        private boolean closed;
        private volatile boolean blocked;
        private volatile boolean blockNext;
        private volatile boolean failNext;
        private volatile IntConsumer onWrite;

        FakeConnection(ManualFeedClock clock) {
            this.clock = clock;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            int index;
            synchronized (this) {
                if (closed) {
                    throw new IOException("closed");
                }
                index = chunks.size();
                times.add(new long[]{clock.nanoTime()});
                chunks.add(Arrays.copyOfRange(data, offset, offset + length));
                notifyAll();
            }
            IntConsumer hook = onWrite;
            if (hook != null) {
                hook.accept(index);
            }
            if (failNext) {
                throw new IOException("peer reset");
            }
            if (blockNext) {
                synchronized (this) {
                    blocked = true;
                    notifyAll();
                    while (!closed && blockNext) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted");
                        }
                    }
                    if (closed) {
                        throw new IOException("closed");
                    }
                    blocked = false;
                }
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        synchronized boolean isClosed() {
            return closed;
        }

        synchronized int count() {
            return chunks.size();
        }

        synchronized long timeOf(int index) {
            return times.get(index)[0];
        }

        synchronized byte[] chunk(int index) {
            return chunks.get(index);
        }

        synchronized byte[] all() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) {
                out.write(chunk, 0, chunk.length);
            }
            return out.toByteArray();
        }

        synchronized boolean awaitCount(int count, long timeoutMillis) throws InterruptedException {
            long end = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (chunks.size() < count) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                wait(Math.max(1, left / 1_000_000L));
            }
            return true;
        }

        void blockTheNextWrite() {
            blockNext = true;
        }

        synchronized void release() {
            blockNext = false;
            notifyAll();
        }

        boolean isBlocked() {
            return blocked;
        }
    }

    private static final class FakeFactory implements FeedConnection.Factory {
        final ManualFeedClock clock;
        final List<Object[]> opened = new ArrayList<>(); // {address, port, timeout, sendBuffer}
        FakeConnection connection;
        IOException failure;
        RuntimeException runtimeFailure;

        FakeFactory(ManualFeedClock clock) {
            this.clock = clock;
        }

        @Override
        public FeedConnection open(InetAddress address, int port, int connectTimeoutMillis, int sendBufferBytes)
                throws IOException {
            opened.add(new Object[]{address, port, connectTimeoutMillis, sendBufferBytes});
            if (failure != null) {
                throw failure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            connection = new FakeConnection(clock);
            return connection;
        }
    }

    private static final class RecordingListener implements PcmFeeder.Listener {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<PcmFeeder.Failure> failure = new AtomicReference<>();
        final CountDownLatch called = new CountDownLatch(1);

        @Override
        public void onFailed(PcmFeeder.Failure reason) {
            failure.set(reason);
            calls.incrementAndGet();
            called.countDown();
        }
    }

    private PcmFeeder.Config fastConfig() {
        return new PcmFeeder.Config().watchdogPollMillis(5).writeWaitMillis(5_000);
    }

    private PcmFeeder open(PcmFeeder.Config config, PcmFeeder.Listener listener) throws IOException {
        PcmFeeder feeder = PcmFeeder.connect(PEER, 4953, FORMAT, config, factory, clock, listener);
        feeders.add(feeder);
        return feeder;
    }

    private PcmFeeder open() throws IOException {
        return open(fastConfig(), null);
    }

    /** Bytes 0..255 repeating with a per-chunk offset, so a chunk out of order or lost shows. */
    private static byte[] pattern(int chunks) {
        byte[] data = new byte[chunks * CHUNK];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 31 + i / CHUNK * 7 + 1) | 1); // never zero, so it differs from silence
        }
        return data;
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMillis) {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static int feedThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("snapcast-feed")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Moves the virtual clock from wake-up to wake-up until the connection holds {@code count} chunks. The count is
     * looked at again once the writer sleeps: a sleeping writer can not write, so the count is final at that moment and
     * the clock is never moved one step too far (that step would make the writer fill the gap with silence).
     */
    private void runUntilWritten(FakeConnection connection, int count) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (true) {
            if (connection.count() >= count) {
                return;
            }
            if (System.nanoTime() > end) {
                fail("only " + connection.count() + " of " + count + " chunks were written");
            }
            if (clock.awaitSleepers(1, 100)) {
                if (connection.count() >= count) {
                    return;
                }
                clock.advanceToNextWake();
            } else {
                Thread.yield();
            }
        }
    }

    // ---- connect target

    @Test
    public void itConnectsToTheGivenAddressAndPortOnly() throws Exception {
        PcmFeeder feeder = open();

        assertEquals(1, factory.opened.size());
        Object[] call = factory.opened.get(0);
        assertSame(PEER, call[0]);
        assertEquals(4953, call[1]);
        assertEquals(5_000, call[2]);
        assertTrue("small send buffer", (Integer) call[3] <= 8 * CHUNK);
        assertEquals(PcmFeeder.State.RUNNING, feeder.getState());
    }

    @Test
    public void theWildcardAndMulticastAddressesAndBadPortsAreRefusedBeforeAnythingIsOpened() throws Exception {
        InetAddress[] refused = {
                address(0, 0, 0, 0),             // what a server reports as uri.host: that would be the phone itself
                address(224, 0, 0, 1),           // multicast
                InetAddress.getByName("::"),     // the IPv6 wildcard
                InetAddress.getByName("ff02::1"), // IPv6 multicast
                null};
        for (InetAddress bad : refused) {
            try {
                PcmFeeder.connect(bad, 4953, FORMAT, null, factory, clock, null);
                fail("address must be refused: " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        for (int port : new int[]{0, -1, 65536, Integer.MAX_VALUE}) {
            try {
                PcmFeeder.connect(PEER, port, FORMAT, null, factory, clock, null);
                fail("port must be refused: " + port);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        assertTrue("nothing was opened", factory.opened.isEmpty());
    }

    @Test
    public void aFailedConnectThrowsAFixedMessageWithoutTheAddress() throws Exception {
        factory.failure = new IOException("Connection refused: /192.0.2.10:4953");

        try {
            open();
            fail("connect must fail");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("192.0.2"));
            assertNull("no cause that could carry the address", expected.getCause());
        }
        assertEquals(threadsBefore, feedThreads());
    }

    @Test
    public void aRuntimeFailureOfTheFactoryIsAnIoFailureToo() throws Exception {
        factory.runtimeFailure = new SecurityException("192.0.2.10 denied");

        try {
            open();
            fail("connect must fail");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("192.0.2"));
        }
    }

    // ---- writing

    @Test
    public void everyByteArrivesInOrderInWholeChunksAndATailIsPaddedWithZeros() throws Exception {
        PcmFeeder feeder = open();
        byte[] data = pattern(3);
        byte[] part = Arrays.copyOf(data, 2 * CHUNK + 320); // two chunks and a short tail

        assertTrue(feeder.write(part, 0, 1_000));
        assertTrue(feeder.write(part, 1_000, 5_000));
        assertTrue(feeder.write(part, 6_000, part.length - 6_000));
        runUntilWritten(factory.connection, 3);

        byte[] expected = Arrays.copyOf(part, 3 * CHUNK); // zero padded
        byte[] written = factory.connection.all();
        assertArrayEquals(expected, Arrays.copyOf(written, 3 * CHUNK));
        for (int i = 0; i < 3; i++) {
            assertEquals(CHUNK, factory.connection.chunk(i).length);
        }
        assertEquals("the tail is audio, not silence", 0, feeder.getSilenceChunks());
    }

    @Test
    public void theLeadNeverExceedsTheLookAheadAndNoChunkIsLate() throws Exception {
        PcmFeeder feeder = open();
        FakeConnection connection = factory.connection;
        final int rounds = 25;

        for (int round = 0; round < rounds; round++) {
            assertTrue(feeder.write(pattern(8), 0, 8 * CHUNK));
            runUntilWritten(connection, 8 * (round + 1));
        }

        long first = connection.timeOf(0);
        for (int k = 0; k < connection.count(); k++) {
            long lead = (k + 1) * CHUNK_NANOS - (connection.timeOf(k) - first);
            assertTrue("chunk " + k + " lead " + lead, lead <= LOOK_AHEAD_NANOS);
            assertTrue("chunk " + k + " was written after its time", connection.timeOf(k) - first <= k * CHUNK_NANOS);
        }
        assertEquals(0, feeder.getLateChunks());
        assertEquals(0, feeder.getSilenceChunks());
        assertTrue(feeder.getMaxLeadNanos() <= LOOK_AHEAD_NANOS);
        assertEquals("three chunks went out together at the start", first, connection.timeOf(2));
        assertTrue(connection.timeOf(3) > first);
    }

    @Test
    public void aStalledProducerDoesNotMakeTheFeederBurstWhenItComesBack() throws Exception {
        PcmFeeder feeder = open(fastConfig().silenceFillChunks(5), null);
        FakeConnection connection = factory.connection;
        assertTrue(feeder.write(pattern(4), 0, 4 * CHUNK));
        runUntilWritten(connection, 4);

        // the producer says nothing: five chunks of silence, then the writer waits without writing
        long end = System.nanoTime() + 5_000_000_000L;
        while (!feeder.isDormant()) {
            assertTrue("never went dormant", System.nanoTime() < end);
            if (clock.awaitSleepers(1, 20)) {
                clock.advanceToNextWake();
            }
        }
        assertEquals(5, feeder.getSilenceChunks());
        assertEquals(9, connection.count());

        clock.advanceMillis(5_000); // five seconds of nothing, 250 chunks "owed"
        assertEquals("nothing is written while dormant", 9, connection.count());
        assertTrue(feeder.write(pattern(8), 0, 8 * CHUNK));
        runUntilWritten(connection, 17);

        long resume = connection.timeOf(9);
        int together = 0;
        for (int k = 9; k < 17; k++) {
            if (connection.timeOf(k) == resume) {
                together++;
            }
            assertTrue("chunk " + k, connection.timeOf(k) - resume >= Math.max(0, (k - 9 - 2)) * CHUNK_NANOS);
        }
        assertEquals("only the look-ahead goes out at once, not 250 chunks", 3, together);
        assertEquals(0, feeder.getLateChunks());
        assertTrue(feeder.getMaxLeadNanos() <= LOOK_AHEAD_NANOS);
    }

    @Test
    public void aWriteThatTakesLongReanchorsInsteadOfCatchingUpWithABurst() throws Exception {
        PcmFeeder feeder = open();
        FakeConnection connection = factory.connection;
        // the third write takes 300 ms (the clock jumps while it is in progress)
        connection.onWrite = index -> {
            if (index == 2) {
                clock.advanceMillis(300);
            }
        };

        assertTrue(feeder.write(pattern(8), 0, 8 * CHUNK));
        runUntilWritten(connection, 8);

        assertEquals(1, feeder.getReanchors());
        assertEquals(1, feeder.getLateChunks());
        long afterStall = connection.timeOf(3);
        int together = 0;
        for (int k = 3; k < 8; k++) {
            if (connection.timeOf(k) == afterStall) {
                together++;
            }
        }
        assertTrue("no burst after the stall: " + together, together <= 2);
        for (int k = 3; k < 8; k++) {
            assertTrue(connection.timeOf(k) - afterStall >= Math.max(0, k - 3 - 1) * CHUNK_NANOS);
        }
    }

    @Test
    public void silenceFillIsBoundedAndThenTheWriterWaits() throws Exception {
        PcmFeeder feeder = open(fastConfig().silenceFillChunks(3), null);
        FakeConnection connection = factory.connection;
        assertTrue(feeder.write(pattern(1), 0, CHUNK));
        runUntilWritten(connection, 1);

        long end = System.nanoTime() + 5_000_000_000L;
        while (!feeder.isDormant()) {
            assertTrue("never went dormant", System.nanoTime() < end);
            if (clock.awaitSleepers(1, 20)) {
                clock.advanceToNextWake();
            }
        }
        clock.advanceMillis(60_000);
        Thread.sleep(20);

        assertEquals(4, connection.count());
        assertEquals(3, feeder.getSilenceChunks());
        byte[] silence = new byte[CHUNK];
        for (int k = 1; k < 4; k++) {
            assertArrayEquals("chunk " + k + " is silent", silence, connection.chunk(k));
        }
    }

    @Test
    public void nothingIsWrittenBeforeTheFirstAudio() throws Exception {
        PcmFeeder feeder = open();
        FakeConnection connection = factory.connection;

        assertTrue(awaitTrue(feeder::isDormant, 2_000));
        clock.advanceMillis(10_000);
        Thread.sleep(20);

        assertEquals(0, connection.count());
        assertEquals(0, feeder.getSilenceChunks());
    }

    @Test
    public void discardDropsWhatIsQueued() throws Exception {
        PcmFeeder feeder = open();
        FakeConnection connection = factory.connection;
        assertTrue(feeder.write(pattern(8), 0, 8 * CHUNK));
        assertTrue(connection.awaitCount(3, 2_000));
        assertTrue(clock.awaitSleepers(1, 2_000));

        feeder.discard();

        assertEquals(0, feeder.getQueuedBytes());
        // what the writer sends from now on is silence, none of the queued pattern
        for (int i = 0; i < 5 && clock.awaitSleepers(1, 100); i++) {
            clock.advanceToNextWake();
        }
        byte[] silence = new byte[CHUNK];
        for (int k = 3; k < connection.count(); k++) {
            assertArrayEquals(silence, connection.chunk(k));
        }
    }

    // ---- back pressure

    @Test
    public void aFullQueueMakesWriteReportFailureAfterTheBoundedWaitAndTheQueueDoesNotGrow() throws Exception {
        PcmFeeder.Config config = fastConfig().queueChunks(2).lookAheadChunks(1).writeWaitMillis(300);
        PcmFeeder feeder = open(config, null);
        FakeConnection connection = factory.connection;
        assertEquals(2 * CHUNK, feeder.getQueueCapacity());

        boolean accepted = feeder.write(pattern(10), 0, 10 * CHUNK);

        assertFalse("seven chunks did not fit", accepted);
        assertTrue(connection.awaitCount(1, 2_000));
        assertEquals(2 * CHUNK, feeder.getQueuedBytes());
        assertEquals(7L * CHUNK, feeder.getDroppedBytes());
        assertEquals(PcmFeeder.State.RUNNING, feeder.getState());
    }

    @Test
    public void aFullQueueBlocksTheProducerUntilTheWriterMakesRoom() throws Exception {
        PcmFeeder.Config config = fastConfig().queueChunks(2).lookAheadChunks(1).writeWaitMillis(5_000);
        PcmFeeder feeder = open(config, null);
        FakeConnection connection = factory.connection;
        final byte[] data = pattern(4);
        final AtomicReference<Boolean> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            result.set(feeder.write(data, 0, data.length));
            done.countDown();
        });
        producer.start();

        assertTrue("the fourth chunk waits for room", connection.awaitCount(1, 2_000));
        assertFalse(done.await(100, TimeUnit.MILLISECONDS));
        // move the clock only when the producer has refilled the queue (or is done), as a real-time producer would
        long end = System.nanoTime() + 5_000_000_000L;
        while (connection.count() < 4) {
            assertTrue("stuck at " + connection.count() + " chunks", System.nanoTime() < end);
            assertTrue(awaitTrue(() -> done.getCount() == 0 || feeder.getQueuedBytes() == feeder.getQueueCapacity(), 2_000));
            if (clock.awaitSleepers(1, 100) && connection.count() < 4) {
                clock.advanceToNextWake();
            }
        }

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, result.get());
        assertArrayEquals(data, Arrays.copyOf(connection.all(), 4 * CHUNK));
        assertEquals(0, feeder.getDroppedBytes());
    }

    @Test
    public void aWriteOutsideTheArrayIsRefused() throws Exception {
        PcmFeeder feeder = open();
        try {
            feeder.write(new byte[10], 5, 10);
            fail("range outside the array");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        assertTrue(feeder.write(new byte[10], 5, 0));
    }

    // ---- failures

    @Test
    public void aPeerThatDoesNotReadIsDetectedByTheWatchdogAndTheConnectionIsClosed() throws Exception {
        RecordingListener listener = new RecordingListener();
        PcmFeeder feeder = open(fastConfig(), listener);
        FakeConnection connection = factory.connection;
        connection.blockTheNextWrite();
        assertTrue(feeder.write(pattern(2), 0, 2 * CHUNK));
        assertTrue("the writer is stuck in a write", awaitTrue(connection::isBlocked, 2_000));

        clock.advanceMillis(10_500);

        assertTrue(listener.called.await(2, TimeUnit.SECONDS));
        assertEquals(PcmFeeder.Failure.STALLED, listener.failure.get());
        assertEquals(PcmFeeder.State.FAILED, feeder.getState());
        assertEquals(PcmFeeder.Failure.STALLED, feeder.getFailure());
        assertTrue(connection.isClosed());
        assertTrue(awaitTrue(() -> feedThreads() == threadsBefore, 2_000));
        assertFalse("a failed feeder takes no more audio", feeder.write(pattern(1), 0, CHUNK));
        assertEquals(1, listener.calls.get());
    }

    @Test
    public void aSlowWriteBelowTheLimitIsNotAStall() throws Exception {
        RecordingListener listener = new RecordingListener();
        PcmFeeder feeder = open(fastConfig(), listener);
        FakeConnection connection = factory.connection;
        connection.blockTheNextWrite();
        assertTrue(feeder.write(pattern(2), 0, 2 * CHUNK));
        assertTrue(awaitTrue(connection::isBlocked, 2_000));

        clock.advanceMillis(9_000);
        Thread.sleep(60); // twelve looks of the watchdog
        connection.release();

        assertEquals(PcmFeeder.State.RUNNING, feeder.getState());
        assertEquals(0, listener.calls.get());
    }

    @Test
    public void aFailingWriteEndsTheFeederAndTheListenerHearsOfItOnce() throws Exception {
        RecordingListener listener = new RecordingListener();
        PcmFeeder feeder = open(fastConfig(), listener);
        FakeConnection connection = factory.connection;
        connection.failNext = true;

        assertTrue(feeder.write(pattern(2), 0, 2 * CHUNK));

        assertTrue(listener.called.await(2, TimeUnit.SECONDS));
        assertEquals(PcmFeeder.Failure.WRITE, listener.failure.get());
        assertEquals(PcmFeeder.State.FAILED, feeder.getState());
        assertTrue(connection.isClosed());
        assertTrue(awaitTrue(() -> feedThreads() == threadsBefore, 2_000));
        feeder.close();
        assertEquals("closing a failed feeder is silent", 1, listener.calls.get());
        assertEquals(PcmFeeder.State.CLOSED, feeder.getState());
    }

    // ---- closing

    @Test
    public void closeStopsBothThreadsAndTheConnectionAndIsIdempotent() throws Exception {
        RecordingListener listener = new RecordingListener();
        PcmFeeder feeder = open(fastConfig(), listener);
        FakeConnection connection = factory.connection;
        assertEquals(threadsBefore + 2, feedThreads());
        assertTrue(feeder.write(pattern(2), 0, 2 * CHUNK));

        feeder.close();
        feeder.close();

        assertEquals(PcmFeeder.State.CLOSED, feeder.getState());
        assertTrue(connection.isClosed());
        assertEquals(threadsBefore, feedThreads());
        assertFalse(feeder.write(pattern(1), 0, CHUNK));
        assertEquals("a requested close is no failure", 0, listener.calls.get());
        assertNull(feeder.getFailure());
    }

    @Test
    public void closeWakesAProducerThatWaitsForRoom() throws Exception {
        PcmFeeder.Config config = fastConfig().queueChunks(1).lookAheadChunks(1).writeWaitMillis(10_000);
        PcmFeeder feeder = open(config, null);
        final AtomicReference<Boolean> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            result.set(feeder.write(pattern(6), 0, 6 * CHUNK));
            done.countDown();
        });
        producer.start();
        assertTrue(factory.connection.awaitCount(1, 2_000));
        assertFalse(done.await(100, TimeUnit.MILLISECONDS));

        feeder.close();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, result.get());
        assertEquals(threadsBefore, feedThreads());
    }

    @Test
    public void closeFromTheListenerDoesNotDeadlock() throws Exception {
        final AtomicReference<PcmFeeder> holder = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);
        PcmFeeder feeder = open(fastConfig(), failure -> {
            holder.get().close();
            closed.countDown();
        });
        holder.set(feeder);
        factory.connection.failNext = true;

        assertTrue(feeder.write(pattern(1), 0, CHUNK));

        assertTrue(closed.await(3, TimeUnit.SECONDS));
        assertEquals(PcmFeeder.State.CLOSED, feeder.getState());
        assertTrue(awaitTrue(() -> feedThreads() == threadsBefore, 2_000));
    }

    @Test
    public void theConfigRefusesValuesOutsideTheirRanges() {
        try {
            new PcmFeeder.Config().queueChunks(9);
            fail("more than 8 chunks must be refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            new PcmFeeder.Config().queueChunks(0);
            fail("an empty queue must be refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            new PcmFeeder.Config().connectTimeoutMillis(0);
            fail("no timeout must be refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // ---- a real socket

    @Test
    public void overARealLoopbackSocketTheBytesArriveAndTheSocketOptionsAreSet() throws Exception {
        InetAddress loopback = address(127, 0, 0, 1);
        final byte[] data = pattern(10);
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            final ByteArrayOutputStream received = new ByteArrayOutputStream();
            final CountDownLatch finished = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        synchronized (received) {
                            received.write(buffer, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // the feeder closed
                } finally {
                    finished.countDown();
                }
            }, "loopback-reader");
            reader.setDaemon(true);
            reader.start();
            final AtomicReference<FeedConnection> opened = new AtomicReference<>();
            FeedConnection.Factory capturing = (address, port, timeout, sendBuffer) -> {
                FeedConnection connection = SocketFeedConnection.FACTORY.open(address, port, timeout, sendBuffer);
                opened.set(connection);
                return connection;
            };

            PcmFeeder feeder = PcmFeeder.connect(loopback, server.getLocalPort(), FORMAT, null, capturing,
                    FeedClock.SYSTEM, null);
            feeders.add(feeder);
            Socket socket = ((SocketFeedConnection) opened.get()).socketForTest();
            assertTrue("TCP_NODELAY", socket.getTcpNoDelay());
            assertTrue("a small send buffer, not the default of megabytes: " + socket.getSendBufferSize(),
                    socket.getSendBufferSize() <= 128 * 1024);
            assertTrue(feeder.write(data, 0, data.length));
            assertTrue(awaitTrue(() -> {
                synchronized (received) {
                    return received.size() >= data.length;
                }
            }, 5_000));
            feeder.close();
            assertTrue(finished.await(5, TimeUnit.SECONDS));

            byte[] all;
            synchronized (received) {
                all = received.toByteArray();
            }
            assertArrayEquals(data, Arrays.copyOf(all, data.length));
            assertEquals("whole chunks only", 0, all.length % CHUNK);
        }
    }

    @Test
    public void connectingToAPortNobodyListensOnFailsFastWithoutTheAddress() throws Exception {
        InetAddress loopback = address(127, 0, 0, 1);
        int freePort;
        try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
            freePort = probe.getLocalPort();
        }

        try {
            PcmFeeder.connect(loopback, freePort, FORMAT, null, SocketFeedConnection.FACTORY, FeedClock.SYSTEM, null);
            fail("nobody listens");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("127.0.0.1"));
        }
        assertEquals(threadsBefore, feedThreads());
    }
}
