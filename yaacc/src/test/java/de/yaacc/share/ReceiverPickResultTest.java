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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link ReceiverPickResult}: what the receiver picker does with the answer.
 * A confirmed pick starts and then clears the selection; a cancelled one changes nothing.
 * Plain JVM.
 */
public class ReceiverPickResultTest {

    private static Set<String> ids(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // ---- confirm with receivers

    @Test
    public void confirmWithOneReceiverStartsPlayback() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, ids("uuid-1"));

        assertTrue(decision.startPlayback());
    }

    @Test
    public void confirmWithOneReceiverClearsSelectionAfterStart() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, ids("uuid-1"));

        assertTrue(decision.clearSelectionAfterStart());
    }

    @Test
    public void confirmWithOneReceiverUsesExactlyThatReceiver() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, ids("uuid-1"));

        assertEquals(ids("uuid-1"), decision.receiversToUse());
    }

    @Test
    public void confirmWithSeveralReceiversUsesAllOfThem() {
        ReceiverPickResult.Decision decision =
                ReceiverPickResult.decide(true, ids("uuid-1", "uuid-2", "uuid-3"));

        assertTrue(decision.startPlayback());
        assertEquals(ids("uuid-1", "uuid-2", "uuid-3"), decision.receiversToUse());
    }

    @Test
    public void confirmWithReceiversClosesThePicker() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, ids("uuid-1"));

        assertFalse(decision.keepPickerOpen());
    }

    // ---- confirm without receivers

    @Test
    public void confirmWithEmptySetDoesNotStartPlayback() {
        ReceiverPickResult.Decision decision =
                ReceiverPickResult.decide(true, Collections.<String>emptySet());

        assertFalse(decision.startPlayback());
    }

    @Test
    public void confirmWithEmptySetDoesNotClearSelection() {
        ReceiverPickResult.Decision decision =
                ReceiverPickResult.decide(true, Collections.<String>emptySet());

        assertFalse(decision.clearSelectionAfterStart());
    }

    @Test
    public void confirmWithEmptySetKeepsThePickerOpenSoTheUserCanChoose() {
        ReceiverPickResult.Decision decision =
                ReceiverPickResult.decide(true, Collections.<String>emptySet());

        assertTrue(decision.keepPickerOpen());
    }

    @Test
    public void confirmWithNullSetBehavesLikeEmptySet() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, null);

        assertFalse(decision.startPlayback());
        assertFalse(decision.clearSelectionAfterStart());
        assertTrue(decision.keepPickerOpen());
        assertNotNull(decision.receiversToUse());
        assertTrue(decision.receiversToUse().isEmpty());
    }

    @Test
    public void confirmWithOnlyNullOrEmptyIdsCountsAsNoReceiver() {
        Set<String> junk = new HashSet<>(Arrays.asList(null, ""));

        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, junk);

        assertFalse(decision.startPlayback());
        assertTrue(decision.keepPickerOpen());
        assertTrue(decision.receiversToUse().isEmpty());
    }

    @Test
    public void nullAndEmptyIdsAreDroppedButRealOnesStay() {
        Set<String> mixed = new HashSet<>(Arrays.asList(null, "", "uuid-1"));

        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, mixed);

        assertTrue(decision.startPlayback());
        assertEquals(ids("uuid-1"), decision.receiversToUse());
    }

    // ---- cancel

    @Test
    public void cancelDoesNotStartPlayback() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(false, ids("uuid-1"));

        assertFalse(decision.startPlayback());
    }

    @Test
    public void cancelDoesNotClearTheSelection() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(false, ids("uuid-1"));

        assertFalse(decision.clearSelectionAfterStart());
    }

    @Test
    public void cancelClosesThePicker() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(false, ids("uuid-1"));

        assertFalse(decision.keepPickerOpen());
    }

    @Test
    public void cancelNeverCarriesTheTickedReceivers() {
        ReceiverPickResult.Decision decision =
                ReceiverPickResult.decide(false, ids("uuid-1", "uuid-2"));

        assertNotNull(decision.receiversToUse());
        assertTrue(decision.receiversToUse().isEmpty());
    }

    @Test
    public void cancelWithNullSetDoesNotThrowAndChangesNothing() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(false, null);

        assertFalse(decision.startPlayback());
        assertFalse(decision.clearSelectionAfterStart());
        assertFalse(decision.keepPickerOpen());
        assertTrue(decision.receiversToUse().isEmpty());
    }

    // ---- the returned set

    @Test
    public void returnedSetIsUnmodifiable() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, ids("uuid-1"));

        try {
            decision.receiversToUse().add("uuid-2");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void returnedSetIsACopyOfTheInput() {
        Set<String> input = ids("uuid-1");
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, input);

        input.add("uuid-2");
        input.remove("uuid-1");

        assertEquals(ids("uuid-1"), decision.receiversToUse());
    }

    @Test
    public void emptyResultSetIsAlsoUnmodifiable() {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(false, ids("uuid-1"));

        try {
            decision.receiversToUse().add("uuid-2");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
