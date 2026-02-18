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

import org.junit.Test;

public class FormatHelperTest {

    @Test
    public void testSecondsOnly() {
        assertEquals("00:00:30", FormatHelper.parseMillisToTimeStringTo(30_000));
    }

    @Test
    public void testMinutesAndSeconds() {
        assertEquals("00:03:45", FormatHelper.parseMillisToTimeStringTo(225_000));
    }

    @Test
    public void testHours() {
        assertEquals("01:30:00", FormatHelper.parseMillisToTimeStringTo(5_400_000));
    }

    @Test
    public void testZero() {
        assertEquals("00:00:00", FormatHelper.parseMillisToTimeStringTo(0));
    }

    @Test
    public void testLongDuration() {
        assertEquals("03:45:30", FormatHelper.parseMillisToTimeStringTo(13_530_000));
    }

    @Test
    public void testEdgeCases() {
        assertEquals("00:00:01", FormatHelper.parseMillisToTimeStringTo(1_000));
        assertEquals("00:01:00", FormatHelper.parseMillisToTimeStringTo(60_000));
        assertEquals("01:00:00", FormatHelper.parseMillisToTimeStringTo(3_600_000));
    }
}
