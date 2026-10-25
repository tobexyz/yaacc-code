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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** {@link BoundedCall}: a provider that hangs ties up a bounded number of threads, no more. */
public class BoundedCallTest {

    private static final long LONG = 5_000L;

    @Test
    public void returnsTheResultOfAFastCall() {
        BoundedCall calls = new BoundedCall(2, "test-call");

        assertEquals("answer", calls.call(() -> "answer", LONG, null));
    }

    @Test
    public void aCallThatThrowsIsANullResult() {
        BoundedCall calls = new BoundedCall(2, "test-call");

        assertNull(calls.<String>call(() -> {
            throw new IllegalStateException("provider text that must not be kept");
        }, LONG, null));
    }

    @Test
    public void aCallThatReturnsNullIsANullResult() {
        BoundedCall calls = new BoundedCall(2, "test-call");

        assertNull(calls.<String>call(() -> null, LONG, null));
    }

    @Test
    public void aSlowCallTimesOutAndItsLateResultIsDisposedOfNotReturned() throws Exception {
        BoundedCall calls = new BoundedCall(2, "test-call");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        AtomicReference<String> late = new AtomicReference<>();

        String result = calls.call(() -> {
            // not interruptible, like a binder call
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
            return "late";
        }, 50L, value -> {
            late.set(value);
            disposed.countDown();
        });
        release.countDown();

        assertNull(result);
        assertTrue(disposed.await(5, TimeUnit.SECONDS));
        assertEquals("late", late.get());
    }

    @Test
    public void whenAllThreadsAreBusyANewCallIsRefusedAtOnce() throws Exception {
        BoundedCall calls = new BoundedCall(1, "test-call");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread hanging = new Thread(() -> calls.call(() -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
            return "x";
        }, LONG, null));
        hanging.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        long begin = System.nanoTime();
        String second = calls.call(() -> "never", LONG, null);
        long tookMillis = (System.nanoTime() - begin) / 1_000_000L;

        assertNull(second);
        assertTrue("refused without waiting, took " + tookMillis, tookMillis < 1_000L);
        release.countDown();
        hanging.join(5_000L);
    }

    @Test
    public void aFinishedCallFreesItsThread() {
        BoundedCall calls = new BoundedCall(1, "test-call");

        assertEquals("a", calls.call(() -> "a", LONG, null));
        assertEquals("b", calls.call(() -> "b", LONG, null));
    }

    @Test
    public void anInterruptedWaiterGetsNullAndKeepsItsFlag() throws Exception {
        BoundedCall calls = new BoundedCall(1, "test-call");
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> flag = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>("unset");
        Thread waiter = new Thread(() -> {
            result.set(calls.call(() -> {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // keep waiting
                    }
                }
                return "x";
            }, LONG, null));
            flag.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        Thread.sleep(100L);
        waiter.interrupt();
        waiter.join(5_000L);
        release.countDown();

        assertNull(result.get());
        assertEquals(Boolean.TRUE, flag.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void atLeastOneThreadIsRequired() {
        new BoundedCall(0, "x");
    }
}
