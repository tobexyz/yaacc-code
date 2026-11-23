/*
 *
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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionRestartPolicyTest {

    private static final SnapcastClientRuntime.Ending FAILED = SnapcastClientRuntime.Ending.ENGINE_FAILED;

    @Test
    public void aFailureThatCanGetBetterRestartsWhileTheSwitchIsOn() {
        SessionRestartPolicy policy = new SessionRestartPolicy();
        assertTrue(policy.shouldRestart(FAILED, true, 0));
        assertTrue(policy.shouldRestart(SnapcastClientRuntime.Ending.UNRESOLVED, true, 1000));
    }

    @Test
    public void nothingRestartsWithTheSwitchOff() {
        assertFalse(new SessionRestartPolicy().shouldRestart(FAILED, false, 0));
    }

    @Test
    public void theUsersStopAndTheOtherEndingsNeverRestart() {
        SessionRestartPolicy policy = new SessionRestartPolicy();
        for (SnapcastClientRuntime.Ending ending : SnapcastClientRuntime.Ending.values()) {
            if (ending != FAILED && ending != SnapcastClientRuntime.Ending.UNRESOLVED) {
                assertFalse(ending.name(), policy.shouldRestart(ending, true, 0));
            }
        }
    }

    @Test
    public void aClientThatKeepsFailingStopsAfterTheLimitAndTriesAgainWhenTheWindowIsOver() {
        SessionRestartPolicy policy = new SessionRestartPolicy();
        for (int i = 0; i < SessionRestartPolicy.MAX_RESTARTS; i++) {
            assertTrue("restart " + i, policy.shouldRestart(FAILED, true, i * 1000L));
        }
        assertFalse(policy.shouldRestart(FAILED, true, 10_000L));
        assertTrue(policy.shouldRestart(FAILED, true, SessionRestartPolicy.WINDOW_MILLIS + 5_000L));
    }
}
