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
