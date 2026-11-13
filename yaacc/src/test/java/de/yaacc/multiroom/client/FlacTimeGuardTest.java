/*
 *
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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FlacTimeGuardTest {

    /** The band of a stream of 1152-frame blocks in 20 ms chunks: the skew moves by 0, 4, 8, 12, 16 ms in a period of five. */
    private static long pattern(int k) {
        return (k % 5) * 4_000L;
    }

    /** Feeds chunks as the engine would: every fill moves the frame count, so later skews are smaller by it. */
    private static final class Feed {
        final FlacTimeGuard guard = new FlacTimeGuard();
        long filled;
        int fills;
        int k;
        long shift; // the stall(s) of the source so far

        void chunks(int count) {
            for (int i = 0; i < count; i++, k++) {
                long fill = guard.onChunk(pattern(k) + shift - filled);
                if (fill > 0) {
                    filled += fill;
                    fills++;
                }
            }
        }
    }

    @Test
    public void aSteadyStreamWithTheBandOfOneChunkNeverGetsAFill() {
        Feed feed = new Feed();
        feed.chunks(50_000);
        assertEquals(0, feed.fills);
        assertTrue(feed.guard.isLearned());
    }

    @Test
    public void aStallIsFilledAtOnceWithAtLeastItsLengthMinusTheBandAndThenExactlyItsLength() {
        for (long stall : new long[]{40_000L, 60_000L, 100_000L, 240_000L, 300_000L, 1_000_000L, 2_900_000L}) {
            Feed feed = new Feed();
            feed.chunks(200);
            feed.shift = stall;
            feed.chunks(1);
            assertTrue("stall " + stall + ": immediate fill " + feed.filled, feed.filled >= stall - 16_000L && feed.filled <= stall);
            feed.chunks(200);
            assertEquals("stall " + stall + " is filled to the microsecond in the end", stall, feed.filled);
            feed.chunks(5_000);
            assertEquals("and nothing more", stall, feed.filled);
        }
    }

    @Test
    public void aSmallStallIsFoundByTheWindowsAndFilledToWithinTheTolerance() {
        for (long stall : new long[]{8_000L, 12_000L, 20_000L, 25_000L}) {
            Feed feed = new Feed();
            feed.chunks(200);
            feed.shift = stall;
            feed.chunks(60);
            // what the guard leaves is below its refine tolerance of 4 ms
            assertTrue("stall " + stall + " filled " + feed.filled,
                    feed.filled <= stall && feed.filled >= stall - FlacTimeGuard.REFINE_TOLERANCE_MICROS);
        }
    }

    @Test
    public void aShiftBelowTheToleranceIsLeftAlone() {
        Feed feed = new Feed();
        feed.chunks(200);
        feed.shift = 4_000L;
        feed.chunks(2_000);
        assertEquals(0, feed.fills);
    }

    @Test
    public void severalStallsOneAfterTheOtherAreAllFilled() {
        Feed feed = new Feed();
        feed.chunks(100);
        long total = 0;
        for (long stall : new long[]{50_000L, 200_000L, 30_000L, 700_000L}) {
            feed.shift += stall;
            total += stall;
            feed.chunks(150);
            assertEquals(total, feed.filled);
        }
    }

    @Test
    public void aSkewThatFallsBelowTheBaseMovesTheBaseAndFillsNothing() {
        Feed feed = new Feed();
        feed.chunks(100);
        feed.shift = -30_000L; // timestamps a little earlier than the count: no silence can undo that
        feed.chunks(2_000);
        assertEquals(0, feed.fills);
        // and a stall after that is still found, relative to the new base
        feed.shift += 100_000L;
        feed.chunks(200);
        assertEquals(100_000L, feed.filled);
    }

    @Test
    public void nothingIsDecidedWhileLearningAndResetStartsOver() {
        FlacTimeGuard guard = new FlacTimeGuard();
        for (int i = 0; i < FlacTimeGuard.LEARN - 1; i++) {
            assertEquals(0, guard.onChunk(i == 10 ? 900_000L : 0));
        }
        assertFalse(guard.isLearned());
        guard.onChunk(0);
        assertTrue(guard.isLearned());
        guard.reset();
        assertFalse(guard.isLearned());
    }
}
