/*
 *
 * Copyright (C) 2025 Tobias Schoene www.yaacc.de
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

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HttpRangeTest {

    @Test
    public void testRanges() throws Exception {
        String input = "bytes=1-22,-3343,50-,-5785,63-10012";
        List<HttpRange> expected = new ArrayList<>();
        expected.add(new HttpRange("bytes", 1, 22, null));
        expected.add(new HttpRange("bytes", null, null, 3343));
        expected.add(new HttpRange("bytes", 50, null, null));
        expected.add(new HttpRange("bytes", null, null, 5785));
        expected.add(new HttpRange("bytes", 63, 10012, null));
        List<HttpRange> calculated = HttpRange.parseRangeHeader(input);
        assertEquals(expected, calculated);
        assertEquals(input, HttpRange.toHeaderString(calculated));
        input = "bytes=1-22";
        calculated = HttpRange.parseRangeHeader(input);
        expected = new ArrayList<>();
        expected.add(new HttpRange("bytes", 1, 22, null));
        assertEquals(expected, calculated);
        assertEquals(input, HttpRange.toHeaderString(calculated));
        input = "bytes=-1234";
        calculated = HttpRange.parseRangeHeader(input);
        expected = new ArrayList<>();
        expected.add(new HttpRange("bytes", null, null, 1234));
        assertEquals(expected, calculated);
        assertEquals(input, HttpRange.toHeaderString(calculated));
        input = null;
        calculated = HttpRange.parseRangeHeader(input);
        expected = new ArrayList<>();
        assertEquals(expected, calculated);
        assertEquals(input, HttpRange.toHeaderString(calculated));
        input = "";
        calculated = HttpRange.parseRangeHeader(input);
        expected = new ArrayList<>();
        assertEquals(expected, calculated);
        assertEquals(null, HttpRange.toHeaderString(calculated));
    }
}
