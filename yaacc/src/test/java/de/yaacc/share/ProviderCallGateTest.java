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

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link ProviderCallGate}: at most one call per provider is in flight, a provider whose call
 * outlived the caller's wait is "stuck" for a while and gets no further call, and the number of
 * providers that may be in flight or stuck at once is bounded. Plain JVM, injected clock.
 */
public class ProviderCallGateTest {

    private static final long STUCK = 600_000L;

    private final AtomicLong clock = new AtomicLong(0L);

    private ProviderCallGate newGate(int maxTracked) {
        return new ProviderCallGate(clock::get, maxTracked, STUCK);
    }

    @Test
    public void aSecondCallToTheSameProviderIsRefusedWhileTheFirstIsInFlight() {
        ProviderCallGate gate = newGate(3);

        assertNotNull(gate.tryAcquire("pkg:a"));
        assertNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void anotherProviderIsNotAffected() {
        ProviderCallGate gate = newGate(3);
        gate.tryAcquire("pkg:a");

        assertNotNull(gate.tryAcquire("pkg:b"));
    }

    @Test
    public void aReturnedCallFreesTheProviderAtOnce() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit permit = gate.tryAcquire("pkg:a");

        gate.release(permit);

        assertEquals(0, gate.trackedCount());
        assertNotNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void aStuckProviderIsRefusedUntilItsCallReturns() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit permit = gate.tryAcquire("pkg:a");
        gate.markStuck(permit);
        clock.addAndGet(STUCK - 1L);

        assertNull(gate.tryAcquire("pkg:a"));

        gate.release(permit);
        assertNotNull("the late return frees it before the marker expires", gate.tryAcquire("pkg:a"));
    }

    @Test
    public void theStuckMarkerExpires() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit permit = gate.tryAcquire("pkg:a");
        gate.markStuck(permit);

        clock.addAndGet(STUCK);

        assertNotNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void theStuckClockStartsWhenTheCallerGaveUpNotWhenItBegan() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit permit = gate.tryAcquire("pkg:a");
        clock.addAndGet(5_000L);
        gate.markStuck(permit);
        clock.addAndGet(STUCK - 1L);

        assertNull(gate.tryAcquire("pkg:a"));
        clock.addAndGet(1L);
        assertNotNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void theLateReturnOfAnExpiredCallDoesNotFreeTheNewerCallOfTheSameProvider() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit old = gate.tryAcquire("pkg:a");
        gate.markStuck(old);
        clock.addAndGet(STUCK);
        ProviderCallGate.Permit newer = gate.tryAcquire("pkg:a");
        assertNotNull(newer);

        gate.release(old);

        assertNull("the newer call is still in flight", gate.tryAcquire("pkg:a"));
        gate.release(newer);
        assertNotNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void markingACallThatAlreadyReturnedDoesNothing() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit permit = gate.tryAcquire("pkg:a");
        gate.release(permit);

        gate.markStuck(permit);

        assertEquals(0, gate.trackedCount());
        assertNotNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void onlyTheConfiguredNumberOfProvidersAreTrackedAtOnce() {
        ProviderCallGate gate = newGate(3);
        gate.markStuck(gate.tryAcquire("pkg:a"));
        gate.markStuck(gate.tryAcquire("pkg:b"));

        // two stuck providers still leave one place for everybody else
        ProviderCallGate.Permit c = gate.tryAcquire("pkg:c");
        assertNotNull(c);
        assertNull("a fourth provider is refused, not tracked", gate.tryAcquire("pkg:d"));
        assertEquals(3, gate.trackedCount());

        gate.release(c);
        assertNotNull(gate.tryAcquire("pkg:d"));
    }

    @Test
    public void theMapStaysBoundedWhateverTheKeys() {
        ProviderCallGate gate = newGate(3);
        for (int i = 0; i < 1_000; i++) {
            gate.tryAcquire("pkg:" + i);
        }

        assertEquals(3, gate.trackedCount());
    }

    @Test
    public void expiredMarkersFreeTheirPlaceForOtherProviders() {
        ProviderCallGate gate = newGate(1);
        gate.markStuck(gate.tryAcquire("pkg:a"));
        assertNull(gate.tryAcquire("pkg:b"));

        clock.addAndGet(STUCK);

        assertNotNull(gate.tryAcquire("pkg:b"));
        assertEquals(1, gate.trackedCount());
    }

    @Test
    public void aMissingKeyIsRefused() {
        assertNull(newGate(3).tryAcquire(null));
    }

    @Test
    public void releasingNullIsHarmless() {
        ProviderCallGate gate = newGate(3);
        gate.release(null);
        gate.markStuck(null);
        assertEquals(0, gate.trackedCount());
    }

    @Test
    public void theDefaultsAreFourThreadsThreePlacesAndTenMinutes() {
        assertEquals(4, ShareLimits.PROVIDER_POOL_THREADS);
        assertEquals(3, ShareLimits.PROVIDER_MAX_TRACKED);
        assertEquals(10L * 60L * 1000L, ShareLimits.PROVIDER_STUCK_MILLIS);
        // one thread is never needed by a tracked provider: a call that just returned can be followed at once
        org.junit.Assert.assertTrue(ShareLimits.PROVIDER_MAX_TRACKED < ShareLimits.PROVIDER_POOL_THREADS);
        assertNotNull(ProviderCallGate.createDefault().tryAcquire("pkg:a"));
    }
}
