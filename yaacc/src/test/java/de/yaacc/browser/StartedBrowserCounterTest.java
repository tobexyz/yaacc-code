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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link StartedBrowserCounter}: the idle timer must be armed only when the last
 * started browser stops. Plain JVM, with a fake scheduler.
 */
public class StartedBrowserCounterTest {

    private static final long TIMEOUT = 300_000L;

    /** Records the single pending task like a Handler would; fire() runs it even if cancelled. */
    private static final class FakeScheduler implements StartedBrowserCounter.Scheduler {
        Runnable pending;
        long pendingDelay = -1;
        int scheduleCalls = 0;
        int cancelCalls = 0;
        Runnable lastScheduled;

        @Override
        public void schedule(Runnable task, long delayMillis) {
            pending = task;
            lastScheduled = task;
            pendingDelay = delayMillis;
            scheduleCalls++;
        }

        @Override
        public void cancel(Runnable task) {
            if (task == pending) {
                pending = null;
                cancelCalls++;
            }
        }

        boolean isArmed() {
            return pending != null;
        }

        /** Simulates the timer expiring (also after a cancel that raced with the expiry). */
        void fire() {
            Runnable task = pending != null ? pending : lastScheduled;
            pending = null;
            task.run();
        }
    }

    private FakeScheduler scheduler;
    private int idleCalls;
    private StartedBrowserCounter counter;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
        idleCalls = 0;
        counter = new StartedBrowserCounter(scheduler, TIMEOUT, () -> idleCalls++);
    }

    @Test
    public void nothingIsArmedBeforeAnyBrowserHasStopped() {
        assertFalse(scheduler.isArmed());
        counter.onBrowserStarted();
        assertFalse(scheduler.isArmed());
        assertEquals(1, counter.startedCount());
    }

    @Test
    public void finalStopArmsTheTimerWithTheTimeout() {
        counter.onBrowserStarted();
        counter.onBrowserStopped();

        assertTrue(scheduler.isArmed());
        assertEquals(TIMEOUT, scheduler.pendingDelay);
        assertEquals(0, counter.startedCount());
    }

    @Test
    public void twoOverlappingBrowsersArmOnlyWhenTheSecondOneStops() {
        counter.onBrowserStarted();
        counter.onBrowserStarted();

        counter.onBrowserStopped();
        assertFalse(scheduler.isArmed());
        assertEquals(1, counter.startedCount());

        counter.onBrowserStopped();
        assertTrue(scheduler.isArmed());
        assertEquals(1, scheduler.scheduleCalls);
    }

    @Test
    public void layoutSwitchNewStartsBeforeOldStopsNeverArms() {
        counter.onBrowserStarted();      // old browser
        counter.onBrowserStarted();      // new browser starts first
        counter.onBrowserStopped();      // old browser stops

        assertFalse(scheduler.isArmed());
        assertEquals(0, scheduler.scheduleCalls);
        assertEquals(1, counter.startedCount());
    }

    @Test
    public void layoutSwitchOldStopsBeforeNewStartsCancelsAgain() {
        counter.onBrowserStarted();      // old browser
        counter.onBrowserStopped();      // old browser stops first: armed
        assertTrue(scheduler.isArmed());

        counter.onBrowserStarted();      // new browser starts: must cancel
        assertFalse(scheduler.isArmed());
        assertEquals(1, counter.startedCount());
    }

    @Test
    public void rotationStopThenStartCancelsAndTheNextStopArmsAgain() {
        counter.onBrowserStarted();
        counter.onBrowserStopped();
        counter.onBrowserStarted();
        assertFalse(scheduler.isArmed());

        counter.onBrowserStopped();
        assertTrue(scheduler.isArmed());
        assertEquals(2, scheduler.scheduleCalls);
    }

    @Test
    public void restartCancelsThePendingTimer() {
        counter.onBrowserStarted();
        counter.onBrowserStopped();
        assertTrue(scheduler.isArmed());

        counter.onBrowserStarted();
        assertNull(scheduler.pending);
        assertEquals(1, scheduler.cancelCalls);
    }

    @Test
    public void unbalancedStopDoesNotGoNegativeOrArm() {
        counter.onBrowserStopped();
        counter.onBrowserStopped();

        assertEquals(0, counter.startedCount());
        assertFalse(scheduler.isArmed());

        counter.onBrowserStarted();
        assertEquals(1, counter.startedCount());
        counter.onBrowserStopped();
        assertTrue(scheduler.isArmed());
    }

    @Test
    public void expiryRunsTheIdleActionOnce() {
        counter.onBrowserStarted();
        counter.onBrowserStopped();

        scheduler.fire();

        assertEquals(1, idleCalls);
    }

    @Test
    public void expiryRacingWithAStartDoesNothing() {
        counter.onBrowserStarted();
        counter.onBrowserStopped();
        counter.onBrowserStarted();      // cancels, but the expiry was already queued

        scheduler.fire();

        assertEquals(0, idleCalls);
    }

    @Test
    public void armingTwiceNeverLeavesTwoPendingTasks() {
        counter.onBrowserStarted();
        counter.onBrowserStopped();
        counter.onBrowserStopped();      // unbalanced

        assertEquals(1, scheduler.scheduleCalls);
    }
}
