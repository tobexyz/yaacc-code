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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.multiroom.client.SnapcastClientRestore.Facts;
import de.yaacc.multiroom.client.SnapcastClientRestore.Point;
import de.yaacc.multiroom.client.SnapcastClientRestore.Skip;

/**
 * Task 12.6 / 12C: when the phone's own Snapcast client is started again (at the start of the process and, as one more try, when a
 * screen starts). The table: switched on AND option on AND a server set AND no session AND not held back after an exit AND the last
 * ending is not an unseen failure AND no attempt in this process yet.
 */
public class SnapcastClientRestoreTest {

    /** Everything as it has to be for a restore at the start of the process. */
    private static Facts ok() {
        return new Facts(Point.PROCESS_START).switchedOn(true).optionEnabled(true).serverSet(true);
    }

    private static void assertSkip(Skip expected, Facts facts) {
        SnapcastClientRestore.Decision decision = SnapcastClientRestore.decide(facts);
        assertFalse(expected.name(), decision.restore());
        assertEquals(expected, decision.skip());
    }

    @Test
    public void everythingInOrderRestores() {
        SnapcastClientRestore.Decision decision = SnapcastClientRestore.decide(ok());

        assertTrue(decision.restore());
        assertNull(decision.skip());
    }

    @Test
    public void aClientThatWasNotSwitchedOnIsNotRestored() {
        assertSkip(Skip.NOT_SWITCHED_ON, ok().switchedOn(false));
    }

    @Test
    public void withTheOptionOffNothingStarts() {
        assertSkip(Skip.OPTION_OFF, ok().optionEnabled(false));
    }

    @Test
    public void withoutAServerNothingStarts() {
        assertSkip(Skip.NO_SERVER, ok().serverSet(false));
    }

    @Test
    public void rightAfterAnExitNothingStartsUntilTheUserStartsTheApp() {
        assertSkip(Skip.EXIT_HOLD, ok().exitHold(true));
        // the user starts the app: the hold is gone and the screen start restores
        SnapcastClientRestore.Decision later = SnapcastClientRestore.decide(new Facts(Point.SCREEN_START).switchedOn(true)
                .optionEnabled(true).serverSet(true).exitHold(false));
        assertTrue(later.restore());
    }

    @Test
    public void aSessionThatRunsIsNotStartedASecondTime() {
        assertSkip(Skip.ALREADY_ACTIVE, ok().active(true));
    }

    @Test
    public void anUnseenFailureIsNotRetriedBlindlyButASeenOneIs() {
        for (String failure : new String[]{"BAD_ADDRESS", "UNRESOLVED", "CONNECTION_GAVE_UP", "SERVER_REFUSED", "ENGINE_FAILED",
                "SERVICE_ERROR", "NO_AUDIO"}) {
            assertSkip(Skip.UNSEEN_FAILURE, ok().lastEnding(failure).endingSeen(false));
            assertTrue(failure + " seen: the user knows, the state is still on", SnapcastClientRestore.decide(ok().lastEnding(failure).endingSeen(true)).restore());
        }
    }

    @Test
    public void endingsThatAreNoFailureNeverBlockTheRestore() {
        // the user stop clears the state itself, the rest are the system or the app ending the service: that is what restore is for
        for (String ending : new String[]{null, "", "NONE", "USER", "SYSTEM", "APP_EXIT", "DISABLED", "START_NOT_ALLOWED", "something unknown"}) {
            assertTrue(String.valueOf(ending), SnapcastClientRestore.decide(ok().lastEnding(ending).endingSeen(false)).restore());
        }
    }

    @Test
    public void whichEndingsAreFailures() {
        assertTrue(SnapcastClientRestore.isFailure("NO_AUDIO"));
        assertTrue(SnapcastClientRestore.isFailure("SERVICE_ERROR"));
        assertFalse(SnapcastClientRestore.isFailure("APP_EXIT"));
        assertFalse(SnapcastClientRestore.isFailure(null));
        assertFalse(SnapcastClientRestore.isFailure("nonsense"));
        // every ending is classified on purpose: a new one has to be decided here
        java.util.Set<String> failures = new java.util.HashSet<>(java.util.Arrays.asList("BAD_ADDRESS", "UNRESOLVED", "CONNECTION_GAVE_UP",
                "SERVER_REFUSED", "ENGINE_FAILED", "SERVICE_ERROR", "NO_AUDIO"));
        for (SnapcastClientRuntime.Ending ending : SnapcastClientRuntime.Ending.values()) {
            assertEquals(ending.name(), failures.contains(ending.name()), SnapcastClientRestore.isFailure(ending.name()));
        }
    }

    // ---- one attempt per process, one more when a screen starts after a refused start ------------------------------

    @Test
    public void thereIsOneAttemptInAProcess() {
        assertSkip(Skip.ALREADY_ATTEMPTED, ok().attempts(1));
        assertSkip(Skip.ALREADY_ATTEMPTED, new Facts(Point.SCREEN_START).switchedOn(true).optionEnabled(true).serverSet(true).attempts(1));
    }

    @Test
    public void aStartTheSystemRefusedIsTriedOnceMoreWhenAScreenStarts() {
        Facts screen = new Facts(Point.SCREEN_START).switchedOn(true).optionEnabled(true).serverSet(true).attempts(1).lastAttemptRefused(true);

        assertTrue(SnapcastClientRestore.decide(screen).restore());
    }

    @Test
    public void theSecondTryIsTheLastOne() {
        assertSkip(Skip.ALREADY_ATTEMPTED, new Facts(Point.SCREEN_START).switchedOn(true).optionEnabled(true).serverSet(true).attempts(2)
                .lastAttemptRefused(true));
        // and the process start never tries twice
        assertSkip(Skip.ALREADY_ATTEMPTED, ok().attempts(1).lastAttemptRefused(true));
    }

    // ---- which reason is named when several hold (a stable order for the log and the tests) --------------------------

    @Test
    public void theReasonsComeInAFixedOrder() {
        Facts nothing = new Facts(Point.PROCESS_START).active(true).exitHold(true).attempts(3).lastEnding("NO_AUDIO").endingSeen(false);
        assertSkip(Skip.NOT_SWITCHED_ON, nothing);
        assertSkip(Skip.OPTION_OFF, nothing.switchedOn(true));
        assertSkip(Skip.NO_SERVER, nothing.optionEnabled(true));
        assertSkip(Skip.EXIT_HOLD, nothing.serverSet(true));
        assertSkip(Skip.ALREADY_ACTIVE, nothing.exitHold(false));
        assertSkip(Skip.UNSEEN_FAILURE, nothing.active(false));
        assertSkip(Skip.ALREADY_ATTEMPTED, nothing.endingSeen(true));
    }
}
