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

package de.yaacc.upnp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the resume check does about the network listener and the device search. Plain JVM.
 */
public class ResumeRefreshPolicyTest {

    private static ResumeRefreshPolicy.ListenerCheck check(boolean usable, boolean initialized, boolean changed,
                                                            boolean multicast, boolean unicast) {
        return ResumeRefreshPolicy.checkListener(usable, initialized, changed, multicast, unicast);
    }

    @Test
    public void aRunningListenerOnTheActiveNetworkIsHealthy() {
        ResumeRefreshPolicy.ListenerCheck result = check(true, true, false, true, true);

        assertEquals(ResumeRefreshPolicy.ListenerCheck.HEALTHY, result);
        assertFalse(result.needsReenable());
    }

    @Test
    public void withoutAUsableNetworkNothingIsRecreated() {
        // airplane mode or cellular only: whatever the sockets look like, there is nothing to bind to
        ResumeRefreshPolicy.ListenerCheck result = check(false, false, true, false, false);

        assertEquals(ResumeRefreshPolicy.ListenerCheck.NO_NETWORK, result);
        assertFalse(result.needsReenable());
    }

    @Test
    public void aDisabledListenerWithANetworkIsEnabledAgain() {
        // onLost was seen, the onAvailable was missed while suspended
        ResumeRefreshPolicy.ListenerCheck result = check(true, false, false, false, false);

        assertEquals(ResumeRefreshPolicy.ListenerCheck.NOT_INITIALIZED, result);
        assertTrue(result.needsReenable());
    }

    @Test
    public void anotherActiveNetworkEnablesTheListenerAgain() {
        // Wi-Fi to cellular and back, or to another access point, without a callback
        ResumeRefreshPolicy.ListenerCheck result = check(true, true, true, true, true);

        assertEquals(ResumeRefreshPolicy.ListenerCheck.NETWORK_CHANGED, result);
        assertTrue(result.needsReenable());
    }

    @Test
    public void aDeadMulticastLoopEnablesTheListenerAgain() {
        ResumeRefreshPolicy.ListenerCheck result = check(true, true, false, false, true);

        assertEquals(ResumeRefreshPolicy.ListenerCheck.MULTICAST_DEAD, result);
        assertTrue(result.needsReenable());
    }

    @Test
    public void aDeadUnicastLoopEnablesTheListenerAgain() {
        ResumeRefreshPolicy.ListenerCheck result = check(true, true, false, true, false);

        assertEquals(ResumeRefreshPolicy.ListenerCheck.UNICAST_DEAD, result);
        assertTrue(result.needsReenable());
    }

    @Test
    public void aChangedNetworkIsReportedBeforeDeadLoops() {
        assertEquals(ResumeRefreshPolicy.ListenerCheck.NETWORK_CHANGED, check(true, true, true, false, false));
    }

    @Test
    public void searchesWhenThereWasNoSearchYet() {
        assertTrue(ResumeRefreshPolicy.shouldSearch(false, ResumeRefreshPolicy.NEVER, 5_000L));
    }

    @Test
    public void doesNotSearchAgainWithinThirtySeconds() {
        assertFalse(ResumeRefreshPolicy.shouldSearch(false, 100_000L, 100_000L + 29_999L));
    }

    @Test
    public void searchesAgainAfterThirtySeconds() {
        assertTrue(ResumeRefreshPolicy.shouldSearch(false, 100_000L, 100_000L + 30_000L));
        assertTrue(ResumeRefreshPolicy.shouldSearch(false, 100_000L, 100_000L + 10 * 60_000L));
    }

    @Test
    public void searchesAtOnceAfterTheListenerWasRecreated() {
        // its new sockets have not heard of any device, however recent the last search was
        assertTrue(ResumeRefreshPolicy.shouldSearch(true, 100_000L, 100_001L));
    }

    @Test
    public void searchesWhenTheClockWentBack() {
        assertTrue(ResumeRefreshPolicy.shouldSearch(false, 100_000L, 50_000L));
    }
}
