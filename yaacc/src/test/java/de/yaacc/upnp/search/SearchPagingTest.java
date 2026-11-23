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
package de.yaacc.upnp.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A page over music, videos and images that are read one after the other. */
public class SearchPagingTest {

    @Test
    public void theFirstPageEndsInsideTheFirstSource() {
        long[][] slices = SearchPaging.slices(new long[]{5, 3, 2}, 0, 4);

        assertArrayEquals(new long[]{0, 4}, slices[0]);
        assertArrayEquals(new long[]{0, 0}, slices[1]);
        assertArrayEquals(new long[]{0, 0}, slices[2]);
    }

    @Test
    public void aPageThatRunsOverASourceBoundaryTakesTheRestOfOneAndTheStartOfTheNext() {
        long[][] slices = SearchPaging.slices(new long[]{5, 3, 2}, 3, 4);

        assertArrayEquals(new long[]{3, 2}, slices[0]);
        assertArrayEquals(new long[]{0, 2}, slices[1]);
        assertArrayEquals(new long[]{0, 0}, slices[2]);
    }

    @Test
    public void aPageBehindASourceSkipsItAndStartsInsideTheNext() {
        long[][] slices = SearchPaging.slices(new long[]{5, 3, 2}, 6, 10);

        assertArrayEquals(new long[]{5, 0}, slices[0]);
        assertArrayEquals(new long[]{1, 2}, slices[1]);
        assertArrayEquals(new long[]{0, 2}, slices[2]);
    }

    @Test
    public void noMaximumMeansEverythingFromTheStart() {
        long[][] slices = SearchPaging.slices(new long[]{2, 1}, 1, 0);

        assertArrayEquals(new long[]{1, 1}, slices[0]);
        assertArrayEquals(new long[]{0, 1}, slices[1]);
    }

    @Test
    public void aStartBehindTheEndIsAnEmptyPage() {
        long[][] slices = SearchPaging.slices(new long[]{2, 1}, 50, 10);

        assertEquals(0, slices[0][1]);
        assertEquals(0, slices[1][1]);
    }

    @Test
    public void theTotalIsTheSumOfTheMatches() {
        assertEquals(10, SearchPaging.total(new long[]{5, 3, 2}));
    }
}
