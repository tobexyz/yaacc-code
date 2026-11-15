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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tasks 12.2 and 12.9: the steps of the exit run in the order they were added (players, then services, then notifications, then the
 * end of the process), and one step that throws does not keep the later ones from running, least of all the end.
 */
public class ExitSequenceTest {

    @Test
    public void stepsRunInTheOrderTheyWereAdded() {
        List<String> log = new ArrayList<>();
        ExitSequence.Result result = new ExitSequence()
                .add("a", () -> log.add("a"))
                .add("b", () -> log.add("b"))
                .add("c", () -> log.add("c"))
                .run();
        assertEquals(Arrays.asList("a", "b", "c"), log);
        assertEquals(Arrays.asList("a", "b", "c"), result.ran());
        assertTrue(result.allSucceeded());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    public void aStepThatThrowsDoesNotStopTheLaterOnes() {
        List<String> log = new ArrayList<>();
        ExitSequence.Result result = new ExitSequence()
                .add(ExitSequence.STEP_PLAYERS, () -> {
                    log.add("players");
                    throw new IllegalStateException("a player that does not release");
                })
                .add(ExitSequence.STEP_SERVICES, () -> log.add("services"))
                .add(ExitSequence.STEP_NOTIFICATIONS, () -> log.add("notifications"))
                .add(ExitSequence.STEP_END, () -> log.add("end"))
                .run();
        assertEquals(Arrays.asList("players", "services", "notifications", "end"), log);
        assertEquals(Arrays.asList(ExitSequence.STEP_PLAYERS), result.failed());
        assertFalse(result.allSucceeded());
    }

    @Test
    public void everyKindOfThrowableIsContainedAnErrorToo() {
        List<String> log = new ArrayList<>();
        ExitSequence.Result result = new ExitSequence()
                .add("checked", () -> {
                    throw new Exception("checked");
                })
                .add("error", () -> {
                    throw new AssertionError("an error from a library");
                })
                .add("last", () -> log.add("last"))
                .run();
        assertEquals(Arrays.asList("last"), log);
        assertEquals(Arrays.asList("checked", "error"), result.failed());
    }

    @Test
    public void theSameNameTwiceIsKeptTwice() {
        List<String> log = new ArrayList<>();
        new ExitSequence()
                .add("player", () -> log.add("1"))
                .add("player", () -> log.add("2"))
                .run();
        assertEquals(Arrays.asList("1", "2"), log);
    }

    @Test
    public void aSequenceWithoutStepsIsFine() {
        assertTrue(new ExitSequence().run().allSucceeded());
    }

    @Test
    public void aNullStepIsSkippedAndReportedAsFailed() {
        List<String> log = new ArrayList<>();
        ExitSequence.Result result = new ExitSequence().add("nothing", null).add("x", () -> log.add("x")).run();
        assertEquals(Arrays.asList("x"), log);
        assertEquals(Arrays.asList("nothing"), result.failed());
    }

    // ---- the wait for the services and the feeds to end ---------------------------------------------------------------

    private static final long MIN = 100;
    private static final long MAX = 2_000;

    @Test
    public void weWaitWhileSomethingRunsAndTheTimeIsNotUp() {
        assertTrue(ExitSequence.shouldKeepWaiting(true, 0, MIN, MAX));
        assertTrue(ExitSequence.shouldKeepWaiting(true, 1_999, MIN, MAX));
    }

    @Test
    public void weStopWaitingWhenNothingRunsAnyMoreAfterTheMinimumLook() {
        assertFalse(ExitSequence.shouldKeepWaiting(false, MIN, MIN, MAX));
        assertFalse(ExitSequence.shouldKeepWaiting(false, 1_000, MIN, MAX));
    }

    @Test
    public void weAlwaysGiveTheMainThreadTheMinimumTimeEvenWhenNothingRuns() {
        // the posted releases and the second cancelAll() get their turn before the process ends
        assertTrue(ExitSequence.shouldKeepWaiting(false, 0, MIN, MAX));
        assertTrue(ExitSequence.shouldKeepWaiting(false, MIN - 1, MIN, MAX));
    }

    @Test
    public void weStopWaitingWhenTheTimeIsUpEvenIfSomethingRuns() {
        assertFalse(ExitSequence.shouldKeepWaiting(true, 2_000, MIN, MAX));
        assertFalse(ExitSequence.shouldKeepWaiting(true, 60_000, MIN, MAX));
    }
}
