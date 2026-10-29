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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the network is back the folder is reloaded only if its last load failed, nothing is
 * running, and not twice within two seconds.
 */
public class NetworkRecoveryReloadGateTest {

    private final NetworkRecoveryReloadGate gate = new NetworkRecoveryReloadGate();

    @Test
    public void reloadsWhenTheLastLoadFailedAndNothingRuns() {
        assertTrue(gate.shouldReload(true, false, 10_000L));
    }

    @Test
    public void doesNotReloadAFolderThatLoadedFine() {
        assertFalse(gate.shouldReload(false, false, 10_000L));
    }

    @Test
    public void doesNotReloadWhileALoadRuns() {
        assertFalse(gate.shouldReload(true, true, 10_000L));
    }

    @Test
    public void ignoresASecondCallbackWithinTwoSeconds() {
        assertTrue(gate.shouldReload(true, false, 10_000L));
        assertFalse(gate.shouldReload(true, false, 11_999L));
    }

    @Test
    public void acceptsACallbackOnceTheWindowIsOver() {
        assertTrue(gate.shouldReload(true, false, 10_000L));
        assertTrue(gate.shouldReload(true, false, 12_000L));
    }

    @Test
    public void anIgnoredCallbackDoesNotExtendTheWindow() {
        assertTrue(gate.shouldReload(true, false, 10_000L));
        assertFalse(gate.shouldReload(true, false, 11_500L));
        assertTrue("two seconds after the counted one, not after the ignored one", gate.shouldReload(true, false, 12_000L));
    }

    @Test
    public void aCallbackThatFoundNothingToReloadStillStartsTheWindow() {
        assertFalse(gate.shouldReload(false, false, 10_000L));
        assertFalse(gate.shouldReload(true, false, 10_500L));
    }

    @Test
    public void reloadNowNeedsAFailedLoadAndNothingRunning() {
        assertTrue(NetworkRecoveryReloadGate.shouldReloadNow(true, false));
        assertFalse(NetworkRecoveryReloadGate.shouldReloadNow(false, false));
        assertFalse(NetworkRecoveryReloadGate.shouldReloadNow(true, true));
    }

    @Test
    public void reloadNowIsNotHeldBackByTheNetworkWindow() {
        // a network callback that found nothing to reload started the window ...
        assertFalse(gate.shouldReload(false, false, 10_000L));
        assertFalse(gate.shouldReload(true, false, 10_500L));
        // ... the server coming back right after it must still be able to continue the folder
        assertTrue(NetworkRecoveryReloadGate.shouldReloadNow(true, false));
    }
}
