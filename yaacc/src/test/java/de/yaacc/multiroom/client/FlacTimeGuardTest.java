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

import java.util.Random;
import org.junit.Test;

/**
 * The time guard of the flac path, fed with the exact skews of the frame headers (see the class comment of the guard).
 * Pure: the tests feed it skews the way the engine does and apply its answer the way the engine does.
 */
public class FlacTimeGuardTest {

    /** Feeds chunks as the engine would (the chunks are 24 ms apart; a stall adds to the skew of every later chunk). */
    private static final class Feed {
        final FlacTimeGuard guard = new FlacTimeGuard();
        long filled;
        int fills;
        int reanchors;
        int k;
        long shift; // the stall(s) of the source so far
        long bias; // an extra, not a stall: set to a number for a single chunk by {@link #one}
        final Random random = new Random(7);
        long noise;

        Feed() {
            guard.reset();
        }

        long skewOf(int chunk) {
            long skew = shift;
            if (noise > 0) {
                skew += random.nextInt((int) (2 * noise + 1)) - noise;
            }
            // positions come from the frame headers: what was inserted does not change them
            return skew;
        }

        long chunk(long extra) {
            long fill = guard.onChunk(skewOf(k) + extra);
            k++;
            if (fill == FlacTimeGuard.REANCHOR) {
                reanchors++;
                guard.reset();
                filled = 0;
                shift = 0;
            } else if (fill > 0) {
                filled += fill;
                fills++;
            }
            return fill;
        }

        void chunks(int count) {
            for (int i = 0; i < count; i++) {
                chunk(0);
            }
        }
    }

    // ---- exact positions (from the frame headers)

    @Test
    public void exactASteadyStreamNeverGetsAFillInThreeHours() {
        Feed feed = new Feed();
        feed.chunks(450_000); // 3 h of blocks of 24 ms
        assertEquals(0, feed.fills);
        assertEquals(0, feed.reanchors);
        assertTrue(feed.guard.isLearned());
    }

    @Test
    public void exactAStallIsFilledToTheMicrosecondAfterTwoChunks() {
        for (long stall : new long[]{9_000L, 24_000L, 40_000L, 100_000L, 250_000L, 299_000L, 1_000_000L, 2_900_000L}) {
            Feed feed = new Feed();
            feed.chunks(100);
            feed.shift = stall;
            feed.chunks(1);
            assertEquals("the median of three needs a second chunk: " + stall, 0, feed.fills);
            feed.chunks(1);
            assertEquals("stall " + stall + " filled at once", stall, feed.filled);
            feed.chunks(5_000);
            assertEquals("and exactly once", 1, feed.fills);
            assertEquals(stall, feed.filled);
        }
    }

    @Test
    public void exactSmallerStallsAreFoundByTheWindows() {
        for (long stall : new long[]{5_000L, 7_999L}) {
            Feed feed = new Feed();
            feed.chunks(100);
            feed.shift = stall;
            feed.chunks(2 * FlacTimeGuard.WINDOW - 1);
            assertEquals("one window is not enough, stall " + stall, 0, feed.fills);
            feed.chunks(2 * FlacTimeGuard.WINDOW);
            assertEquals("stall " + stall, stall, feed.filled);
            assertEquals(1, feed.fills);
        }
    }

    @Test
    public void exactAShiftBelowTheRefineToleranceIsLeftAlone() {
        Feed feed = new Feed();
        feed.chunks(100);
        feed.shift = FlacTimeGuard.REFINE_TOLERANCE_MICROS;
        feed.chunks(5_000);
        assertEquals(0, feed.fills);
    }

