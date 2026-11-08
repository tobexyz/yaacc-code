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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Tests for {@link ShareConcurrencyLimiter}: at most N concurrent responses per share key. */
public class ShareConcurrencyLimiterTest {

    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBB";

    @Test
    public void grantsUpToTheMaximumPerKeyAndRefusesTheNext() {
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(4);
        List<ShareConcurrencyLimiter.Lease> leases = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
            assertNotNull("lease " + i, lease);
            leases.add(lease);
        }
        assertNull(limiter.tryAcquire(KEY_A));
        assertEquals(4, limiter.activeCount(KEY_A));
    }

    @Test
    public void theDefaultMaximumIsFour() {
        assertEquals(4, ShareLimits.MAX_CONCURRENT_RESPONSES_PER_SHARE);
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter();
        for (int i = 0; i < 4; i++) {
            assertNotNull(limiter.tryAcquire(KEY_A));
        }
        assertNull(limiter.tryAcquire(KEY_A));
    }

    @Test
    public void keysAreCountedIndependently() {
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(1);
        assertNotNull(limiter.tryAcquire(KEY_A));
        assertNull(limiter.tryAcquire(KEY_A));
        assertNotNull(limiter.tryAcquire(KEY_B));
    }

    @Test
    public void releasingFreesASlot() {
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(1);
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
        assertNull(limiter.tryAcquire(KEY_A));
        lease.release();
        assertEquals(0, limiter.activeCount(KEY_A));
        assertNotNull(limiter.tryAcquire(KEY_A));
    }

    @Test
    public void releasingTwiceFreesOnlyOneSlot() {
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(2);
        ShareConcurrencyLimiter.Lease first = limiter.tryAcquire(KEY_A);
        assertNotNull(limiter.tryAcquire(KEY_A));
        first.release();
        first.release();
        first.release();
        assertEquals(1, limiter.activeCount(KEY_A));
        assertNotNull(limiter.tryAcquire(KEY_A));
        assertNull(limiter.tryAcquire(KEY_A));
    }

    @Test
    public void aNullOrMalformedKeyIsNeverGranted() {
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(4);
        assertNull(limiter.tryAcquire(null));
        assertNull(limiter.tryAcquire(""));
        assertNull(limiter.tryAcquire("../etc/passwd"));
        assertEquals(0, limiter.activeCount(null));
    }

    @Test
    public void unknownKeysHaveNoActiveResponses() {
        assertEquals(0, new ShareConcurrencyLimiter(4).activeCount(KEY_A));
    }

    @Test
    public void bookkeepingForReleasedKeysIsDropped() {
        ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(4);
        limiter.tryAcquire(KEY_A).release();
        limiter.tryAcquire(KEY_B).release();
        assertEquals(0, limiter.trackedKeys());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNonPositiveMaximumIsRejected() {
        new ShareConcurrencyLimiter(0);
    }

    @Test
    public void concurrentAcquiresNeverExceedTheMaximum() throws Exception {
        final int max = 4;
        final ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(max);
        final AtomicInteger granted = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY_A);
                        if (lease != null) {
                            int now = granted.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            granted.decrementAndGet();
                            lease.release();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue("peak " + peak.get(), peak.get() <= max);
        assertEquals(0, limiter.activeCount(KEY_A));
    }
}
