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

/**
 * Notices that a flac stream has shifted against its timestamps, which the frame counting of the engine can not see.
 *
 * <p><b>Why.</b> A flac chunk is placed after the previous one by counting decoded frames (the decoder's output does not
 * line up with the chunks). A stall of the source (the server resets its chunk clock after any read slower than a chunk,
 * so the timestamps jump by the stall) is invisible to that: every later chunk is due as much earlier as the stall lasted.
 * Up to about 200 ms the stream then simply plays shifted against every other client for the rest of the session; above
 * that the lateness is more than the queue absorbs and the engine skipped every chunk (the review's C1).
 *
 * <p><b>What it looks at.</b> The skew of a chunk is its timestamp minus the stream time the frame count puts it at. In a
 * steady stream it is not constant: the encoder emits a chunk when a block is complete, stamped with the pcm chunk that
 * completed it, so with blocks of 1152 frames and chunks of 20 ms the chunks come 20, 20, 20, 20, 40 ms apart and the
 * skew moves in a band of about one chunk. A stall adds its length to the skew of every later chunk. So the guard learns
 * the lowest skew and the width of the band from the first {@value #LEARN} chunks, and then watches the excess over the
 * lowest skew:
 * <ul>
 *   <li>an excess beyond the band and {@value #IMMEDIATE_TOLERANCE_MICROS} microseconds is a stall of at least the excess
 *   minus the band: that much silence is inserted at once (the same operation as the gap fill of the pcm path);</li>
 *   <li>what remains (the stall was up to one band longer), or a small stall, shows as a lowest excess of a whole window of
 *   {@value #WINDOW} chunks that stays above {@value #REFINE_TOLERANCE_MICROS} microseconds in two windows in a row (a
 *   window is longer than the period of the pattern, so its lowest excess is the base again when nothing happened):
 *   that is inserted too.</li>
 * </ul>
 * A skew below the lowest one moves the base down (the band widens by the same). Pure; one instance per timeline, reset
 * whenever the engine anchors anew.
 */
final class FlacTimeGuard {

    /** Chunks watched before the base and the band are fixed (more than a second of audio: several blocks and patterns). */
    static final int LEARN = 48;
    static final int WINDOW = 12;
    /** An excess this far beyond the band is a stall at once. */
    static final long IMMEDIATE_TOLERANCE_MICROS = 20_000L;
    /** A window whose lowest excess is above this is a shift that stays. */
    static final long REFINE_TOLERANCE_MICROS = 4_000L;
    /** The band never grows beyond this (a decoder that jitters more is left to the corrections). */
    private static final long MAX_BAND_MICROS = 150_000L;

    private int seen;
    private long learnMin = Long.MAX_VALUE;
    private long learnMax = Long.MIN_VALUE;
    private long base;
    private long band;
    private final long[] window = new long[WINDOW];
    private int windowCount;
    private int shiftedWindows;

    /** Forget everything: the stream is placed anew. */
    void reset() {
        seen = 0;
        learnMin = Long.MAX_VALUE;
        learnMax = Long.MIN_VALUE;
        base = 0;
        band = 0;
        windowCount = 0;
        shiftedWindows = 0;
    }

    /** True once the base and the band are fixed. */
    boolean isLearned() {
        return seen >= LEARN;
    }

    /**
     * @param skewMicros the chunk's timestamp minus the stream time its start has by the frame count (micros)
     * @return microseconds of silence to insert before this chunk (0: none); the skews of the chunks that follow are
     * smaller by that much once the engine has moved its frame count by it
     */
    long onChunk(long skewMicros) {
        if (seen < LEARN) {
            learnMin = Math.min(learnMin, skewMicros);
            learnMax = Math.max(learnMax, skewMicros);
            if (++seen == LEARN) {
                base = learnMin;
                band = Math.min(MAX_BAND_MICROS, learnMax - learnMin);
            }
            return 0;
        }
        long excess = skewMicros - base;
        if (excess < 0) {
            band = Math.min(MAX_BAND_MICROS, band - excess);
            base = skewMicros;
            excess = 0;
        }
        if (excess > band + IMMEDIATE_TOLERANCE_MICROS) {
            long fill = excess - band;
            windowCount = 0;
            shiftedWindows = 0;
            return fill;
        }
        window[windowCount++] = excess;
        if (windowCount < WINDOW) {
            return 0;
        }
        windowCount = 0;
        long lowest = Long.MAX_VALUE;
        for (long value : window) {
            lowest = Math.min(lowest, value);
        }
        if (lowest > REFINE_TOLERANCE_MICROS) {
            if (++shiftedWindows >= 2) {
                shiftedWindows = 0;
                return lowest;
            }
        } else {
            shiftedWindows = 0;
        }
        return 0;
    }
}
