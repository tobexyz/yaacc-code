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

import java.util.Arrays;

/**
 * Decides how many single frames to drop or repeat in a block to bring the playback back to its time, in small steps.
 *
 * <p>The drift is {@code presentation time of the next frame - the time it should be presented}: positive means late
 * (frames are dropped), negative early (frames are repeated). Measurements arrive only when the platform reports a new
 * position and are noisy, so the controller keeps the last {@value #WINDOW} and acts on their median. A correction
 * shifts all of them at once (what was corrected is no longer drift), so the controller does not overshoot while the
 * median catches up. Corrections start above the dead band and stop below half of it (hysteresis), and are limited to
 * one frame per {@code correctionIntervalFrames} frames played, so the audio is never stretched noticeably. Pure.
 */
public final class DriftController {

    public static final int WINDOW = 7;
    private static final int MIN_SAMPLES = 3;

    private final long deadBandMicros;
    private final int correctionIntervalFrames;
    private final long[] samples = new long[WINDOW];
    private int count;
    private int next;
    private boolean correcting;
    private long credit;

    public DriftController(long deadBandMicros, int correctionIntervalFrames) {
        if (deadBandMicros < 0 || correctionIntervalFrames < 1) {
            throw new IllegalArgumentException("bad drift settings");
        }
        this.deadBandMicros = deadBandMicros;
        this.correctionIntervalFrames = correctionIntervalFrames;
    }

    public void reset() {
        count = 0;
        next = 0;
        correcting = false;
        credit = 0;
    }

    /** A new measurement (call it only for a new position of the output). */
    public void addMeasurement(long driftMicros) {
        samples[next] = driftMicros;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
    }

    /** True when there are enough measurements to act on. */
    public boolean isReady() {
        return count >= MIN_SAMPLES;
    }

    /** The median of the measurements; 0 when there are none. */
    public long smoothedMicros() {
        if (count == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        return copy[count / 2];
    }

    /**
     * @param blockFrames the frames about to be played
     * @param sampleRate  frames per second
     * @return frames to drop (positive) or repeat (negative) in this block; 0 for none
     */
    public int plan(int blockFrames, int sampleRate) {
        credit = Math.min(credit + blockFrames, 2L * correctionIntervalFrames);
        if (!isReady()) {
            return 0;
        }
        long drift = smoothedMicros();
        long magnitude = Math.abs(drift);
        if (correcting ? magnitude < deadBandMicros / 2 : magnitude <= deadBandMicros) {
            correcting = false;
            return 0;
        }
        correcting = true;
        long needed = (magnitude * sampleRate + 500_000L) / 1_000_000L;
        long allowed = credit / correctionIntervalFrames;
        long frames = Math.min(needed, allowed);
        if (frames <= 0) {
            return 0;
        }
        credit -= frames * correctionIntervalFrames;
        return (int) (drift > 0 ? frames : -frames);
    }

    /** Tells the controller that {@code correctionMicros} of drift were removed (positive: playback was moved earlier). */
    public void applied(long correctionMicros) {
        for (int i = 0; i < count; i++) {
            samples[i] -= correctionMicros;
        }
    }
}
