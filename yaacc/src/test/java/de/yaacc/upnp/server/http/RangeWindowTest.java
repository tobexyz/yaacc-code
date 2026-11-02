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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.util.HttpRange;

/** {@link RangeWindow}: which bytes a parsed {@code Range} entry selects, edge cases included. */
public class RangeWindowTest {

    private static RangeWindow window(String header, long size) {
        return RangeWindow.resolve(HttpRange.parseRangeHeader(header).get(0), size);
    }

    private static void assertWindow(RangeWindow window, long start, long length) {
        assertTrue(window.isSatisfiable());
        assertEquals(start, window.start());
        assertEquals(length, window.length());
    }

    @Test
    public void noRangeIsTheWholeResource() {
        assertWindow(RangeWindow.resolve(null, 1000), 0, 1000);
    }

    @Test
    public void closedRange() {
        assertWindow(window("bytes=100-199", 1000), 100, 100);
    }

    @Test
    public void openEndRunsToTheLastByte() {
        assertWindow(window("bytes=900-", 1000), 900, 100);
    }

    @Test
    public void endOfZeroIsTheFirstByteNotNoEnd() {
        assertWindow(window("bytes=0-0", 1000), 0, 1);
    }

    @Test
    public void endBeyondTheResourceIsClipped() {
        assertWindow(window("bytes=900-5000", 1000), 900, 100);
    }

    @Test
    public void lastByteAndSingleByteAtTheEnd() {
        assertWindow(window("bytes=999-999", 1000), 999, 1);
        assertWindow(window("bytes=0-999", 1000), 0, 1000);
    }

    @Test
    public void suffixIsTheTailAndIsClippedToTheResource() {
        assertWindow(window("bytes=-100", 1000), 900, 100);
        assertWindow(window("bytes=-5000", 1000), 0, 1000);
    }

    @Test
    public void rangesThatSelectNothingAreNotSatisfiableWithLengthZero() {
        for (String header : new String[]{"bytes=1000-", "bytes=1000-1999", "bytes=5000-", "bytes=500-100", "bytes=-0"}) {
            RangeWindow window = window(header, 1000);
            assertFalse(header, window.isSatisfiable());
            assertEquals(header, 0, window.length());
        }
    }

    @Test
    public void anyRangeOfAnEmptyResourceIsNotSatisfiable() {
        assertFalse(window("bytes=0-", 0).isSatisfiable());
        assertFalse(window("bytes=-5", 0).isSatisfiable());
        assertFalse(window("bytes=0-0", 0).isSatisfiable());
    }

    @Test
    public void contentRangeNamesTheBytesSent() {
        assertEquals("bytes 100-199/1000", window("bytes=100-199", 1000).contentRange(1000));
        assertEquals("bytes 900-999/1000", window("bytes=900-5000", 1000).contentRange(1000));
        assertEquals("bytes 900-999/1000", window("bytes=-100", 1000).contentRange(1000));
        assertEquals("bytes 0-0/1000", window("bytes=0-0", 1000).contentRange(1000));
    }
}
