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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link RendererStateMap}: bounded, evicting, never created from invalid ids. */
public class RendererStateMapTest {

    @Test
    public void createStoresTheStateWithTheInitialPosition() {
        RendererStateMap map = new RendererStateMap(8);
        RendererStateMap.State state = map.create("dev-1", 4_000L);
        assertNotNull(state);
        assertEquals(4_000L, state.getTimePositionMs());
        assertFalse(state.isPaused());
        assertEquals(0L, state.getTotalDurationMs());
        assertEquals(0L, state.getLastBytePosition());
        assertSame(state, map.get("dev-1"));
        assertEquals(1, map.size());
    }

    @Test
    public void createOfAnExistingIdReturnsTheSameState() {
        RendererStateMap map = new RendererStateMap(8);
        RendererStateMap.State first = map.create("dev-1", 1L);
        assertSame(first, map.create("dev-1", 99L));
        assertEquals(1L, first.getTimePositionMs());
        assertEquals(1, map.size());
    }

    @Test
    public void getOfAnUnknownIdIsNullAndCreatesNothing() {
        RendererStateMap map = new RendererStateMap(8);
        assertNull(map.get("dev-1"));
        assertEquals(0, map.size());
    }

    @Test
    public void invalidIdsCreateNoState() {
        RendererStateMap map = new RendererStateMap(8);
        assertNull(map.create(null, 0L));
        assertNull(map.create("", 0L));
        assertNull(map.create("a/b", 0L));
        assertNull(map.create("../x", 0L));
        assertNull(map.get("a/b"));
        assertFalse(map.update("a/b", 1L, false));
        assertEquals(0, map.size());
    }

    @Test
    public void updateChangesOnlyAnExistingState() {
        RendererStateMap map = new RendererStateMap(8);
        assertFalse(map.update("dev-1", 5_000L, true));
        assertEquals(0, map.size());
        RendererStateMap.State state = map.create("dev-1", 0L);
        assertTrue(map.update("dev-1", 5_000L, true));
        assertEquals(5_000L, state.getTimePositionMs());
        assertTrue(state.isPaused());
    }

    @Test
    public void theMapNeverGrowsBeyondItsCapAndEvictsTheLeastRecentlyUsed() {
        RendererStateMap map = new RendererStateMap(3);
        map.create("a", 0L);
        map.create("b", 0L);
        map.create("c", 0L);
        map.get("a"); // a is now the most recently used
        map.create("d", 0L);
        assertEquals(3, map.size());
        assertNotNull(map.get("a"));
        assertNull("b was the least recently used", map.get("b"));
        assertNotNull(map.get("c"));
        assertNotNull(map.get("d"));
    }

    @Test
    public void manyDifferentIdsStayWithinTheCap() {
        RendererStateMap map = new RendererStateMap(RendererStateMap.DEFAULT_MAX);
        for (int i = 0; i < 10_000; i++) {
            map.create("device-" + i, i);
            assertTrue(map.size() <= RendererStateMap.DEFAULT_MAX);
        }
        assertEquals(RendererStateMap.DEFAULT_MAX, map.size());
    }

    @Test
    public void theDefaultCapIsEight() {
        assertEquals(8, RendererStateMap.DEFAULT_MAX);
    }

    @Test
    public void clearEmptiesTheMap() {
        RendererStateMap map = new RendererStateMap(8);
        map.create("a", 0L);
        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get("a"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNonPositiveCapIsRejected() {
        new RendererStateMap(0);
    }

    @Test
    public void aStateCanBeChangedThroughItsSetters() {
        RendererStateMap.State state = new RendererStateMap(2).create("a", 0L);
        state.setTotalDurationMs(60_000L);
        state.setLastBytePosition(123L);
        assertEquals(60_000L, state.getTotalDurationMs());
        assertEquals(123L, state.getLastBytePosition());
    }
}
