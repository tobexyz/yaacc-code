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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link ReceiverSelection}: the stored receiver id set is never modified in place
 * (it can be the object {@code SharedPreferences} keeps), and an empty set stays empty. Plain JVM.
 */
public class ReceiverSelectionTest {

    private static Set<String> setOf(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    public void copyIsEqualButNotTheSameObject() {
        Set<String> stored = setOf("a", "b");

        Set<String> copy = ReceiverSelection.copy(stored);

        assertEquals(stored, copy);
        assertNotSame(stored, copy);
    }

    @Test
    public void changingTheCopyDoesNotChangeTheStoredSet() {
        Set<String> stored = setOf("a", "b");

        Set<String> copy = ReceiverSelection.copy(stored);
        copy.remove("a");
        copy.add("c");

        assertEquals(setOf("a", "b"), stored);
    }

    @Test
    public void copyOfAnUnmodifiableSetIsModifiable() {
        Set<String> stored = Collections.unmodifiableSet(setOf("a"));

        Set<String> copy = ReceiverSelection.copy(stored);
        copy.add("b");

        assertEquals(setOf("a", "b"), copy);
    }

    @Test
    public void copyOfNullIsEmpty() {
        assertTrue(ReceiverSelection.copy(null).isEmpty());
    }

    @Test
    public void withoutRemovesOnlyTheUnknownIdsAndLeavesTheStoredSetAlone() {
        Set<String> stored = Collections.unmodifiableSet(setOf("a", "b", "c"));

        Set<String> result = ReceiverSelection.without(stored, Arrays.asList("b", "x"));

        assertEquals(setOf("a", "c"), result);
        assertEquals(setOf("a", "b", "c"), stored);
    }

    @Test
    public void withoutNothingKeepsEverything() {
        assertEquals(setOf("a"), ReceiverSelection.without(setOf("a"), Collections.<String>emptyList()));
        assertEquals(setOf("a"), ReceiverSelection.without(setOf("a"), null));
    }

    @Test
    public void anEmptyStoredSetStaysEmpty() {
        Set<String> result = ReceiverSelection.without(new HashSet<String>(), Arrays.asList("a"));

        assertTrue(result.isEmpty());
    }

    @Test
    public void removingEverythingGivesAnEmptyModifiableSet() {
        Set<String> result = ReceiverSelection.without(setOf("a"), Arrays.asList("a"));

        assertTrue(result.isEmpty());
        result.add("b");
        assertEquals(setOf("b"), result);
    }
}
