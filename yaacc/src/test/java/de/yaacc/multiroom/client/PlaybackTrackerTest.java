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

public class PlaybackTrackerTest {

    private static final long NOW = 100_000_000L;

    @Test
    public void aReportIsTakenAndExtrapolatedAtTheRateOfTheStream() {
        PlaybackTracker t = new PlaybackTracker(48000);
        assertTrue(t.update(new PlaybackPosition(4800, NOW - 10_000), 10_000, NOW));
        assertEquals(NOW - 10_000, t.presentationMicros(4800));
        assertEquals(NOW - 10_000 + 1_000_000, t.presentationMicros(4800 + 48000));
        assertEquals(NOW - 10_000 - 1_000, t.presentationMicros(4800 - 48));
    }

    @Test
    public void nothingIsValidBeforeTheFirstReport() {
        PlaybackTracker t = new PlaybackTracker(48000);
        assertFalse(t.isValid());
        assertEquals(-1, t.ageMicros(NOW));
        assertFalse(t.update(null, 0, NOW));
    }

    @Test
    public void aReportThatClaimsMoreFramesThanWereWrittenIsRefused() {
        PlaybackTracker t = new PlaybackTracker(48000);
        assertFalse(t.update(new PlaybackPosition(5000, NOW), 4000, NOW));
        assertFalse(t.isValid());
        assertEquals(1, t.rejectedCount());
    }

    @Test
    public void aReportFromBeforeTheFlushIsRefused() {
        PlaybackTracker t = new PlaybackTracker(48000);
        t.reset(NOW);
        assertFalse(t.update(new PlaybackPosition(100, NOW - 1), 1000, NOW + 10));
        assertTrue(t.update(new PlaybackPosition(100, NOW + 5), 1000, NOW + 10));
    }

    @Test
    public void aReportThatIsTooOldOrTooFarInTheFutureIsRefused() {
        PlaybackTracker t = new PlaybackTracker(48000);
        assertFalse(t.update(new PlaybackPosition(100, NOW - 4_000_000), 1000, NOW));
        assertFalse(t.update(new PlaybackPosition(100, NOW + 2_000_000), 1000, NOW));
        assertTrue("committed to be presented shortly", t.update(new PlaybackPosition(100, NOW + 100_000), 1000, NOW));
    }

    @Test
    public void thePositionMustAdvance() {
        PlaybackTracker t = new PlaybackTracker(48000);
        assertTrue(t.update(new PlaybackPosition(1000, NOW), 5000, NOW));
        long revision = t.revision();
        assertFalse("the same report again", t.update(new PlaybackPosition(1000, NOW), 5000, NOW));
        assertFalse("standing still", t.update(new PlaybackPosition(1000, NOW + 20_000), 5000, NOW + 20_000));
        assertFalse("backwards", t.update(new PlaybackPosition(900, NOW + 40_000), 5000, NOW + 40_000));
        assertFalse("more frames at an earlier time", t.update(new PlaybackPosition(1200, NOW - 5000), 5000, NOW));
        assertEquals(revision, t.revision());
        assertTrue(t.update(new PlaybackPosition(2000, NOW + 20_000), 5000, NOW + 20_000));
        assertTrue(t.revision() > revision);
    }

    @Test
    public void resetForgetsThePosition() {
        PlaybackTracker t = new PlaybackTracker(48000);
        t.update(new PlaybackPosition(1000, NOW), 5000, NOW);
        t.reset(NOW + 1);
        assertFalse(t.isValid());
    }

    // ---- outliers: a lone odd report is not taken, a level shift that the next report confirms is

    @Test
    public void aLoneOddReportIsHeldBackAndTheLineGoesOn() {
        PlaybackTracker t = new PlaybackTracker(48000, 150_000);
        assertTrue(t.update(new PlaybackPosition(48_000, NOW), 200_000, NOW));
        // 200 ms later the frame count lags by 160 ms
        assertFalse(t.update(new PlaybackPosition(48_000 + 1_920, NOW + 200_000), 200_000, NOW + 200_000));
        assertEquals(1, t.outlierCount());
        assertEquals(NOW, t.presentationMicros(48_000));
        // the next report is on the old line again: taken
        assertTrue(t.update(new PlaybackPosition(48_000 + 19_200, NOW + 400_000), 200_000, NOW + 400_000));
        assertEquals(NOW + 400_000, t.presentationMicros(48_000 + 19_200));
    }

    @Test
    public void aLevelShiftThatTheNextReportConfirmsIsTaken() {
        PlaybackTracker t = new PlaybackTracker(48000, 150_000);
        assertTrue(t.update(new PlaybackPosition(48_000, NOW), 400_000, NOW));
        // the output stood still for 160 ms: from now on the frames lag by 160 ms
        assertFalse(t.update(new PlaybackPosition(48_000 + 1_920, NOW + 200_000), 400_000, NOW + 200_000));
        assertTrue(t.update(new PlaybackPosition(48_000 + 11_520, NOW + 400_000), 400_000, NOW + 400_000));
        assertEquals(NOW + 400_000, t.presentationMicros(48_000 + 11_520));
    }

    @Test
    public void withoutAGateEveryReportIsTaken() {
        PlaybackTracker t = new PlaybackTracker(48000);
        assertTrue(t.update(new PlaybackPosition(48_000, NOW), 200_000, NOW));
        assertTrue(t.update(new PlaybackPosition(48_000 + 100, NOW + 200_000), 200_000, NOW + 200_000));
        assertEquals(0, t.outlierCount());
    }
}
