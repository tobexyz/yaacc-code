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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The write-idle watchdog of {@link ShareConcurrencyLimiter}: a response that makes no progress
 * for the idle time gives its slot back (so stalled or trickling readers cannot use up the cap)
 * and is told to abort at its next callback. Driven with a manual clock.
 */
public class ShareConcurrencyLimiterWatchdogTest {

    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final long IDLE = ShareLimits.WRITE_IDLE_MILLIS;
    private static final long STEP = ShareLimits.MIN_PROGRESS_BYTES;

    private final AtomicLong now = new AtomicLong(1_000L);
    private final ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(2, now::get);

    @Test
    public void theLimitsAreSixtySecondsAndSixteenKiB() {
        assertEquals(60_000L, ShareLimits.WRITE_IDLE_MILLIS);
        assertEquals(16 * 1024, ShareLimits.MIN_PROGRESS_BYTES);
    }

    @Test
    public void aLeaseWithoutProgressIsExpiredAfterTheIdleTimeAndGivesItsSlotBack() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);

        assertEquals(1, limiter.expireIdle(IDLE));

        assertFalse(lease.isLive());
        assertTrue(lease.isExpired());
        assertEquals(0, limiter.activeCount(KEY_A));
        assertEquals(0, limiter.trackedKeys());
    }

    @Test
    public void aLeaseIsNotExpiredBeforeTheIdleTimeIsOver() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE);

        assertEquals(0, limiter.expireIdle(IDLE));

        assertTrue(lease.isLive());
        assertEquals(1, limiter.activeCount(KEY_A));
    }

    @Test
    public void progressOfAtLeastTheStepResetsTheIdleClock() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE - 1_000L);
        lease.noteProgress(STEP);
        now.addAndGet(IDLE - 1_000L);

        assertEquals(0, limiter.expireIdle(IDLE));
        assertTrue(lease.isLive());

        now.addAndGet(2_000L);
        assertEquals(1, limiter.expireIdle(IDLE));
    }

    @Test
    public void aTrickleBelowTheStepDoesNotKeepALeaseAlive() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        for (int i = 0; i < 100; i++) {
            now.addAndGet(IDLE / 50);
            lease.noteProgress(100);
        }

        // 10 KB over two idle periods is under the step: no progress was ever recorded
        assertEquals(1, limiter.expireIdle(IDLE));
        assertFalse(lease.isLive());
    }

    @Test
    public void smallWritesAddUpToAStep() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE - 1_000L);
        lease.noteProgress(STEP / 2);
        lease.noteProgress(STEP / 2);
        now.addAndGet(IDLE - 1_000L);

        assertEquals(0, limiter.expireIdle(IDLE));
    }

    @Test
    public void nonPositiveProgressIsIgnored() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        lease.noteProgress(0);
        lease.noteProgress(-5);
        lease.noteProgress(Long.MIN_VALUE);
        now.addAndGet(IDLE + 1);

        assertEquals(1, limiter.expireIdle(IDLE));
    }

    @Test
    public void anExpiredSlotCanBeTakenByTheNextResponse() {
        ShareConcurrencyLimiter.Lease first = limiter.tryAcquire(KEY_A);
        ShareConcurrencyLimiter.Lease second = limiter.tryAcquire(KEY_A);
        assertNull(limiter.tryAcquire(KEY_A));
        now.addAndGet(IDLE + 1);
        second.noteProgress(STEP);

        assertEquals(1, limiter.expireIdle(IDLE));

        assertNotNull(limiter.tryAcquire(KEY_A));
        assertFalse(first.isLive());
        assertTrue(second.isLive());
    }

    @Test
    public void releasingAnExpiredLeaseDoesNotGiveASlotBackTwice() {
        ShareConcurrencyLimiter.Lease expired = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);
        ShareConcurrencyLimiter.Lease other = limiter.tryAcquire(KEY_A);
        assertNotNull(other);

        // HttpCore reports the end of the stalled response later (socket timeout)
        expired.release();
        expired.release();

        assertEquals(1, limiter.activeCount(KEY_A));
        assertTrue(other.isLive());
    }

    @Test
    public void expiringTwiceCountsALeaseOnce() {
        limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);

        assertEquals(1, limiter.expireIdle(IDLE));
        assertEquals(0, limiter.expireIdle(IDLE));
        assertEquals(0, limiter.activeCount(KEY_A));
    }

    @Test
    public void aReleasedLeaseIsNotLiveAndIsNeverExpired() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        lease.release();
        now.addAndGet(IDLE + 1);

        assertFalse(lease.isLive());
        assertFalse(lease.isExpired());
        assertEquals(0, limiter.expireIdle(IDLE));
    }

    @Test
    public void progressAfterTheExpiryDoesNotRevivePhysicalSlots() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);

        lease.noteProgress(10 * STEP);

        assertFalse(lease.isLive());
        assertEquals(0, limiter.activeCount(KEY_A));
    }

    @Test
    public void onlyTheIdleLeasesOfTheSameOrOtherKeysAreExpired() {
        ShareConcurrencyLimiter.Lease idleA = limiter.tryAcquire(KEY_A);
        ShareConcurrencyLimiter.Lease busyB = limiter.tryAcquire(KEY_B);
        now.addAndGet(IDLE + 1);
        busyB.noteProgress(STEP);

        assertEquals(1, limiter.expireIdle(IDLE));

        assertFalse(idleA.isLive());
        assertTrue(busyB.isLive());
        assertEquals(1, limiter.activeCount(KEY_B));
    }

    // ---- re-acquire: an expired response that resumes asks for a slot again

    @Test
    public void aResumedLeaseGetsASlotAgainWhenOneIsFree() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);
        assertEquals(0, limiter.activeCount(KEY_A));

        assertTrue(lease.tryReacquire());

        assertTrue(lease.isLive());
        assertFalse(lease.isExpired());
        assertEquals(1, limiter.activeCount(KEY_A));
    }

    @Test
    public void aResumedLeaseFailsWhenAnotherResponseTookTheSlotsAndTheCapIsFull() {
        ShareConcurrencyLimiter.Lease idle = limiter.tryAcquire(KEY_A);
        ShareConcurrencyLimiter.Lease busy = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        busy.noteProgress(STEP);
        assertEquals(1, limiter.expireIdle(IDLE));
        // the freed slot is reused by another response while the first one is paused
        ShareConcurrencyLimiter.Lease other = limiter.tryAcquire(KEY_A);
        assertNotNull(other);

        assertFalse(idle.tryReacquire());

        assertFalse(idle.isLive());
        assertTrue(idle.isExpired());
        assertEquals(2, limiter.activeCount(KEY_A));
        // and it can still try again once a slot frees up
        other.release();
        assertTrue(idle.tryReacquire());
        assertEquals(2, limiter.activeCount(KEY_A));
    }

    @Test
    public void reacquiringALiveLeaseChangesNothing() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);

        assertTrue(lease.tryReacquire());
        assertTrue(lease.tryReacquire());

        assertEquals(1, limiter.activeCount(KEY_A));
    }

    @Test
    public void aReleasedLeaseCanNeverBeReacquired() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        lease.release();

        assertFalse(lease.tryReacquire());
        assertEquals(0, limiter.activeCount(KEY_A));

        ShareConcurrencyLimiter.Lease expired = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);
        expired.release();

        assertFalse(expired.tryReacquire());
        assertEquals(0, limiter.activeCount(KEY_A));
    }

    @Test
    public void aResumedLeaseStartsAFreshProgressWindow() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);
        lease.tryReacquire();

        now.addAndGet(IDLE - 1_000L);
        assertEquals("not idle yet in the new window", 0, limiter.expireIdle(IDLE));

        now.addAndGet(2_000L);
        assertEquals("idle again without progress", 1, limiter.expireIdle(IDLE));
        assertTrue(lease.isExpired());
    }

    @Test
    public void bytesNotedBeforeTheExpiryDoNotCarryIntoTheFreshWindow() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        lease.noteProgress(STEP - 1);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);
        lease.tryReacquire();

        lease.noteProgress(1);
        now.addAndGet(IDLE + 1);

        assertEquals("the one byte alone is no progress step", 1, limiter.expireIdle(IDLE));
    }

    @Test
    public void expiredReacquiredAndReleasedGivesTheSlotBackExactlyOnce() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        now.addAndGet(IDLE + 1);
        limiter.expireIdle(IDLE);
        assertTrue(lease.tryReacquire());

        lease.release();
        lease.release();

        assertEquals(0, limiter.activeCount(KEY_A));
        assertEquals(0, limiter.trackedKeys());
        assertNotNull(limiter.tryAcquire(KEY_A));
        assertNotNull(limiter.tryAcquire(KEY_A));
        assertNull(limiter.tryAcquire(KEY_A));
    }

    @Test
    public void aLeaseCanExpireAndResumeRepeatedlyWithoutDrifting() {
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        for (int i = 0; i < 20; i++) {
            now.addAndGet(IDLE + 1);
            assertEquals(1, limiter.expireIdle(IDLE));
            assertEquals(0, limiter.activeCount(KEY_A));
            assertTrue(lease.tryReacquire());
            assertEquals(1, limiter.activeCount(KEY_A));
        }
        lease.release();
        assertEquals(0, limiter.activeCount(KEY_A));
    }

    @Test
    public void theCapIsNeverExceededUnderInterleavedExpiryReacquireAndRelease() throws Exception {
        final int cap = 3;
        final AtomicLong clock = new AtomicLong(0L);
        final ShareConcurrencyLimiter shared = new ShareConcurrencyLimiter(cap, clock::get);
        final java.util.concurrent.atomic.AtomicInteger violations = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                java.util.Random random = new java.util.Random(seed);
                start.await();
                ShareConcurrencyLimiter.Lease lease = null;
                for (int i = 0; i < 4_000; i++) {
                    switch (random.nextInt(4)) {
                        case 0:
                            if (lease == null) {
                                lease = shared.tryAcquire(KEY_A);
                            }
                            break;
                        case 1:
                            if (lease != null) {
                                lease.tryReacquire();
                            }
                            break;
                        case 2:
                            if (lease != null) {
                                lease.release();
                                lease = null;
                            }
                            break;
                        default:
                            if (lease != null) {
                                lease.noteProgress(STEP);
                            }
                    }
                    if (shared.activeCount(KEY_A) > cap) {
                        violations.incrementAndGet();
                    }
                }
                if (lease != null) {
                    lease.release();
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 4_000; i++) {
                clock.addAndGet(IDLE / 3);
                shared.expireIdle(IDLE);
                if (shared.activeCount(KEY_A) > cap) {
                    violations.incrementAndGet();
                }
            }
            return null;
        }));
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(0, violations.get());
        assertEquals("every lease was released exactly once", 0, shared.activeCount(KEY_A));
        assertEquals(0, shared.trackedKeys());
    }

    @Test
    public void aNonPositiveIdleTimeIsRejected() {
        try {
            limiter.expireIdle(0L);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    @Test
    public void theSharedInstanceIsOneProcessWideLimiter() {
        assertTrue(ShareConcurrencyLimiter.shared() == ShareConcurrencyLimiter.shared());
    }
}
