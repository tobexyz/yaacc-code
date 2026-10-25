/*
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
package de.yaacc.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link PositionalSource}: positional reads, EOF and the non-blocking close that runs
 * the underlying close exactly once and never while a read is running.
 */
public class PositionalSourceTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        pool.shutdownNow();
    }

    /** Byte-array backed source that counts closes and can hold a read open. */
    static class ArraySource extends PositionalSource {
        final byte[] data;
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger activeReads = new AtomicInteger();
        /** Set if close ran while a read was inside readAtInternal. */
        volatile boolean closedDuringRead;
        volatile CountDownLatch readEntered;
        volatile CountDownLatch readMayFinish;
        volatile IOException failWith;

        ArraySource(byte[] data) {
            super(data.length);
            this.data = data;
        }

        @Override
        protected int readAtInternal(long position, byte[] buf, int off, int len) throws IOException {
            activeReads.incrementAndGet();
            try {
                CountDownLatch entered = readEntered;
                if (entered != null) {
                    entered.countDown();
                }
                CountDownLatch mayFinish = readMayFinish;
                if (mayFinish != null) {
                    try {
                        if (!mayFinish.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("test timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                if (failWith != null) {
                    throw failWith;
                }
                if (position >= data.length) {
                    return -1;
                }
                int n = (int) Math.min(len, data.length - position);
                System.arraycopy(data, (int) position, buf, off, n);
                return n;
            } finally {
                activeReads.decrementAndGet();
            }
        }

        @Override
        protected void closeInternal() {
            if (activeReads.get() > 0) {
                closedDuringRead = true;
            }
            closes.incrementAndGet();
        }
    }

    private static byte[] sequence(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    // ---- reads

    @Test
    public void sizeIsTheConstructorValue() {
        assertEquals(10L, new ArraySource(sequence(10)).size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeSizeIsRejected() {
        new PositionalSource(-1L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                return -1;
            }

            @Override
            protected void closeInternal() {
            }
        };
    }

    @Test
    public void readsAtAnOffsetIntoTheBufferOffset() throws IOException {
        ArraySource source = new ArraySource(sequence(100));
        byte[] buf = new byte[20];
        int n = source.readAt(40, buf, 5, 10);
        assertEquals(10, n);
        byte[] expected = new byte[20];
        System.arraycopy(sequence(100), 40, expected, 5, 10);
        assertArrayEquals(expected, buf);
    }

    @Test
    public void readsDoNotMoveAnyPosition() throws IOException {
        ArraySource source = new ArraySource(sequence(100));
        byte[] a = new byte[4];
        byte[] b = new byte[4];
        source.readAt(50, a, 0, 4);
        source.readAt(0, b, 0, 4);
        byte[] again = new byte[4];
        source.readAt(50, again, 0, 4);
        assertArrayEquals(a, again);
        assertEquals(0, b[0]);
    }

    @Test
    public void aReadIsClippedAtTheEnd() throws IOException {
        ArraySource source = new ArraySource(sequence(10));
        byte[] buf = new byte[64];
        assertEquals(3, source.readAt(7, buf, 0, 64));
    }

    @Test
    public void aReadNeverAsksTheBackendForBytesBeyondTheSize() throws IOException {
        final int[] asked = new int[1];
        PositionalSource source = new PositionalSource(5L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                asked[0] = len;
                return len;
            }

            @Override
            protected void closeInternal() {
            }
        };
        source.readAt(3, new byte[64], 0, 64);
        assertEquals(2, asked[0]);
    }

    @Test
    public void readAtOrBeyondTheSizeIsEndOfFile() throws IOException {
        ArraySource source = new ArraySource(sequence(10));
        byte[] buf = new byte[4];
        assertEquals(-1, source.readAt(10, buf, 0, 4));
        assertEquals(-1, source.readAt(1_000_000L, buf, 0, 4));
        assertEquals(-1, source.readAt(Long.MAX_VALUE, buf, 0, 4));
    }

    @Test
    public void anEmptySourceIsAlwaysAtEndOfFile() throws IOException {
        ArraySource source = new ArraySource(new byte[0]);
        assertEquals(-1, source.readAt(0, new byte[4], 0, 4));
    }

    @Test
    public void aZeroLengthReadReturnsZero() throws IOException {
        ArraySource source = new ArraySource(sequence(10));
        assertEquals(0, source.readAt(3, new byte[4], 0, 0));
    }

    @Test
    public void aBackendThatReturnsZeroForANonEmptyReadCountsAsEndOfFile() throws IOException {
        PositionalSource source = new PositionalSource(10L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                return 0;
            }

            @Override
            protected void closeInternal() {
            }
        };
        assertEquals(-1, source.readAt(0, new byte[4], 0, 4));
    }

    @Test
    public void invalidArgumentsAreRejected() throws IOException {
        ArraySource source = new ArraySource(sequence(10));
        assertRejected(source, -1L, new byte[4], 0, 4);
        assertRejected(source, 0L, null, 0, 4);
        assertRejected(source, 0L, new byte[4], -1, 2);
        assertRejected(source, 0L, new byte[4], 0, -1);
        assertRejected(source, 0L, new byte[4], 3, 2);
        assertRejected(source, 0L, new byte[4], Integer.MAX_VALUE, 2);
    }

    private static void assertRejected(PositionalSource source, long pos, byte[] buf, int off, int len)
            throws IOException {
        try {
            source.readAt(pos, buf, off, len);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void aFailingBackendReadSurfacesAsIOException() {
        ArraySource source = new ArraySource(sequence(10));
        source.failWith = new IOException("boom");
        try {
            source.readAt(0, new byte[4], 0, 4);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }

    // ---- close

    @Test
    public void closeWhileIdleClosesAtOnce() {
        ArraySource source = new ArraySource(sequence(10));
        assertFalse(source.isClosing());
        source.close();
        assertTrue(source.isClosing());
        assertEquals(1, source.closes.get());
    }

    @Test
    public void closeIsIdempotent() {
        ArraySource source = new ArraySource(sequence(10));
        source.close();
        source.close();
        source.close();
        assertEquals(1, source.closes.get());
    }

    @Test
    public void aReadAfterCloseFailsWithIOException() {
        ArraySource source = new ArraySource(sequence(10));
        source.close();
        try {
            source.readAt(0, new byte[4], 0, 4);
            fail("expected IOException");
        } catch (IOException expected) {
            // fine
        }
        assertEquals("the backend must not be read after close", 0, source.activeReads.get());
    }

    @Test
    public void aZeroLengthReadAfterCloseAlsoFails() {
        ArraySource source = new ArraySource(sequence(10));
        source.close();
        try {
            source.readAt(0, new byte[4], 0, 0);
            fail("expected IOException");
        } catch (IOException expected) {
            // fine
        }
    }

    @Test
    public void closeAsAutoCloseableWorks() throws Exception {
        ArraySource source = new ArraySource(sequence(10));
        try (ArraySource ignored = source) {
            assertEquals(4, ignored.readAt(0, new byte[4], 0, 4));
        }
        assertEquals(1, source.closes.get());
    }

    @Test
    public void closeWhileAReadRunsReturnsAtOnceAndDefersTheRealCloseToTheReader() throws Exception {
        ArraySource source = new ArraySource(sequence(100));
        source.readEntered = new CountDownLatch(1);
        source.readMayFinish = new CountDownLatch(1);
        byte[] buf = new byte[10];
        Future<Integer> read = pool.submit(() -> source.readAt(20, buf, 0, 10));
        assertTrue(source.readEntered.await(5, TimeUnit.SECONDS));

        // close() must not wait for the reader
        Future<?> close = pool.submit(source::close);
        close.get(2, TimeUnit.SECONDS);
        assertTrue(source.isClosing());
        assertEquals("not closed while a read runs", 0, source.closes.get());

        // a new read is refused although the old one is still running
        try {
            source.readAt(0, new byte[4], 0, 4);
            fail("expected IOException");
        } catch (IOException expected) {
            // fine
        }

        source.readMayFinish.countDown();
        assertEquals(10, (int) read.get(5, TimeUnit.SECONDS));
        // the read that was in flight delivered real data
        byte[] expected = new byte[10];
        System.arraycopy(sequence(100), 20, expected, 0, 10);
        assertArrayEquals(expected, buf);
        assertEquals("closed by the last reader", 1, source.closes.get());
        assertFalse(source.closedDuringRead);
    }

    @Test
    public void theLastOfSeveralReadersCloses() throws Exception {
        ArraySource source = new ArraySource(sequence(100));
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);
        PositionalSource shared = new PositionalSource(100L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) throws IOException {
                entered.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            }

            @Override
            protected void closeInternal() {
                source.closes.incrementAndGet();
            }
        };
        List<Future<Integer>> reads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reads.add(pool.submit(() -> shared.readAt(0, new byte[1], 0, 1)));
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        shared.close();
        assertEquals(0, source.closes.get());
        go.countDown();
        for (Future<Integer> read : reads) {
            read.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, source.closes.get());
    }

    @Test
    public void aReadThatFailsAfterCloseStillLeadsToTheRealClose() throws Exception {
        ArraySource source = new ArraySource(sequence(100));
        source.readEntered = new CountDownLatch(1);
        source.readMayFinish = new CountDownLatch(1);
        source.failWith = new IOException("boom");
        Future<Integer> read = pool.submit(() -> source.readAt(0, new byte[4], 0, 4));
        assertTrue(source.readEntered.await(5, TimeUnit.SECONDS));
        source.close();
        assertEquals(0, source.closes.get());
        source.readMayFinish.countDown();
        try {
            read.get(5, TimeUnit.SECONDS);
            fail("expected the read to fail");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
        assertEquals(1, source.closes.get());
    }

    @Test
    public void aBackendThatThrowsAnUncheckedExceptionOnCloseDoesNotBreakTheCaller() {
        PositionalSource source = new PositionalSource(1L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                return -1;
            }

            @Override
            protected void closeInternal() {
                throw new IllegalStateException("close failed");
            }
        };
        source.close();
        assertTrue(source.isClosing());
        source.close();
    }

    @Test
    public void manyReadersAndOneCloseNeverReadAfterTheBackendClosed() throws Exception {
        final int readers = 8;
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger violations = new AtomicInteger();
        final AtomicInteger inBackend = new AtomicInteger();
        final byte[] data = sequence(4096);
        PositionalSource source = new PositionalSource(data.length) {
            private volatile boolean backendClosed;

            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                inBackend.incrementAndGet();
                try {
                    if (backendClosed) {
                        violations.incrementAndGet();
                    }
                    int n = (int) Math.min(len, data.length - position);
                    System.arraycopy(data, (int) position, buf, off, n);
                    Thread.yield();
                    if (backendClosed) {
                        violations.incrementAndGet();
                    }
                    return n;
                } finally {
                    inBackend.decrementAndGet();
                }
            }

            @Override
            protected void closeInternal() {
                if (inBackend.get() != 0) {
                    violations.incrementAndGet();
                }
                backendClosed = true;
                closes.incrementAndGet();
            }
        };
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                start.await();
                byte[] buf = new byte[64];
                for (int i = 0; i < 20_000; i++) {
                    try {
                        source.readAt(i % 4000, buf, 0, 64);
                    } catch (IOException closed) {
                        return null;
                    }
                }
                return null;
            }));
        }
        start.countDown();
        Thread.sleep(5);
        source.close();
        source.close();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, closes.get());
        assertEquals(0, violations.get());
    }
}