    @Test
    public void exactTwoWindowsFarApartAreNoShift() {
        // an elevated window, then a good one, then an elevated one: the count starts over (not "two out of three")
        Feed feed = new Feed();
        feed.chunks(100);
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < FlacTimeGuard.WINDOW; i++) {
                feed.chunk(6_000L); // a window of chunks 6 ms late
            }
            for (int i = 0; i < FlacTimeGuard.WINDOW; i++) {
                feed.chunk(0); // a window on time
            }
        }
        assertEquals(0, feed.fills);
    }

    @Test
    public void exactASingleOddTimestampIsNoStall() {
        for (long odd : new long[]{50_000L, 400_000L, -50_000L}) {
            Feed feed = new Feed();
            feed.chunks(100);
            feed.chunk(odd);
            feed.chunks(5_000);
            assertEquals("odd " + odd, 0, feed.fills);
            assertEquals(0, feed.reanchors);
        }
    }

    @Test
    public void exactAFillThatTurnsOutWrongIsUndoneByAReanchorAndNeverBySkipping() {
        Feed feed = new Feed();
        feed.chunks(100);
        feed.chunk(50_000L);
        feed.chunk(50_000L); // two odd chunks in a row look like a stall
        assertEquals(1, feed.fills);
        for (int i = 0; i < FlacTimeGuard.BELOW_CHUNKS - 1; i++) {
            assertEquals(0, feed.chunk(0));
        }
        assertEquals("nothing happens before the fifth chunk", 0, feed.reanchors);
        feed.chunk(0);
        assertEquals(1, feed.reanchors);
        assertTrue(feed.guard.lastUndoMicros() >= 45_000L);
    }

    @Test
    public void exactASkewThatDropsForGoodIsAReanchor() {
        for (long drop : new long[]{12_000L, 30_000L, 500_000L}) {
            Feed feed = new Feed();
            feed.chunks(100);
            feed.shift = -drop;
            feed.chunks(FlacTimeGuard.BELOW_CHUNKS - 1);
            assertEquals(0, feed.reanchors);
            feed.chunks(1);
            assertEquals("drop " + drop, 1, feed.reanchors);
        }
    }

    @Test
    public void exactASmallDropLowersTheBaseAndAStallAfterItIsStillFilledExactly() {
        Feed feed = new Feed();
        feed.chunks(100);
        feed.shift = -8_000L; // a clock that is a little slow
        feed.chunks(200);
        assertEquals(0, feed.reanchors);
        assertEquals(0, feed.fills);
        feed.shift += 100_000L;
        feed.chunks(10);
        assertEquals(100_000L, feed.filled);
    }

    @Test
    public void exactADropThatLastsFewerThanFiveChunksChangesNothing() {
        Feed feed = new Feed();
        feed.chunks(100);
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < FlacTimeGuard.BELOW_CHUNKS - 1; i++) {
                feed.chunk(-40_000L);
            }
            feed.chunk(0);
        }
        assertEquals(0, feed.reanchors);
        // the base did not move: a stall of 100 ms is 100 ms
        feed.shift = 100_000L;
        feed.chunks(10);
        assertEquals(100_000L, feed.filled);
    }

    @Test
    public void exactANoisyStampIsNotAStallWhateverTheNoiseIfItWasThereFromTheStart() {
        for (long noise : new long[]{3_000L, 8_000L, 15_000L, 40_000L}) {
            Feed feed = new Feed();
            feed.noise = noise;
            feed.chunks(450_000);
            assertEquals("noise " + noise, 0, feed.fills);
            assertEquals("noise " + noise, 0, feed.reanchors);
        }
    }

    @Test
    public void exactAStallIsFoundInANoisyStreamToWithinTheNoiseAndNeverOverFilled() {
        // the immediate fill is the median excess less the band: with noise of +-15 ms it must not be more than the stall
        for (long seed = 1; seed <= 20; seed++) {
            Feed feed = new Feed();
            feed.noise = 15_000L;
            feed.random.setSeed(seed);
            feed.chunks(200);
            feed.shift = 100_000L;
            feed.chunks(3);
            assertTrue("seed " + seed + ": immediate fill " + feed.filled, feed.fills == 1 && feed.filled <= 100_000L + 2_000L);
            feed.chunks(400);
            assertTrue("seed " + seed + ": filled " + feed.filled, feed.filled >= 100_000L - 2 * 15_000L && feed.filled <= 100_000L + 15_000L);
            assertEquals(0, feed.reanchors);
        }
    }

    @Test
    public void exactAStallInsideTheLearningPhaseIsFoundToo() {
        for (int at : new int[]{3, 20, 40}) {
            Feed feed = new Feed();
            feed.chunks(at);
            feed.shift = 120_000L;
            feed.chunks(400);
            assertEquals("stall at chunk " + at, 120_000L, feed.filled);
        }
    }

    @Test
    public void exactAClockThatRunsSlowByAMicrosecondPerChunkIsFollowedWithoutAReanchor() {
        Feed feed = new Feed();
        feed.chunks(100);
        for (int i = 0; i < 100_000; i++) { // a stamp clock that is a little slow (not what Snapcast does, it adds nanoseconds; a robustness case)
            feed.shift -= 1;
            feed.chunk(0);
        }
        assertEquals(0, feed.reanchors);
        assertEquals(0, feed.fills);
    }

    @Test
    public void exactAClockThatRunsFastBySlowlyIsCaughtUpInSteps() {
        Feed feed = new Feed();
        feed.chunks(100);
        for (int i = 0; i < 100_000; i++) {
            feed.shift += 1;
            feed.chunk(0);
        }
        assertEquals(0, feed.reanchors);
        // 100 ms of drift over the run: filled in steps of the refine tolerance, never more than the drift
        assertTrue("filled " + feed.filled, feed.filled <= 100_000L && feed.filled >= 100_000L - 2 * FlacTimeGuard.REFINE_TOLERANCE_MICROS - 30_000L);
    }

    @Test
    public void exactTheGuardNeverDecidesWhileLearning() {
        FlacTimeGuard guard = new FlacTimeGuard();
        guard.reset();
        for (int i = 0; i < FlacTimeGuard.LEARN - 1; i++) {
            assertEquals(0, guard.onChunk(i == 10 ? 900_000L : 0));
        }
        assertFalse(guard.isLearned());
        guard.onChunk(0);
        assertTrue(guard.isLearned());
        guard.reset();
        assertFalse(guard.isLearned());
    }

    @Test
    public void exactAResetForgetsWhatWasInsertedBefore() {
        Feed feed = new Feed();
        feed.chunks(100);
        feed.shift = 100_000L;
        feed.chunks(5);
        assertEquals(100_000L, feed.filled);
        // the engine placed the stream anew (the fill is part of the new anchor): the same skews are the new normal
        feed.guard.reset();
        for (int i = 0; i < 2_000; i++) {
            assertEquals(0, feed.guard.onChunk(feed.skewOf(feed.k++)));
        }
    }

    @Test
    public void exactTheBandOfALearnedWanderIsCapped() {
        // a source that wanders by seconds is not a band: the band stops at the cap, so a stall of the cap's size is a stall
        FlacTimeGuard guard = new FlacTimeGuard();
        guard.reset();
        for (int i = 0; i < FlacTimeGuard.LEARN; i++) {
            guard.onChunk(i % 2 == 0 ? 0 : 5_000_000L);
        }
        assertEquals(150_000L, guard.band());
    }

    @Test
    public void exactTheBandOfASteadyStreamIsZero() {
        FlacTimeGuard guard = new FlacTimeGuard();
        guard.reset();
        for (int i = 0; i < FlacTimeGuard.LEARN; i++) {
            guard.onChunk(1_234_567L);
        }
        assertEquals(0L, guard.band());
    }

    @Test
    public void theGuardSaysWhatItSawAtAFill() {
        Feed feed = new Feed();
        feed.chunks(100);
        feed.shift = 100_000L;
        feed.chunks(2);
        assertEquals(1, feed.guard.lastReason());
        assertEquals(100_000L, feed.guard.lastExcessMicros());
        Feed small = new Feed();
        small.chunks(100);
        small.shift = 6_000L;
        small.chunks(3 * FlacTimeGuard.WINDOW);
        assertEquals(2, small.guard.lastReason());
        assertEquals(6_000L, small.guard.lastExcessMicros());
    }

    // ---- the values, by literal numbers (not by the constants they pin; review cycle 6, W2)

    private static FlacTimeGuard learned(long band) {
        FlacTimeGuard guard = new FlacTimeGuard();
        guard.reset();
        for (int i = 0; i < 48; i++) {
            assertEquals(0, guard.onChunk(i % 2 == 0 ? 0 : band));
        }
        return guard;
    }

    /** Feeds {@code count} chunks of this skew; returns the first answer that is not 0 (or 0). */
    private static long feed(FlacTimeGuard guard, long skew, int count) {
        for (int i = 0; i < count; i++) {
            long answer = guard.onChunk(skew);
            if (answer != 0) {
                return answer;
            }
        }
        return 0;
    }

    @Test
    public void aShiftOf3999MicrosecondsIsLeftAloneAndOneOf4001IsFilled() {
        FlacTimeGuard below = learned(0);
        assertEquals(0, feed(below, 3_999, 5_000));
        FlacTimeGuard above = learned(0);
        assertEquals("filled after two windows of 12", 4_001, feed(above, 4_001, 24));
        FlacTimeGuard one = learned(0);
        assertEquals("not after one window", 0, feed(one, 4_001, 23));
    }

    @Test
    public void aDropOfFourChunksIsNothingAndOneOfFiveIsAReanchor() {
        for (int low = 1; low <= 5; low++) {
            FlacTimeGuard guard = learned(0);
            long answer = 0;
            for (int i = 0; i < low; i++) {
                answer = guard.onChunk(-40_000);
            }
            if (low < 5) {
                assertEquals("after " + low + " low chunks", 0, answer);
            } else {
                assertEquals(FlacTimeGuard.REANCHOR, answer);
            }
        }
        // four low chunks and then one on time: the run is broken, and a later run of four decides nothing either
        FlacTimeGuard guard = learned(0);
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(0, guard.onChunk(-40_000));
            }
            assertEquals(0, guard.onChunk(0));
        }
    }

    @Test
    public void theFifthChunkOfAFiveChunkDropIsTheOnlyOneThatIsLow() {
        // chunks 1 to 4 on time but below the base by a little, the fifth far below: five below the base, the median is the
        // small one: the base is lowered, nothing is re-anchored (a minimum of the five would re-anchor)
        FlacTimeGuard guard = learned(0);
        for (long skew : new long[]{-2_000, -2_000, -2_000, -2_000}) {
            assertEquals(0, guard.onChunk(skew));
        }
        assertEquals(0, guard.onChunk(-50_000));
        // and a maximum of the five would miss the drop of three low chunks out of five
        FlacTimeGuard other = learned(0);
        long[] values = {-2_000, -50_000, -50_000, -2_000, -50_000};
        long answer = 0;
        for (long v : values) {
            answer = other.onChunk(v);
        }
        assertEquals("median -50 ms: a re-anchor", FlacTimeGuard.REANCHOR, answer);
    }

    @Test
    public void theUndoLimitIsTwelveMillisecondsPlusTheBand() {
        // a band of 20 ms learned: the drop must be 12 ms + 20 ms = 32 ms below the base
        FlacTimeGuard under = learned(20_000);
        assertEquals(0, feed(under, -31_999, 5));
        FlacTimeGuard exactly = learned(20_000);
        assertEquals(FlacTimeGuard.REANCHOR, feed(exactly, -32_000, 5));
        // without a band: 12 ms
        FlacTimeGuard noBandUnder = learned(0);
        assertEquals(0, feed(noBandUnder, -11_999, 5));
        FlacTimeGuard noBand = learned(0);
        assertEquals(FlacTimeGuard.REANCHOR, feed(noBand, -12_000, 5));
    }
}
