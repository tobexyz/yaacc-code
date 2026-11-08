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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SafShortIdsTest {

    @Test
    public void idsCountUpFromOneAndAreStablePerUri() {
        SafShortIds ids = new SafShortIds();
        assertEquals("1", ids.getOrCreate("content://a"));
        assertEquals("2", ids.getOrCreate("content://b"));
        assertEquals("1", ids.getOrCreate("content://a"));
        assertEquals(3, ids.counter());
        assertEquals("content://b", ids.uriFor("2"));
        assertEquals("1", ids.existing("content://a"));
        assertNull(ids.existing("content://c"));
        assertNull(ids.uriFor("9"));
        assertNull(ids.uriFor(null));
    }

    @Test
    public void restoredIdsResolveAndMoveTheCounterBehindThem() {
        SafShortIds ids = new SafShortIds();
        ids.restore("7", "content://seven");
        ids.restore("3", "content://three");
        assertEquals("content://seven", ids.uriFor("7"));
        assertEquals("7", ids.existing("content://seven"));
        assertEquals("8", ids.getOrCreate("content://new"));
    }

    @Test
    public void theStoredCounterNeverMovesBack() {
        SafShortIds ids = new SafShortIds();
        ids.restore("10", "content://x");
        ids.restoreCounter(5);
        assertEquals(11, ids.counter());
        ids.restoreCounter(40);
        assertEquals("40", ids.getOrCreate("content://y"));
    }

    @Test
    public void nonNumericRestoredIdsDoNotTouchTheCounter() {
        SafShortIds ids = new SafShortIds();
        ids.restore("abc", "content://x");
        assertEquals("content://x", ids.uriFor("abc"));
        assertEquals(1, ids.counter());
        ids.restore(null, "content://y");
        ids.restore("5", null);
        assertEquals(1, ids.size());
    }

    @Test
    public void transientIdsResolveButNeverCollideWithOrUseTheCounter() {
        SafShortIds ids = new SafShortIds();
        String transientId = ids.getOrCreateTransient("content://a");
        assertEquals(transientId, ids.getOrCreateTransient("content://a"));
        assertEquals("content://a", ids.uriFor(transientId));
        assertNotEquals(transientId, ids.getOrCreateTransient("content://b"));
        assertEquals("the counter is untouched", 1, ids.counter());
        assertNull("a transient id is not a persistent one", ids.existing("content://a"));
        assertEquals("1", ids.getOrCreate("content://a"));
    }

    @Test
    public void resetForgetsEverythingAndStartsAtOneAgain() {
        SafShortIds ids = new SafShortIds();
        ids.getOrCreate("content://a");
        ids.getOrCreateTransient("content://b");
        ids.reset();
        assertEquals(0, ids.size());
        assertEquals(1, ids.counter());
        assertEquals("1", ids.getOrCreate("content://c"));
    }
}
