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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * {@link AbandonableCall}: a call on its own daemon thread that the caller may give up on. The
 * caller gets control back at the deadline and can close what the worker blocks on; a worker that
 * stays stuck is abandoned (counted, bounded), and whatever it produces later is disposed of.
 */
public class AbandonableCallTest {

    private static final long LONG = 5_000L;
    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void releaseStuckWorkers() {
        release.countDown();
    }

    private void blockUninterruptibly() {
        while (true) {
            try {
                release.await();
                return;
            } catch (InterruptedException ignored) {
                // keep waiting
            }
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    public void aFastCallReturnsItsResult() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");

        assertEquals("answer", calls.call(abandoned -> "answer", LONG, null, null));
        assertEquals(0, calls.abandonedAliveCount());
    }

    @Test
    public void theWorkersFailureIsRethrown() {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");

        try {
            calls.call(abandoned -> {
                throw new IOException("disk full");
            }, LONG, null, null);
            fail();
        } catch (IOException expected) {
            assertEquals("disk full", expected.getMessage());
        } catch (Exception other) {
            fail("wrong exception " + other);
        }
    }

    @Test
    public void theCallerGetsControlBackAtTheDeadlineAndTheHookRunsOnTheCallersThread() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");
        AtomicReference<Thread> hookThread = new AtomicReference<>();
        long begin = System.nanoTime();

        try {
            calls.call(abandoned -> {
                blockUninterruptibly();
                return "late";
            }, 100L, () -> hookThread.set(Thread.currentThread()), null);
            fail();
        } catch (TimeoutException expected) {
            // the caller is free
        }
        long tookMillis = (System.nanoTime() - begin) / 1_000_000L;

        assertTrue("took " + tookMillis, tookMillis < 3_000L);
        assertEquals(Thread.currentThread(), hookThread.get());
        assertEquals(1, calls.abandonedAliveCount());
    }

    @Test
    public void closingWhatTheWorkerBlocksOnFromTheHookEndsTheWorker() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean workerEnded = new AtomicBoolean();

        try {
            calls.call(abandoned -> {
                try {
                    closed.await();
                } finally {
                    workerEnded.set(true);
                }
                return "x";
            }, 100L, closed::countDown, null);
            fail();
        } catch (TimeoutException expected) {
            // the hook closed the source
        }

        waitUntil(workerEnded::get);
        waitUntil(() -> calls.abandonedAliveCount() == 0);
    }

    @Test
    public void theWorkerSeesThatItWasAbandoned() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");
        AtomicBoolean sawAbandoned = new AtomicBoolean();
        CountDownLatch seen = new CountDownLatch(1);

        try {
            calls.call(abandoned -> {
                blockUninterruptibly();
                sawAbandoned.set(abandoned.getAsBoolean());
                seen.countDown();
                return "late";
            }, 50L, null, null);
            fail();
        } catch (TimeoutException expected) {
            // abandoned
        }
        release.countDown();

        assertTrue(seen.await(5, TimeUnit.SECONDS));
        assertTrue(sawAbandoned.get());
    }

    @Test
    public void aLateResultIsDisposedOfNeverReturned() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");
        AtomicReference<String> disposed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try {
            calls.call(abandoned -> {
                blockUninterruptibly();
                return "late";
            }, 50L, null, value -> {
                disposed.set(value);
                done.countDown();
            });
            fail();
        } catch (TimeoutException expected) {
            // abandoned
        }
        release.countDown();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("late", disposed.get());
        waitUntil(() -> calls.abandonedAliveCount() == 0);
    }

    @Test
    public void aResultThatArrivesInTimeIsNeverDisposed() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");
        AtomicInteger disposed = new AtomicInteger();

        assertEquals("answer", calls.call(abandoned -> "answer", LONG, null, value -> disposed.incrementAndGet()));
        Thread.sleep(50);

        assertEquals(0, disposed.get());
    }

    @Test
    public void aLateFailureIsSwallowed() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");

        try {
            calls.call(abandoned -> {
                blockUninterruptibly();
                throw new IOException("late failure");
            }, 50L, null, null);
            fail();
        } catch (TimeoutException expected) {
            // abandoned
        }
        release.countDown();

        waitUntil(() -> calls.abandonedAliveCount() == 0);
    }

    @Test
    public void tooManyStuckWorkersRefuseTheNextCallWithoutStartingAThread() throws Exception {
        AbandonableCall calls = new AbandonableCall(1, "test-abandonable");
        try {
            calls.call(abandoned -> {
                blockUninterruptibly();
                return "x";
            }, 50L, null, null);
            fail();
        } catch (TimeoutException expected) {
            // one stuck worker is the limit
        }
        AtomicInteger started = new AtomicInteger();

        try {
            calls.call(abandoned -> {
                started.incrementAndGet();
                return "never";
            }, LONG, null, null);
            fail();
        } catch (RejectedExecutionException expected) {
            // refused
        }

        assertEquals(0, started.get());
    }

    @Test
    public void whenTheStuckWorkerEndsTheNextCallIsAcceptedAgain() throws Exception {
        AbandonableCall calls = new AbandonableCall(1, "test-abandonable");
        try {
            calls.call(abandoned -> {
                blockUninterruptibly();
                return "x";
            }, 50L, null, null);
            fail();
        } catch (TimeoutException expected) {
            // stuck
        }
        release.countDown();
        waitUntil(() -> calls.abandonedAliveCount() == 0);

        assertEquals("ok", calls.call(abandoned -> "ok", LONG, null, null));
    }

    @Test
    public void aCallerThatIsInterruptedAbandonsTheWorkerAndKeepsItsInterruptFlag() throws Exception {
        AbandonableCall calls = new AbandonableCall(2, "test-abandonable");
        AtomicBoolean hookRan = new AtomicBoolean();
        AtomicBoolean flagKept = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                calls.call(abandoned -> {
                    blockUninterruptibly();
                    return "x";
                }, LONG, () -> hookRan.set(true), null);
            } catch (TimeoutException e) {
                timedOut.set(true);
            } catch (Exception ignored) {
                // not expected
            }
            flagKept.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        Thread.sleep(100);
        caller.interrupt();
        caller.join(5_000L);

        assertTrue(timedOut.get());
        assertTrue(hookRan.get());
        assertTrue(flagKept.get());
        assertNotNull(calls);
        assertFalse(caller.isAlive());
    }
}
