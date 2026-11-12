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

public class DriftControllerTest {

    private static DriftController controller() {
        return new DriftController(2000, 400);
    }

    private static void feed(DriftController c, long drift, int times) {
        for (int i = 0; i < times; i++) {
            c.addMeasurement(drift);
        }
    }

    @Test
    public void nothingHappensBeforeEnoughMeasurements() {
        DriftController c = controller();
        c.addMeasurement(50_000);
        c.addMeasurement(50_000);
        assertFalse(c.isReady());
        assertEquals(0, c.plan(960, 48000));
    }

    @Test
    public void driftInsideTheDeadBandIsLeftAlone() {
        DriftController c = controller();
        feed(c, 1500, 7);
        assertEquals(0, c.plan(960, 48000));
        feed(c, -2000, 7);
        assertEquals(0, c.plan(960, 48000));
    }

    @Test
    public void lateMeansDropAndEarlyMeansRepeat() {
        DriftController late = controller();
        feed(late, 10_000, 5);
        assertTrue(late.plan(960, 48000) > 0);
        DriftController early = controller();
        feed(early, -10_000, 5);
        assertTrue(early.plan(960, 48000) < 0);
    }

    @Test
    public void theRateIsLimitedToOneFramePerIntervalWhateverTheDrift() {
        DriftController c = controller();
        feed(c, 25_000, 7);
        long frames = 0;
        long played = 0;
        for (int block = 0; block < 100; block++) {
            int n = c.plan(960, 48000);
            frames += n;
            played += 960;
            feed(c, 25_000, 1); // the drift stays (nothing reports the correction)
        }
        assertTrue("dropped " + frames + " in " + played, frames <= played / 400 + 1);
        assertTrue(frames > 0);
    }

    @Test
    public void aCorrectionShiftsTheMeasurementsSoTheControllerDoesNotOvershoot() {
        DriftController c = controller();
        feed(c, 6_000, 7);
        int dropped = 0;
        for (int block = 0; block < 400; block++) {
            int n = c.plan(960, 48000);
            if (n > 0) {
                dropped += n;
                c.applied(n * 1_000_000L / 48000);
            }
        }
        // 6 ms is 288 frames; it stops below half the dead band (1 ms = 48 frames), so about 240 frames
        assertTrue("dropped " + dropped, dropped >= 225 && dropped <= 255);
        assertTrue(Math.abs(c.smoothedMicros()) <= 1000);
    }

    @Test
    public void theMedianIgnoresASingleWildMeasurement() {
        DriftController c = controller();
        feed(c, 500, 6);
        c.addMeasurement(900_000);
        assertEquals(500, c.smoothedMicros());
        assertEquals(0, c.plan(960, 48000));
    }

    @Test
    public void hysteresisKeepsCorrectingUntilHalfTheDeadBand() {
        DriftController c = controller();
        feed(c, 5_000, 7);
        assertTrue(c.plan(960, 48000) > 0);
        feed(c, 1_500, 7); // inside the dead band but above half of it
        assertTrue(c.plan(960, 48000) > 0);
        feed(c, 900, 7);
        assertEquals(0, c.plan(960, 48000));
    }

    @Test
    public void resetForgetsEverything() {
        DriftController c = controller();
        feed(c, 20_000, 7);
        c.reset();
        assertFalse(c.isReady());
        assertEquals(0, c.smoothedMicros());
        assertEquals(0, c.plan(960, 48000));
    }
}
