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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link WatchedCopy}: the copy fallback cannot be pinned by a source whose read never returns.
 * At the deadline the waiting caller closes the source from another thread, deletes the partial
 * file and gives the copy slot back; a worker that stays stuck is abandoned, and when it wakes up
 * later it leaves no file and no descriptor behind. Plain JVM, pure-Java blocking sources.
 */
public class WatchedCopyTest {

    private static final long TIMEOUT = 300L;
    private static final long FAR = 1L << 40;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File dir;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger held = new AtomicInteger();
    private final AtomicInteger heldClosed = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        dir = temp.newFolder("incoming");
    }

    @After
    public void releaseStuckWorkers() {
        release.countDown();
    }

    private final WatchedCopy.FileCreator creator = () -> File.createTempFile("share-", ".bin", dir);

    private final WatchedCopy.Holder holder = file -> {
        held.incrementAndGet();
        return new PositionalSource(file.length()) {
            // opened on the first read, so that holding a copy whose file is gone does not fail
            private RandomAccessFile raf;

            @Override
            protected synchronized int readAtInternal(long position, byte[] buf, int off, int len)
                    throws IOException {
                if (raf == null) {
                    raf = new RandomAccessFile(file, "r");
                }
                raf.seek(position);
                return raf.read(buf, off, len);
            }

            @Override
            protected synchronized void closeInternal() {
                heldClosed.incrementAndGet();
                try {
                    if (raf != null) {
                        raf.close();
                    }
                } catch (IOException ignored) {
                    // test double
                }
            }
        };
    };

    /** A normal source. */
    private static final class BytesInput implements WatchedCopy.Input {
        private final byte[] data;
        final AtomicBoolean closed = new AtomicBoolean();

        BytesInput(byte[] data) {
            this.data = data;
        }

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public long declaredLength() {
            return data.length;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /**
     * A pipe that never produces a byte. If {@code closeWakesRead}, closing the input makes the
     * blocked read fail (what libcore does for a closed descriptor); if not, only {@code release}
     * wakes it, and it then delivers {@code lateBytes} and the end of the stream.
     */
    private final class StuckInput implements WatchedCopy.Input {
        private final boolean closeWakesRead;
        private final byte[] lateBytes;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final CountDownLatch readStarted = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();

        StuckInput(boolean closeWakesRead, byte[] lateBytes) {
            this.closeWakesRead = closeWakesRead;
            this.lateBytes = lateBytes;
        }

        @Override
        public InputStream open() {
            return new InputStream() {
                private boolean delivered;

                @Override
                public int read() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    readStarted.countDown();
                    CountDownLatch waitOn = closeWakesRead ? closedLatch : release;
                    while (true) {
                        try {
                            waitOn.await();
                            break;
                        } catch (InterruptedException ignored) {
                            // not interruptible, like a blocked read(2)
                        }
                    }
                    if (closeWakesRead) {
                        throw new IOException("closed");
                    }
                    if (delivered) {
                        return -1;
                    }
                    delivered = true;
                    System.arraycopy(lateBytes, 0, b, off, lateBytes.length);
                    return lateBytes.length;
                }
            };
        }

        @Override
        public long declaredLength() {
            return -1L;
        }

        @Override
        public void close() {
            closed.set(true);
            closedLatch.countDown();
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(5, TimeUnit.SECONDS);
        }
    }

    private PositionalSource copy(WatchedCopy copier, WatchedCopy.Input input) throws WatchedCopy.Failure {
        return copier.copy(input, creator, () -> FAR, () -> FAR, holder);
    }

    private WatchedCopy.Reason failureOf(WatchedCopy copier, WatchedCopy.Input input) {
        try {
            copy(copier, input).close();
            fail("expected a failure");
            return null;
        } catch (WatchedCopy.Failure e) {
            return e.getReason();
        }
    }

    private int filesInDir() {
        String[] names = dir.list();
        return names == null ? 0 : names.length;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    // ---- normal operation

    @Test
    public void aNormalCopyIsHeldAndItsFileIsDeletedWhenTheSourceIsClosed() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        byte[] data = {1, 2, 3, 4, 5};
        BytesInput input = new BytesInput(data);

        PositionalSource source = copy(copier, input);

        assertEquals(5L, source.size());
        byte[] back = new byte[5];
        assertEquals(5, source.readAt(0, back, 0, 5));
        assertArrayEquals(data, back);
        assertEquals(1, filesInDir());
        assertTrue("the input is closed", input.closed.get());
        source.close();
        assertEquals("the copy is deleted with the source", 0, filesInDir());
    }

    @Test
    public void theSlotIsFreeAfterACopyAndANewCopyProceeds() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);

        copy(copier, new BytesInput(new byte[]{1})).close();
        copy(copier, new BytesInput(new byte[]{2})).close();

        assertEquals(2, held.get());
    }

    @Test
    public void onlyOneCopyRunsAtATime() throws Exception {
        WatchedCopy copier = new WatchedCopy(5_000L, 2);
        StuckInput slow = new StuckInput(false, new byte[]{9});
        Thread first = new Thread(() -> {
            try {
                copier.copy(slow, creator, () -> FAR, () -> FAR, holder).close();
            } catch (WatchedCopy.Failure ignored) {
                // not expected
            }
        });
        first.start();
        assertTrue(slow.awaitReadStarted());
        BytesInput second = new BytesInput(new byte[]{1});

        assertEquals(WatchedCopy.Reason.BUSY, failureOf(copier, second));

        assertTrue("the refused input is closed", second.closed.get());
        release.countDown();
        first.join(5_000L);
    }

    @Test
    public void aDeclaredSizeAboveTheBudgetIsRefusedAndNothingStays() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        BytesInput input = new BytesInput(new byte[100]);

        try {
            copier.copy(input, creator, () -> 10L, () -> FAR, holder);
            fail();
        } catch (WatchedCopy.Failure e) {
            assertEquals(WatchedCopy.Reason.TOO_BIG, e.getReason());
        }

        assertEquals(0, filesInDir());
        assertTrue(input.closed.get());
        assertEquals(0, held.get());
    }

    @Test
    public void noBudgetLeftIsNoSpace() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        BytesInput input = new BytesInput(new byte[1]);

        try {
            copier.copy(input, creator, () -> 0L, () -> FAR, holder);
            fail();
        } catch (WatchedCopy.Failure e) {
            assertEquals(WatchedCopy.Reason.NO_SPACE, e.getReason());
        }

        assertEquals(0, filesInDir());
        assertTrue(input.closed.get());
    }

    @Test
    public void aReadErrorDeletesThePartialCopy() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        WatchedCopy.Input failing = new WatchedCopy.Input() {
            @Override
            public InputStream open() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("provider died");
                    }
                };
            }

            @Override
            public long declaredLength() {
                return -1L;
            }

            @Override
            public void close() {
            }
        };

        assertEquals(WatchedCopy.Reason.UNUSABLE, failureOf(copier, failing));
        assertEquals(0, filesInDir());
        // and the slot is free
        copy(copier, new BytesInput(new byte[]{1})).close();
    }

    @Test
    public void anEmptyStreamIsNotACopy() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);

        assertEquals(WatchedCopy.Reason.UNUSABLE, failureOf(copier, new BytesInput(new byte[0])));
        assertEquals(0, filesInDir());
    }

    // ---- the watchdog

    @Test
    public void aSourceThatNeverDeliversIsClosedAtTheDeadlineAndTheCallerIsFree() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        StuckInput stuck = new StuckInput(true, new byte[0]);
        long begin = System.nanoTime();

        assertEquals(WatchedCopy.Reason.TIMED_OUT, failureOf(copier, stuck));
        long tookMillis = (System.nanoTime() - begin) / 1_000_000L;

        assertTrue("took " + tookMillis, tookMillis < 4_000L);
        assertTrue("the source was closed from the caller's side", stuck.closed.get());
        assertEquals("the partial file is deleted", 0, filesInDir());
        assertEquals("nothing was held", 0, held.get());
    }

    @Test
    public void afterTheTimeoutTheSlotIsFreeAndTheNextCopyProceeds() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        assertEquals(WatchedCopy.Reason.TIMED_OUT, failureOf(copier, new StuckInput(true, new byte[0])));

        byte[] data = {7, 8, 9};
        PositionalSource next = copy(copier, new BytesInput(data));

        assertEquals(3L, next.size());
        next.close();
        assertEquals(0, filesInDir());
    }

    @Test
    public void aWorkerThatIgnoresTheCloseIsAbandonedAndTheNextCopyStillProceeds() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        StuckInput stuck = new StuckInput(false, new byte[]{1, 2, 3});

        assertEquals(WatchedCopy.Reason.TIMED_OUT, failureOf(copier, stuck));
        assertEquals("the stuck worker is still alive", 1, copier.stuckWorkers());

        PositionalSource next = copy(copier, new BytesInput(new byte[]{4, 5}));
        assertEquals(2L, next.size());
        next.close();
        assertEquals(0, filesInDir());
    }

    @Test
    public void aLateWorkerThatFinishesIsANoOp() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        StuckInput stuck = new StuckInput(false, new byte[]{1, 2, 3});
        assertEquals(WatchedCopy.Reason.TIMED_OUT, failureOf(copier, stuck));
        assertEquals(0, filesInDir());

        // the read finally returns data and then the end of the stream
        release.countDown();
        waitUntil(() -> copier.stuckWorkers() == 0);

        assertEquals("no file was left or recreated", 0, filesInDir());
        assertEquals("nothing was registered or held", held.get(), heldClosed.get());
        // and the slot is intact
        copy(copier, new BytesInput(new byte[]{1})).close();
    }

    @Test
    public void aLateWorkerThatAlreadyHeldTheCopyHasItClosedAndDeleted() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 2);
        CountDownLatch holding = new CountDownLatch(1);
        WatchedCopy.Holder slowHolder = file -> {
            holding.countDown();
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
            return holder.hold(file);
        };

        try {
            copier.copy(new BytesInput(new byte[]{1, 2, 3}), creator, () -> FAR, () -> FAR, slowHolder);
            fail();
        } catch (WatchedCopy.Failure e) {
            assertEquals(WatchedCopy.Reason.TIMED_OUT, e.getReason());
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        assertEquals("the caller already deleted the file", 0, filesInDir());

        release.countDown();
        waitUntil(() -> copier.stuckWorkers() == 0);

        assertEquals("a descriptor opened late is closed again", held.get(), heldClosed.get());
        assertEquals(0, filesInDir());
    }

    @Test
    public void tooManyStuckWorkersTurnTheNextCopyAwayAsBusy() throws Exception {
        WatchedCopy copier = new WatchedCopy(TIMEOUT, 1);
        assertEquals(WatchedCopy.Reason.TIMED_OUT, failureOf(copier, new StuckInput(false, new byte[]{1})));
        BytesInput next = new BytesInput(new byte[]{1});

        assertEquals(WatchedCopy.Reason.BUSY, failureOf(copier, next));

        assertTrue("the input of a refused copy is closed", next.closed.get());
        assertEquals(0, filesInDir());
        assertFalse(copier.stuckWorkers() == 0);

        // once the stuck worker is gone it works again
        release.countDown();
        waitUntil(() -> copier.stuckWorkers() == 0);
        assertNotNull(copy(copier, new BytesInput(new byte[]{1})));
    }
}
