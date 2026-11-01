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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemUpdateIdsTest {

    @Test
    public void theStartValueIsTheSecondsSinceTheEpoch() {
        assertEquals(1_793_000_000L, SystemUpdateIds.startValue(1_793_000_000_999L));
    }

    @Test
    public void aLaterStartGivesAHigherValue() {
        assertTrue(SystemUpdateIds.startValue(1_793_000_061_000L) > SystemUpdateIds.startValue(1_793_000_000_000L));
    }

    @Test
    public void startsInTheSameSecondGiveTheSameValue() {
        assertEquals(SystemUpdateIds.startValue(5_000L), SystemUpdateIds.startValue(5_999L));
    }

    @Test
    public void theValueIsAtLeastOne() {
        assertEquals(1L, SystemUpdateIds.startValue(0L));
        assertEquals(1L, SystemUpdateIds.startValue(999L));
    }

    @Test
    public void theValueFitsIntoAnUnsignedFourByteNumber() {
        long far = 4_294_967_295L * 1000L + 5_000L; // past the year 2106
        long value = SystemUpdateIds.startValue(far);
        assertTrue(value >= 1L && value <= 0xFFFFFFFFL);
    }
}
