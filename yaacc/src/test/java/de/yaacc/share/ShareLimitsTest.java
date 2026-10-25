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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pins the lifetime constants of the shares to the values the spec names. */
public class ShareLimitsTest {

    @Test
    public void theIdleGraceIsTenMinutesAndTheAbsoluteMaximumSixHours() {
        assertEquals(10L * 60L * 1000L, ShareLimits.IDLE_GRACE_MILLIS);
        assertEquals(6L * 60L * 60L * 1000L, ShareLimits.ABSOLUTE_MAX_MILLIS);
    }

    @Test
    public void theGraceIsShorterThanTheAbsoluteMaximumAndTheSweepRunsOftenEnough() {
        assertTrue(ShareLimits.IDLE_GRACE_MILLIS < ShareLimits.ABSOLUTE_MAX_MILLIS);
        assertEquals(60_000L, ShareLimits.SWEEP_INTERVAL_MILLIS);
        assertTrue(ShareLimits.SWEEP_INTERVAL_MILLIS < ShareLimits.IDLE_GRACE_MILLIS);
    }

    @Test
    public void theChunkSizeIsSixtyFourKiB() {
        assertEquals(64 * 1024, ShareLimits.CHUNK_BYTES);
        assertEquals(ShareLimits.CHUNK_BYTES, RangeStreamer.DEFAULT_CHUNK_BYTES);
    }
}
