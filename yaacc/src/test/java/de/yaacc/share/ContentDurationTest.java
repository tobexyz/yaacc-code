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

import org.junit.Test;

public class ContentDurationTest {

    @Test
    public void aNormalDurationIsParsed() {
        assertEquals(215_000L, ContentDuration.parseMillis("215000"));
        assertEquals(215_000L, ContentDuration.parseMillis(" 215000 "));
    }

    @Test
    public void junkZeroNegativeAndHugeValuesAreUnknown() {
        assertEquals(-1L, ContentDuration.parseMillis(null));
        assertEquals(-1L, ContentDuration.parseMillis(""));
        assertEquals(-1L, ContentDuration.parseMillis("abc"));
        assertEquals(-1L, ContentDuration.parseMillis("0"));
        assertEquals(-1L, ContentDuration.parseMillis("-5"));
        assertEquals(-1L, ContentDuration.parseMillis("86400001"));
        assertEquals(-1L, ContentDuration.parseMillis("99999999999999999999"));
        assertEquals(ContentDuration.MAX_MILLIS, ContentDuration.parseMillis("86400000"));
    }
}
