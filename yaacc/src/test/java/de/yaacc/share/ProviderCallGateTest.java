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

import org.junit.Test;

/**
 * {@link ProviderCallGate}: at most one call per provider is outstanding, for exactly as long as
 * the call is (a hung call keeps its place until it returns, however long that takes), and the
 * number of providers with a call outstanding is bounded. Plain JVM.
 */
public class ProviderCallGateTest {

    private static ProviderCallGate newGate(int maxTracked) {
        return new ProviderCallGate(maxTracked);
    }

    @Test
    public void aSecondCallToTheSameProviderIsRefusedWhileTheFirstIsOutstanding() {
        ProviderCallGate gate = newGate(3);

        assertNotNull(gate.tryAcquire("pkg:a"));
        assertNull(gate.tryAcquire("pkg:a"));
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
    public void aPermitHasNoExpiryItLivesUntilItsCallReturns() throws Exception {
        // a hung Binder call keeps its thread: the provider must stay blocked however long that takes
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit permit = gate.tryAcquire("pkg:a");

        for (int i = 0; i < 50; i++) {
            assertNull(gate.tryAcquire("pkg:a"));
        }
        assertEquals(1, gate.trackedCount());

        gate.release(permit);
        assertNotNull("the late return frees it", gate.tryAcquire("pkg:a"));
    }

    @Test
    public void oneKeyNeverTakesMoreThanOnePlaceHoweverOftenItIsAsked() {
        ProviderCallGate gate = newGate(3);
        int granted = 0;
        for (int i = 0; i < 1_000; i++) {
            if (gate.tryAcquire("pkg:a") != null) {
                granted++;
            }
        }

        assertEquals(1, granted);
        assertEquals(1, gate.trackedCount());
        assertNotNull("two places are left for others", gate.tryAcquire("pkg:b"));
        assertNotNull(gate.tryAcquire("pkg:c"));
    }

    @Test
    public void theLateReturnOfAnOldPermitDoesNotFreeANewerOneOfTheSameKey() {
        ProviderCallGate gate = newGate(3);
        ProviderCallGate.Permit old = gate.tryAcquire("pkg:a");
        gate.release(old);
        ProviderCallGate.Permit newer = gate.tryAcquire("pkg:a");

        gate.release(old);

        assertNull("the newer call is still outstanding", gate.tryAcquire("pkg:a"));
        gate.release(newer);
        assertNotNull(gate.tryAcquire("pkg:a"));
    }

    @Test
    public void onlyTheConfiguredNumberOfProvidersAreTrackedAtOnce() {
        ProviderCallGate gate = newGate(3);
        gate.tryAcquire("pkg:a");
        gate.tryAcquire("pkg:b");

        // two outstanding providers still leave one place for everybody else
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
    public void aMissingKeyIsRefused() {
        assertNull(newGate(3).tryAcquire(null));
    }

    @Test
    public void releasingNullIsHarmless() {
        ProviderCallGate gate = newGate(3);
        gate.release(null);
        assertEquals(0, gate.trackedCount());
    }

    @Test
    public void theDefaultsLeaveAThreadOfThePoolFree() {
        assertEquals(4, ShareLimits.PROVIDER_POOL_THREADS);
        assertEquals(3, ShareLimits.PROVIDER_MAX_TRACKED);
        // at most this many threads are inside provider calls, so one is never taken by them
        assertTrue(ShareLimits.PROVIDER_MAX_TRACKED < ShareLimits.PROVIDER_POOL_THREADS);
        ProviderCallGate gate = ProviderCallGate.createDefault();
        for (int i = 0; i < 10; i++) {
            gate.tryAcquire("pkg:" + i);
        }
        assertEquals(ShareLimits.PROVIDER_MAX_TRACKED, gate.trackedCount());
    }
}
