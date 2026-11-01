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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SafBrowseGate}: the preload waits for a Browse that is being answered, for a limited time. Plain JVM.
 */
public class SafBrowseGateTest {

    @Test
    public void nothingToWaitForWhenNoBrowseIsActive() {
        SafBrowseGate gate = new SafBrowseGate();

        assertFalse(gate.isBusy());
        assertEquals(0, gate.awaitIdle(10_000));
    }

    @Test
    public void aWaiterIsReleasedWhenTheLastBrowseEnds() throws Exception {
        SafBrowseGate gate = new SafBrowseGate();
        gate.enter();
        gate.enter();
        AtomicLong waited = new AtomicLong(-1);
        CountDownLatch started = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            started.countDown();
            waited.set(gate.awaitIdle(30_000));
        });
        waiter.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        gate.exit();
        Thread.sleep(100);
        assertEquals("one Browse is still active", -1, waited.get());
        assertTrue(gate.isBusy());

        gate.exit();
        waiter.join(5000);
        assertFalse(waiter.isAlive());
        assertTrue("released long before the limit", waited.get() >= 0 && waited.get() < 10_000);
        assertFalse(gate.isBusy());
    }

    @Test
    public void aWaiterGivesUpAfterTheLimit() {
        SafBrowseGate gate = new SafBrowseGate();
        gate.enter();

        long start = System.nanoTime();
        long waited = gate.awaitIdle(150);
        long elapsed = (System.nanoTime() - start) / 1_000_000L;

        assertTrue("waited " + waited, waited >= 100 && waited < 5000);
        assertTrue("elapsed " + elapsed, elapsed >= 100 && elapsed < 5000);
        assertTrue("the Browse is still active", gate.isBusy());
    }

    @Test
    public void anExitWithoutEnterDoesNotCountBackwards() {
        SafBrowseGate gate = new SafBrowseGate();
        gate.exit();
        gate.enter();

        assertTrue(gate.isBusy());
        gate.exit();
        assertFalse(gate.isBusy());
    }

    @Test
    public void anInterruptEndsTheWaitAndIsKept() throws Exception {
        SafBrowseGate gate = new SafBrowseGate();
        gate.enter();
        AtomicLong interrupted = new AtomicLong(-1);
        Thread waiter = new Thread(() -> {
            gate.awaitIdle(30_000);
            interrupted.set(Thread.currentThread().isInterrupted() ? 1 : 0);
        });
        waiter.start();
        Thread.sleep(100);

        waiter.interrupt();
        waiter.join(5000);

        assertEquals(1, interrupted.get());
    }
}
