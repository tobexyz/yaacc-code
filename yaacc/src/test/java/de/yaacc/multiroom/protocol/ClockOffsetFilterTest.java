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
package de.yaacc.multiroom.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The offset filter against simulated networks. A sample of the protocol is {@code c2s = offset + delay_up} and
 * {@code s2c = -offset + delay_down} (offset = server clock minus client clock), the estimate of a single round trip is
 * {@code (c2s - s2c) / 2 = offset + (delay_up - delay_down) / 2}.
 *
 * <p><b>The bound these tests prove:</b> a single sample is wrong by half the difference of the two delays, at most
 * {@code (dmax - dmin) / 2} on a network whose one-way delays lie between {@code dmin} and {@code dmax} (and never more
 * than half the round trip). The filter reports the median of the lower half of the window by round trip, so its error
 * is inside the same bound as long as more than half of the samples of the window are inside that range; outliers are
 * thrown out by the round trip selection. A constant asymmetry can not be seen by any filter: its error is exactly half
 * the asymmetry, which is at most {@code minRoundTrip / 2} ({@link ClockOffsetFilter#errorBoundMicros()}).
 */
public class ClockOffsetFilterTest {

    private static final long OFFSET = 123_456_789_012L; // the server clock is ahead by about 34 hours
    private static final long MS = 1000L;

    private static boolean feed(ClockOffsetFilter filter, long offset, long up, long down) {
        return filter.addSample(offset + up, -offset + down);
    }

    @Test
    public void aSymmetricNetworkGivesTheExactOffsetAndNoJitter() {
        ClockOffsetFilter filter = new ClockOffsetFilter();

        for (int i = 0; i < 20; i++) {
            feed(filter, OFFSET, 10 * MS, 10 * MS);
        }

        assertTrue(filter.isSynchronized());
        assertEquals(OFFSET, filter.offsetMicros());
        assertEquals(0, filter.jitterMicros());
        assertEquals(20 * MS, filter.minRoundTripMicros());
        assertEquals(10 * MS, filter.errorBoundMicros());
        assertEquals(0, filter.resyncCount());
    }

    @Test
    public void theFilterNeedsThreeSamplesBeforeItClaimsToBeSynchronized() {
        ClockOffsetFilter filter = new ClockOffsetFilter();

        assertFalse(filter.isSynchronized());
        feed(filter, OFFSET, 5 * MS, 5 * MS);
        feed(filter, OFFSET, 5 * MS, 5 * MS);
        assertFalse(filter.isSynchronized());
        feed(filter, OFFSET, 5 * MS, 5 * MS);
        assertTrue(filter.isSynchronized());
        assertEquals(3, filter.sampleCount());
    }

    @Test
    public void aConstantAsymmetryShowsAsHalfTheAsymmetryAndStaysInsideHalfTheRoundTrip() {
        ClockOffsetFilter filter = new ClockOffsetFilter();

        for (int i = 0; i < 20; i++) {
            feed(filter, OFFSET, 30 * MS, 10 * MS);
        }

        assertEquals(10 * MS, filter.offsetMicros() - OFFSET); // (30 - 10) / 2
        assertTrue(Math.abs(filter.offsetMicros() - OFFSET) <= filter.errorBoundMicros());
        assertEquals(20 * MS, filter.errorBoundMicros());
    }

    @Test
    public void jitterOnBothDirectionsStaysInsideHalfTheDelaySpreadForAnySeed() {
        long min = 5 * MS;
        long max = 25 * MS;
        long bound = (max - min) / 2; // 10 ms
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            ClockOffsetFilter filter = new ClockOffsetFilter();
            long worst = 0;
            for (int i = 0; i < 400; i++) {
                feed(filter, OFFSET, min + (long) (random.nextDouble() * (max - min)),
                        min + (long) (random.nextDouble() * (max - min)));
                if (filter.sampleCount() >= ClockOffsetFilter.WINDOW) {
                    worst = Math.max(worst, Math.abs(filter.offsetMicros() - OFFSET));
                }
            }
            assertTrue("seed " + seed + ": worst error " + worst + " us", worst <= bound);
        }
    }

    @Test
    public void jitterOnBothDirectionsIsMuchBetterThanTheWorstCaseInPractice() {
        // the stated bound for this (very jittery) network is 10 ms (above); the typical error is about a third of it
        Random random = new Random(7);
        ClockOffsetFilter filter = new ClockOffsetFilter();
        List<Long> errors = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            feed(filter, OFFSET, 5 * MS + (long) (random.nextDouble() * 20 * MS),
                    5 * MS + (long) (random.nextDouble() * 20 * MS));
            if (filter.sampleCount() >= ClockOffsetFilter.WINDOW) {
                errors.add(Math.abs(filter.offsetMicros() - OFFSET));
            }
        }
        Collections.sort(errors);
        long p95 = errors.get((int) (errors.size() * 0.95));

        assertTrue("95th percentile of the error " + p95 + " us, worst " + errors.get(errors.size() - 1) + " us",
                p95 <= 4 * MS);
    }

    @Test
    public void slowRoundTripsAreNotAllowedToPullTheEstimate() {
        // one sample in five waits 300 ms in one direction (a Wi-Fi power save wake up): a single such sample is wrong
        // by 150 ms. This shows that outliers do not matter; that the shortest round trips decide is pinned by the two
        // tests after this one (a plain median of the window would pass this test as well)
        Random random = new Random(11);
        ClockOffsetFilter filter = new ClockOffsetFilter();
        long worst = 0;
        for (int i = 0; i < 1000; i++) {
            long up = 5 * MS + (long) (random.nextDouble() * 10 * MS);
            long down = 5 * MS + (long) (random.nextDouble() * 10 * MS);
            if (i % 5 == 2) {
                up += 300 * MS;
            }
            feed(filter, OFFSET, up, down);
            if (filter.sampleCount() >= ClockOffsetFilter.WINDOW) {
                worst = Math.max(worst, Math.abs(filter.offsetMicros() - OFFSET));
            }
        }

        assertTrue("worst error " + worst + " us (bound 5 ms = half of the 10 ms spread)", worst <= 5 * MS);
    }

    @Test
    public void theShortestRoundTripsDecideAndNotTheMedianOfAllSamples() {
        // eight exact samples (round trip 10 ms) and eight with a long uplink (round trip 200 ms, each 90 ms off): the
        // median of all sixteen would be 45 ms off, the median of the eight shortest round trips is exact
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 8; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
            feed(filter, OFFSET, 190 * MS, 10 * MS);
        }

        assertEquals(ClockOffsetFilter.WINDOW, filter.sampleCount());
        assertEquals(OFFSET, filter.offsetMicros());
        assertEquals(10 * MS, filter.minRoundTripMicros());
    }

    @Test
    public void whenTheErrorGrowsWithTheRoundTripTheEstimateBeatsTheMedianOfAllSamples() {
        // a long and varying uplink and a constant downlink: the error of a sample is half the extra uplink delay
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            ClockOffsetFilter filter = new ClockOffsetFilter();
            long[] samples = new long[ClockOffsetFilter.WINDOW];
            for (int i = 0; i < samples.length; i++) {
                long up = 5 * MS + (long) (random.nextDouble() * 55 * MS);
                long down = 5 * MS;
                feed(filter, OFFSET, up, down);
                samples[i] = ((OFFSET + up) - (-OFFSET + down)) / 2;
            }
            java.util.Arrays.sort(samples);
            long medianOfAll = (samples[7] + samples[8]) / 2;

            long filterError = Math.abs(filter.offsetMicros() - OFFSET);
            long plainMedianError = Math.abs(medianOfAll - OFFSET);
            assertTrue("seed " + seed + ": filter " + filterError + " us, median of all " + plainMedianError + " us",
                    filterError < plainMedianError);
        }
    }

    @Test
    public void aServerClockThatRunsFastIsFollowedWithinAMillisecond() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        long worst = 0;
        for (int second = 0; second < 600; second++) {
            long drifted = OFFSET + second * 50L; // 50 ppm: 50 us per second
            feed(filter, drifted, 10 * MS, 10 * MS);
            if (second >= ClockOffsetFilter.WINDOW) {
                worst = Math.max(worst, Math.abs(filter.offsetMicros() - drifted));
            }
        }

        assertTrue("worst lag " + worst + " us", worst <= 1 * MS);
    }

    @Test
    public void samplesWithImpossibleOrHugeRoundTripsAreRefusedAndCounted() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 5; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
        }
        long before = filter.offsetMicros();

        assertFalse(filter.addSample(OFFSET - 10 * MS, -OFFSET - 10 * MS)); // negative round trip
        assertFalse(filter.addSample(OFFSET + 400 * MS, -OFFSET + 101 * MS)); // 501 ms
        assertFalse(filter.addSample(Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(filter.addSample(Long.MIN_VALUE, Long.MIN_VALUE));
        assertFalse(filter.addSample(Long.MAX_VALUE, Long.MIN_VALUE));
        assertFalse(filter.addSample(Long.MIN_VALUE, Long.MAX_VALUE));

        assertEquals(before, filter.offsetMicros());
        assertEquals(6, filter.rejectedSamples());
        assertEquals(5, filter.sampleCount());
        assertTrue(filter.addSample(OFFSET + 250 * MS, -OFFSET + 250 * MS)); // exactly 500 ms is still taken
    }

    @Test
    public void aStepLargerThanTheLimitIsNotAppliedAtOnceAndIsResynchronisedAfterThreeAgreeingSamples() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 30; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
        }
        int epoch = filter.epoch();
        long newOffset = OFFSET + 3_000_000_000L / 1000 * 1000; // the server restarted: its clock is 3000 s further

        feed(filter, newOffset, 5 * MS, 5 * MS);
        assertEquals("the first sample is not believed", OFFSET, filter.offsetMicros());
        feed(filter, newOffset, 5 * MS, 5 * MS);
        assertEquals("nor the second", OFFSET, filter.offsetMicros());
        assertEquals(0, filter.resyncCount());
        assertEquals(epoch, filter.epoch());

        feed(filter, newOffset, 5 * MS, 5 * MS);

        assertEquals(1, filter.resyncCount());
        assertEquals(epoch + 1, filter.epoch());
        assertTrue(filter.isSynchronized());
        assertEquals(newOffset, filter.offsetMicros());
        assertEquals(3, filter.sampleCount());
    }

    @Test
    public void aLoneWildSampleIsIgnoredAndNeverStartsAResync() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 30; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
        }

        feed(filter, OFFSET + 5_000_000L, 5 * MS, 5 * MS);
        feed(filter, OFFSET + 5_000_000L, 5 * MS, 5 * MS);
        feed(filter, OFFSET, 5 * MS, 5 * MS); // a normal sample ends the run of strange ones
        feed(filter, OFFSET + 5_000_000L, 5 * MS, 5 * MS);
        feed(filter, OFFSET + 5_000_000L, 5 * MS, 5 * MS);

        assertEquals(0, filter.resyncCount());
        assertEquals(OFFSET, filter.offsetMicros());
    }

    @Test
    public void strangeSamplesThatDisagreeWithEachOtherDoNotResynchronise() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 30; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
        }

        feed(filter, OFFSET + 3_000_000L, 5 * MS, 5 * MS);
        feed(filter, OFFSET + 7_000_000L, 5 * MS, 5 * MS);
        feed(filter, OFFSET + 11_000_000L, 5 * MS, 5 * MS);
        feed(filter, OFFSET - 9_000_000L, 5 * MS, 5 * MS);

        assertEquals(0, filter.resyncCount());
        assertEquals(OFFSET, filter.offsetMicros());
    }

    @Test
    public void aStepBelowTheLimitMovesThroughTheMedianWithoutAResync() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 30; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
        }

        for (int i = 0; i < 40; i++) {
            feed(filter, OFFSET + 400 * MS, 5 * MS, 5 * MS);
        }

        assertEquals(0, filter.resyncCount());
        assertEquals(OFFSET + 400 * MS, filter.offsetMicros());
    }

    @Test
    public void jitterIsTheSpreadOfTheSamplesAroundTheEstimate() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 32; i++) {
            feed(filter, OFFSET + (i % 2 == 0 ? 5 * MS : -5 * MS), 10 * MS, 10 * MS);
        }

        assertEquals(5 * MS, filter.jitterMicros());
        assertTrue(Math.abs(filter.offsetMicros() - OFFSET) <= 5 * MS);
    }

    @Test
    public void resetForgetsEverythingAndStartsANewEpoch() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 10; i++) {
            feed(filter, OFFSET, 5 * MS, 5 * MS);
        }
        int epoch = filter.epoch();

        filter.reset();

        assertFalse(filter.isSynchronized());
        assertEquals(0, filter.sampleCount());
        assertEquals(epoch + 1, filter.epoch());
        assertEquals(0, filter.rejectedSamples());
    }

    @Test
    public void theServerClockIsTheClientClockPlusTheOffset() {
        ClockOffsetFilter filter = new ClockOffsetFilter();
        for (int i = 0; i < 5; i++) {
            feed(filter, 777_000L, 5 * MS, 5 * MS);
        }

        assertEquals(1_000_000L + 777_000L, filter.serverNowMicros(1_000_000L));
    }
}
