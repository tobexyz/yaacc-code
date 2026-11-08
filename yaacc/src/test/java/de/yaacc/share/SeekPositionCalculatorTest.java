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

/**
 * Tests for {@link SeekPositionCalculator}: the time to byte estimate of the server-side time
 * range management. The numbers are those of the original implementation
 * (first 10 percent of the file are "header", never past 85 percent of the file).
 */
public class SeekPositionCalculatorTest {

    @Test
    public void halfTheDurationIsHalfOfTheDataAfterTheHeader() {
        // header 100, data 900: 100 + 450
        assertEquals(550L, SeekPositionCalculator.bytePositionForTime(1000L, 50_000L, 100_000L));
    }

    @Test
    public void timeZeroIsTheHeaderSize() {
        assertEquals(100L, SeekPositionCalculator.bytePositionForTime(1000L, 0L, 100_000L));
    }

    @Test
    public void thePositionIsCappedAtEightyFivePercent() {
        assertEquals(850L, SeekPositionCalculator.bytePositionForTime(1000L, 100_000L, 100_000L));
        assertEquals(850L, SeekPositionCalculator.bytePositionForTime(1000L, 900_000L, 100_000L));
    }

    @Test
    public void anUnknownSizeOrDurationGivesZero() {
        assertEquals(0L, SeekPositionCalculator.bytePositionForTime(0L, 1000L, 100_000L));
        assertEquals(0L, SeekPositionCalculator.bytePositionForTime(-1L, 1000L, 100_000L));
        assertEquals(0L, SeekPositionCalculator.bytePositionForTime(1000L, 1000L, 0L));
        assertEquals(0L, SeekPositionCalculator.bytePositionForTime(1000L, 1000L, -5L));
    }

    @Test
    public void aNegativeTimeNeverGivesANegativePosition() {
        assertTrue(SeekPositionCalculator.bytePositionForTime(1000L, -1_000_000L, 100_000L) >= 0L);
        assertEquals(0L, SeekPositionCalculator.bytePositionForTime(1000L, Long.MIN_VALUE, 1L));
    }

    @Test
    public void hugeValuesDoNotOverflowAndStayWithinTheCap() {
        long size = Long.MAX_VALUE;
        long cap = (long) (size * 0.85);
        long position = SeekPositionCalculator.bytePositionForTime(size, Long.MAX_VALUE, 1L);
        assertEquals(cap, position);
        position = SeekPositionCalculator.bytePositionForTime(size, 1L, Long.MAX_VALUE);
        assertTrue(position >= 0L && position <= cap);
    }

    @Test
    public void theResultIsAlwaysInsideTheFile() {
        for (long size : new long[]{1L, 10L, 12_345_678L, Long.MAX_VALUE}) {
            for (long time : new long[]{0L, 1L, 5_000L, 1_000_000L, Long.MAX_VALUE}) {
                long position = SeekPositionCalculator.bytePositionForTime(size, time, 60_000L);
                assertTrue(position >= 0L && position < size);
            }
        }
    }

    @Test
    public void theDurationIsEstimatedAt128KibitPerSecond() {
        // (size * 8 * 1000) / (128 * 1024)
        assertEquals(1_000L, SeekPositionCalculator.estimateDurationMs(16_384L));
        assertEquals(61L, SeekPositionCalculator.estimateDurationMs(1_000L));
        assertEquals(0L, SeekPositionCalculator.estimateDurationMs(0L));
        assertEquals(0L, SeekPositionCalculator.estimateDurationMs(-1L));
        assertEquals(0L, SeekPositionCalculator.estimateDurationMs(2L));
    }

    @Test
    public void theDurationEstimateMatchesTheOriginalFormulaAndNeverOverflows() {
        for (long size : new long[]{1L, 999L, 4_000_000L, 123_456_789_012L}) {
            assertEquals((size * 8 * 1000) / (128 * 1024), SeekPositionCalculator.estimateDurationMs(size));
        }
        long huge = SeekPositionCalculator.estimateDurationMs(Long.MAX_VALUE);
        assertTrue(huge > 0L);
    }
}
