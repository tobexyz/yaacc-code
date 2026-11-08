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

/**
 * Tests for {@link HeldFileSource#clampLength}: the window a provider declares ({@code startOffset}
 * and length) is untrusted and is clamped to the real size of the file behind the descriptor.
 */
public class HeldFileSourceTest {

    private static final long UNKNOWN = -1L;

    @Test
    public void aWholeFileWithUnknownLengthIsTheRealSize() {
        assertEquals(1000L, HeldFileSource.clampLength(0L, UNKNOWN, 1000L));
    }

    @Test
    public void aDeclaredLengthWithinTheFileIsKept() {
        assertEquals(300L, HeldFileSource.clampLength(100L, 300L, 1000L));
    }

    @Test
    public void aDeclaredLengthBeyondTheFileIsClampedToWhatIsThere() {
        assertEquals(900L, HeldFileSource.clampLength(100L, 5000L, 1000L));
        assertEquals(1000L, HeldFileSource.clampLength(0L, Long.MAX_VALUE, 1000L));
    }

    @Test
    public void anUnknownLengthStartsAtTheOffset() {
        assertEquals(750L, HeldFileSource.clampLength(250L, UNKNOWN, 1000L));
    }

    @Test
    public void anyNegativeDeclaredLengthCountsAsUnknown() {
        assertEquals(1000L, HeldFileSource.clampLength(0L, -2L, 1000L));
        assertEquals(1000L, HeldFileSource.clampLength(0L, Long.MIN_VALUE, 1000L));
    }

    @Test
    public void aNegativeOffsetIsRejected() {
        assertEquals(-1L, HeldFileSource.clampLength(-1L, 10L, 1000L));
        assertEquals(-1L, HeldFileSource.clampLength(Long.MIN_VALUE, UNKNOWN, 1000L));
    }

    @Test
    public void anOffsetAtOrBeyondTheEndIsRejected() {
        assertEquals(-1L, HeldFileSource.clampLength(1000L, UNKNOWN, 1000L));
        assertEquals(-1L, HeldFileSource.clampLength(1001L, 10L, 1000L));
        assertEquals(-1L, HeldFileSource.clampLength(Long.MAX_VALUE, 10L, 1000L));
    }

    @Test
    public void aZeroLengthWindowIsRejected() {
        assertEquals(-1L, HeldFileSource.clampLength(0L, 0L, 1000L));
        assertEquals(-1L, HeldFileSource.clampLength(0L, UNKNOWN, 0L));
    }

    @Test
    public void aNegativeRealSizeIsRejected() {
        assertEquals(-1L, HeldFileSource.clampLength(0L, UNKNOWN, -1L));
    }

    @Test
    public void offsetPlusLengthNeverOverflows() {
        long real = Long.MAX_VALUE;
        assertEquals(10L, HeldFileSource.clampLength(real - 10L, Long.MAX_VALUE, real));
    }
}
