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
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ActionFlight} and {@link GuardedPickedAction}: the single-flight rule that
 * keeps two picked actions from sharing the (global) receiver selection. From a confirm until its
 * action is done no second action may start, so one action can never read, replace or clear what
 * another one stored. Plain JVM.
 */
public class ActionFlightTest {

    private long now;
    private ActionFlight flight;

    @Before
    public void setUp() {
        now = 1_000L;
        flight = new ActionFlight(() -> now, 60_000L);
    }

    private static Set<String> ids(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // ---- the flight

    @Test
    public void aFreshFlightIsIdleAndTheFirstActionMayBegin() {
        assertFalse(flight.isActive());
        assertNotNull(flight.tryBegin());
        assertTrue(flight.isActive());
    }

    @Test
    public void aSecondActionIsRefusedWhileOneIsInFlight() {
        ActionFlight.Ticket first = flight.tryBegin();

        assertNull(flight.tryBegin());
        assertTrue(flight.isActive());
        first.end();
    }

    @Test
    public void afterTheFirstEndedASecondMayBegin() {
        flight.tryBegin().end();

        assertFalse(flight.isActive());
        assertNotNull(flight.tryBegin());
    }

    @Test
    public void endingTwiceIsHarmless() {
        ActionFlight.Ticket first = flight.tryBegin();
        first.end();
        first.end();

        assertFalse(flight.isActive());
    }

    @Test
    public void aLateEndOfAnOldTicketDoesNotEndTheNewFlight() {
        ActionFlight.Ticket old = flight.tryBegin();
        now += 60_000L; // the watchdog gave up on it
        ActionFlight.Ticket fresh = flight.tryBegin();
        assertNotNull(fresh);

        old.end(); // the stuck action finally returns

        assertTrue(flight.isActive());
        assertNull(flight.tryBegin());
        fresh.end();
        assertFalse(flight.isActive());
    }

    @Test
    public void aFlightThatNeverEndsExpiresSoNothingIsBlockedForGood() {
        flight.tryBegin();

        now += 59_999L;
        assertTrue(flight.isActive());
        assertNull(flight.tryBegin());
        now += 1L;
        assertFalse(flight.isActive());
        assertNotNull(flight.tryBegin());
    }

    @Test
    public void aClockThatRunsBackwardsCountsAsExpired() {
        flight.tryBegin();
        now -= 5_000L;

        assertFalse(flight.isActive());
    }

    @Test
    public void theDefaultFlightIsIdleAndSingle() {
        ActionFlight real = ActionFlight.createDefault();

        ActionFlight.Ticket ticket = real.tryBegin();
        assertNotNull(ticket);
        assertNull(real.tryBegin());
        ticket.end();
    }

    @Test
    public void aNonPositiveMaximumIsRefused() {
        try {
            new ActionFlight(() -> 0L, 0L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- the guarded action

    /** Records what the action owner was told. */
    private static final class Recorder implements PickedAction {
        final List<Set<String>> confirmed = new ArrayList<>();
        final List<Runnable> done = new ArrayList<>();
        int cancelled;

        @Override
        public void onConfirmed(Set<String> receiverIds, Runnable doneCallback) {
            confirmed.add(receiverIds);
            done.add(doneCallback);
        }

        @Override
        public void onCancelled() {
            cancelled++;
        }
    }

    @Test
    public void aConfirmBeginsTheFlightAndHandsOverADoneCallback() {
        Recorder action = new Recorder();

        new GuardedPickedAction(flight, action).onConfirmed(ids("a"));

        assertEquals(1, action.confirmed.size());
        assertTrue(flight.isActive());
        action.done.get(0).run();
        assertFalse(flight.isActive());
    }

    @Test
    public void twoInterleavedActionsCannotBothRun() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        GuardedPickedAction guardedFirst = new GuardedPickedAction(flight, first);
        GuardedPickedAction guardedSecond = new GuardedPickedAction(flight, second);

        guardedFirst.onConfirmed(ids("tv"));
        guardedSecond.onConfirmed(ids("phone")); // confirmed while the first is still starting

        assertEquals(1, first.confirmed.size());
        assertEquals(0, second.confirmed.size());
        assertEquals(1, second.cancelled);
        assertEquals(ids("tv"), first.confirmed.get(0));
    }

    @Test
    public void theRefusedSecondActionRunsOnceTheFirstIsDone() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        new GuardedPickedAction(flight, first).onConfirmed(ids("tv"));
        new GuardedPickedAction(flight, second).onConfirmed(ids("phone"));
        first.done.get(0).run();

        new GuardedPickedAction(flight, second).onConfirmed(ids("phone"));

        assertEquals(ids("phone"), second.confirmed.get(0));
    }

    @Test
    public void doneCalledTwiceDoesNotEndTheNextAction() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        new GuardedPickedAction(flight, first).onConfirmed(ids("tv"));
        Runnable firstDone = first.done.get(0);
        firstDone.run();
        new GuardedPickedAction(flight, second).onConfirmed(ids("phone"));

        firstDone.run(); // a second call from the first action

        assertTrue(flight.isActive());
    }

    @Test
    public void aCancelDoesNotBeginAFlight() {
        Recorder action = new Recorder();

        new GuardedPickedAction(flight, action).onCancelled();

        assertEquals(1, action.cancelled);
        assertFalse(flight.isActive());
    }

    @Test
    public void aThrowingActionEndsItsFlightAndTheExceptionReachesTheCaller() {
        PickedAction boom = new PickedAction() {
            @Override
            public void onConfirmed(Set<String> receiverIds, Runnable done) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onCancelled() {
            }
        };

        try {
            new GuardedPickedAction(flight, boom).onConfirmed(ids("a"));
            fail("expected the exception");
        } catch (IllegalStateException expected) {
            // ok
        }

        assertFalse(flight.isActive());
    }
}
