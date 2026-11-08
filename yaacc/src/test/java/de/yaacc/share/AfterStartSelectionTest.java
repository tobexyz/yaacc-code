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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link AfterStartSelection}: what happens to the receiver selection once a picked
 * action has started. The new layout clears it (the selection is per action); classic restores the
 * selection it had before a shared file opened the picker. Whether classic restores or clears is
 * the single constant {@link AfterStartSelection#CLASSIC_MODE}. Plain JVM.
 */
public class AfterStartSelectionTest {

    private static Set<String> ids(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // ---- which mode a layout uses

    @Test
    public void theNewLayoutClears() {
        assertEquals(AfterStartSelection.Mode.CLEAR, AfterStartSelection.modeFor(true));
    }

    @Test
    public void classicUsesTheOneNamedConstant() {
        assertEquals(AfterStartSelection.CLASSIC_MODE, AfterStartSelection.modeFor(false));
    }

    /**
     * Pins the owner decision of 2026-10-25 (classic restores the previous selection). The owner may
     * switch to clearing later: change {@code CLASSIC_MODE} and this one assertion, nothing else.
     */
    @Test
    public void classicRestoresThePreviousSelectionAsDecidedOn20261025() {
        assertEquals(AfterStartSelection.Mode.RESTORE_PREVIOUS, AfterStartSelection.CLASSIC_MODE);
    }

    // ---- the plan

    @Test
    public void clearModeClearsAndRestoresNothing() {
        AfterStartSelection.Plan plan = AfterStartSelection.plan(AfterStartSelection.Mode.CLEAR, ids("a", "b"));

        assertTrue(plan.clear());
        assertTrue(plan.restoreTo().isEmpty());
    }

    @Test
    public void restoreModeRestoresExactlyThePreviousIdsAndDoesNotClear() {
        AfterStartSelection.Plan plan =
                AfterStartSelection.plan(AfterStartSelection.Mode.RESTORE_PREVIOUS, ids("a", "b"));

        assertFalse(plan.clear());
        assertEquals(ids("a", "b"), plan.restoreTo());
    }

    @Test
    public void theRestoredSetIsACopyThatLaterChangesDoNotReach() {
        Set<String> previous = ids("a");
        AfterStartSelection.Plan plan = AfterStartSelection.plan(AfterStartSelection.Mode.RESTORE_PREVIOUS, previous);

        previous.add("late");

        assertEquals(ids("a"), plan.restoreTo());
    }

    @Test
    public void theRestoredSetIsUnmodifiable() {
        AfterStartSelection.Plan plan =
                AfterStartSelection.plan(AfterStartSelection.Mode.RESTORE_PREVIOUS, ids("a"));

        try {
            plan.restoreTo().add("x");
            fail("expected an unmodifiable set");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void restoringANullOrEmptySelectionRestoresAnEmptyOne() {
        AfterStartSelection.Plan fromNull = AfterStartSelection.plan(AfterStartSelection.Mode.RESTORE_PREVIOUS, null);
        AfterStartSelection.Plan fromEmpty = AfterStartSelection.plan(AfterStartSelection.Mode.RESTORE_PREVIOUS,
                Collections.<String>emptySet());

        assertFalse(fromNull.clear());
        assertTrue(fromNull.restoreTo().isEmpty());
        assertFalse(fromEmpty.clear());
        assertTrue(fromEmpty.restoreTo().isEmpty());
    }

    @Test
    public void nullAndEmptyIdsInThePreviousSelectionAreDropped() {
        Set<String> previous = new LinkedHashSet<>();
        previous.add(null);
        previous.add("");
        previous.add("a");

        AfterStartSelection.Plan plan = AfterStartSelection.plan(AfterStartSelection.Mode.RESTORE_PREVIOUS, previous);

        assertEquals(ids("a"), plan.restoreTo());
    }

    @Test
    public void aNullModeClears() {
        // fail safe: an unknown mode must not leave a picked selection behind
        AfterStartSelection.Plan plan = AfterStartSelection.plan(null, ids("a"));

        assertTrue(plan.clear());
        assertTrue(plan.restoreTo().isEmpty());
    }
}
