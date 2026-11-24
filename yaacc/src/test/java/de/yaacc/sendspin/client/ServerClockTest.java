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
package de.yaacc.sendspin.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.session.ControllerMessages;

import org.json.JSONObject;
import org.junit.Test;

/** The server clock estimate of the controller and the extrapolation of the track position (spec, "Calculating current track position"). */
public class ServerClockTest {

    @Test
    public void aMeasurementIsTheOffsetOfTheExchangeAndItsErrorHalfTheDelay() {
        // client sends at 1000 (its clock), the server clock is 5000 ahead; 100 us each way; the server answers after 20 us
        long t1 = 1000, t2 = 6100, t3 = 6120, t4 = 1220;
        assertEquals(5000L, ServerClock.measurement(t1, t2, t3, t4));
        assertEquals(100L, ServerClock.maxError(t1, t2, t3, t4));
    }

    @Test
    public void thereIsNoEstimateBeforeAFinishedBurst() {
        ServerClock clock = new ServerClock();
        assertFalse(clock.hasEstimate());
        clock.add(1000, 6100, 6120, 1220);
        assertFalse("a burst has to end first", clock.hasEstimate());
        clock.endBurst();
        assertTrue(clock.hasEstimate());
        assertEquals(1220L + 5000L, clock.serverTimeAt(1220L));
    }

    @Test
    public void aBurstFeedsOnlyItsMeasurementWithTheLowestError() {
        ServerClock clock = new ServerClock();
        clock.add(0, 5_500, 5_510, 1_000);      // offset 5005, error 495: a slow exchange
        clock.add(2000, 7_060, 7_070, 2_120);   // offset 5005... let the clean one differ by design below
        clock.endBurst();
        // second exchange: T2-T1 = 5060, T3-T4 = 4950, offset 5005, error 55
        assertEquals(5005L, clock.offsetMicros());
        ServerClock other = new ServerClock();
        other.add(0, 5_500, 5_510, 1_000);      // error 495, offset 5005
        other.add(2000, 7_000, 7_001, 2_100);   // error 50, offset 4950.5 -> 4950 (integer division toward zero)
        other.endBurst();
        assertEquals("the clean exchange wins, not the average", 4950L, other.offsetMicros());
    }

    @Test
    public void measurementsWithANonPositiveErrorOnlyCountIfThereIsNothingElse() {
        ServerClock clock = new ServerClock();
        clock.add(0, 1_000, 1_000, 0);  // error 0: not trusted
        clock.add(10, 1_020, 1_030, 40); // error 10, offset 1000
        clock.endBurst();
        assertEquals(1000L, clock.offsetMicros());
    }

    @Test
    public void laterBurstsSmoothTheEstimateAndALargeJumpResetsIt() {
        ServerClock clock = new ServerClock();
        clock.add(0, 1_050, 1_050, 100);
        clock.endBurst();
        long first = clock.offsetMicros(); // 1000
        assertEquals(1000L, first);
        clock.add(10_000, 11_060, 11_060, 10_100); // offset 1010, error 50: a small residual
        clock.endBurst();
        long smoothed = clock.offsetMicros();
        assertTrue("moves toward the new value, not all the way: " + smoothed, smoothed > first && smoothed < 1010);
        clock.add(20_000, 520_050, 520_050, 20_100); // offset 500000: far beyond three times the error: a new baseline
        clock.endBurst();
        assertEquals(500_000L, clock.offsetMicros());
    }

    @Test
    public void anEmptyBurstChangesNothing() {
        ServerClock clock = new ServerClock();
        clock.add(0, 1_050, 1_050, 100);
        clock.endBurst();
        long before = clock.offsetMicros();
        clock.endBurst();
        assertEquals(before, clock.offsetMicros());
    }

    // ---------------------------------------------------------------- the position of the track

    private static ControllerMessages.Metadata metadata(long timestamp, Long progress, Long duration, int speed) throws Exception {
        JSONObject m = new JSONObject().put("timestamp", timestamp).put("title", "T");
        if (progress != null) {
            m.put("progress", new JSONObject().put("track_progress", progress).put("track_duration", duration).put("playback_speed", speed));
        }
        return ControllerMessages.parseServerState(new JSONObject().put("metadata", m)).metadata;
    }

    @Test
    public void thePositionAdvancesWithTheSpeedAndIsClamped() throws Exception {
        ControllerMessages.Metadata m = metadata(1_000_000, 10_000L, 180_000L, 1000);
        assertEquals(10_000L, TrackPosition.positionMs(m, 1_000_000));
        assertEquals("two seconds later", 12_000L, TrackPosition.positionMs(m, 3_000_000));
        assertEquals("double speed", 14_000L, TrackPosition.positionMs(metadata(1_000_000, 10_000L, 180_000L, 2000), 3_000_000));
        assertEquals("paused", 10_000L, TrackPosition.positionMs(metadata(1_000_000, 10_000L, 180_000L, 0), 9_000_000));
        assertEquals("clamped to the duration", 180_000L, TrackPosition.positionMs(m, 1_000_000_000L));
        assertEquals("never below zero", 0L, TrackPosition.positionMs(m, -1_000_000_000L));
        assertEquals("an unlimited track has no upper clamp", 70_000L, TrackPosition.positionMs(metadata(0, 10_000L, 0L, 1000), 60_000_000L));
    }

    @Test
    public void withoutProgressThereIsNoPosition() throws Exception {
        assertEquals(-1L, TrackPosition.positionMs(metadata(1, null, null, 0), 5));
        assertEquals(-1L, TrackPosition.positionMs(null, 5));
    }

    @Test
    public void aMetadataStateInThePastOrPresentIsAppliedAtOnce() throws Exception {
        TrackPosition.Tracker tracker = new TrackPosition.Tracker();
        ControllerMessages.Metadata a = metadata(1_000, 0L, 1000L, 1000);
        tracker.accept(a, 2_000);
        assertSame(a, tracker.current(2_000));
        assertNull(tracker.pending());
    }

    @Test
    public void aFutureStateIsHeldUntilItsTimeAndReplacedByTheNextFutureOne() throws Exception {
        TrackPosition.Tracker tracker = new TrackPosition.Tracker();
        ControllerMessages.Metadata now = metadata(1_000, 0L, 1000L, 1000);
        ControllerMessages.Metadata next = metadata(10_000, 0L, 1000L, 1000);
        ControllerMessages.Metadata later = metadata(20_000, 0L, 1000L, 1000);
        tracker.accept(now, 2_000);
        tracker.accept(next, 2_500);
        assertSame("the display keeps the current state", now, tracker.current(5_000));
        assertSame("a pending update may be shown early", next, tracker.pending());
        tracker.accept(later, 5_000);
        assertSame("a new future message replaces the held one", later, tracker.pending());
        assertSame(now, tracker.current(19_999));
        assertSame("its time has come", later, tracker.current(20_000));
        assertNull(tracker.pending());
    }

    @Test
    public void aPresentStateCancelsAHeldOne() throws Exception {
        TrackPosition.Tracker tracker = new TrackPosition.Tracker();
        tracker.accept(metadata(1_000, 0L, 1000L, 1000), 2_000);
        tracker.accept(metadata(50_000, 0L, 1000L, 1000), 2_000);
        ControllerMessages.Metadata cancel = metadata(2_000, 0L, 1000L, 1000);
        tracker.accept(cancel, 3_000);
        assertSame(cancel, tracker.current(4_000));
        assertNull(tracker.pending());
    }
}
