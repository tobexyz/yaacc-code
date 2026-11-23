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

/**
 * One page of a search over several sources (music, videos, images in this order) that are queried one after the other: from the
 * number of matches of every source, which part of each source belongs to the page that starts at {@code first} and holds at most
 * {@code max} results. Pure Java.
 */
public final class SearchPaging {

    private SearchPaging() {
    }

    /**
     * @param counts the number of matches of every source, in the order of the results
     * @param first  the index of the first result of the page (0 based)
     * @param max    the most results of the page; 0 or less means all
     * @return for every source {offset in the source, number to take}; the number is 0 for a source that is not on the page
     */
    public static long[][] slices(long[] counts, long first, long max) {
        long[][] result = new long[counts.length][2];
        long skip = Math.max(0L, first);
        long room = max <= 0L ? Long.MAX_VALUE : max;
        for (int i = 0; i < counts.length; i++) {
            long inSource = Math.max(0L, counts[i]);
            long offset = Math.min(skip, inSource);
            long take = Math.min(room, inSource - offset);
            result[i][0] = offset;
            result[i][1] = take;
            skip -= offset;
            room -= take;
            if (room <= 0L) {
                break;
            }
        }
        return result;
    }

    /** @return the sum of the counts, the TotalMatches of the search */
    public static long total(long[] counts) {
        long sum = 0L;
        for (long count : counts) {
            sum += Math.max(0L, count);
        }
        return sum;
    }
}
