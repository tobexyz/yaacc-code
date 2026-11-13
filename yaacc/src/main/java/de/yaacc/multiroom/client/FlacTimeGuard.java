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
 * line up with the chunks). A stall of the source is invisible to that: the server restarts the clock of its chunks after a
 * read that took longer than a chunk (Snapcast {@code PcmStream::chunkEncoded}, {@code asio_stream.hpp}), so the timestamps
 * after the stall jump by the stall (plus up to a block of frames that the encoder had collected) while the frames go on.
 * Every later chunk is then due as much earlier as the stall lasted: up to about 200 ms the stream plays shifted against
 * every other client for the rest of the session, above that the engine used to skip every chunk (the review's C1).
 *
 * <p><b>What it looks at.</b> The <i>skew</i> of a chunk is its timestamp minus the position of its first frame. The
 * server stamps the chunks of a flac stream with a running clock that is advanced by the duration of the encoded frames
 * (one block of the encoder, 24 ms for 1152 frames at 48 kHz, apart), so in a steady stream the skew is <b>constant</b>, and
 * a stall adds its length to it. The position must be <b>exact</b>: the engine reads the frame number from the FLAC frame
 * header at the start of the chunk, which is the position of the audio the server's encoder was given, whatever the client's
 * decoder does with it (hold a block back, step its latency). The guard is only ever fed such positions: with positions
 * counted from the decoded frames a decoder that delivers a block late looks like a stall of the source, and the silence
 * inserted for it is never taken back (the review's W1, and the owner's trace of the 8j build: four fills of about one block
 * in 15 minutes, the stream late against the other clients). So when the headers can not be read the engine does not
 * run the guard at all.
 *
 * After the learning phase the excess of a chunk is its skew over the lowest one (less what the guard has inserted):
 * <ul>
 *   <li>the median of the last three excesses beyond the band and {@value #IMMEDIATE_MICROS} microseconds is a stall of at least the median
 *   minus the band: that much silence is inserted at once (the operation of the pcm gap fill). The median means a single odd
 *   timestamp never does it;</li>
 *   <li>what remains, or a small stall, shows as a lowest excess of a window of {@value #WINDOW} chunks that stays above
 *   {@value #REFINE_TOLERANCE_MICROS} microseconds (and above half the band) in two windows in a row: that is inserted
 *   too;</li>
 *   <li>a skew <i>below</i> the lowest one in {@value #BELOW_CHUNKS} chunks in a row means the stream is later than its timestamps
 *   say (a fill that was wrong, a clock that jumped back): when the median of those is {@value #UNDO_MICROS} microseconds or
 *   more below the base the guard asks for the stream to be placed anew by its timestamps ({@link #REANCHOR}, no audio is
 *   skipped to undo anything); a smaller drop lowers the base (a clock that is a little slow). A single low chunk changes
 *   nothing.</li>
 * </ul>
 * Pure; one instance per timeline, reset whenever the engine anchors anew.
 */
final class FlacTimeGuard {

    /** Returned by {@link #onChunk} when the stream has to be placed anew by its timestamps. */
    static final long REANCHOR = -1L;

    /** Chunks watched before the base and the band are fixed (more than a second of audio). */
    static final int LEARN = 48;
    static final int WINDOW = 12;
    /** An excess this far beyond the band is a stall at once (a third of a block of the default encoder). */
    static final long IMMEDIATE_MICROS = 8_000L;
    /** A window whose lowest excess is above this is a shift that stays. */
    static final long REFINE_TOLERANCE_MICROS = 4_000L;
    /** Consecutive chunks below the base before the guard believes it. */
    static final int BELOW_CHUNKS = 5;
    /** A drop of the skew by this much is a stream that is late, not a slow clock. */
    static final long UNDO_MICROS = 12_000L;
    /** The band never grows beyond this (a source that jitters more is left to the corrections). */
    private static final long MAX_BAND_MICROS = 150_000L;

    private int seen;
    private long learnMin = Long.MAX_VALUE;
    private long learnMax = Long.MIN_VALUE;
    private long base;
    private long band;
    /** What was inserted so far (the skews of exact positions do not change by it, so it is taken off here). */
    private long applied;
    private final long[] window = new long[WINDOW];
    private int windowCount;
    private int shiftedWindows;
    private final long[] last3 = new long[3];
    private int last3Count;
    private final long[] below = new long[BELOW_CHUNKS];
    private int belowCount;
    private long lastUndoMicros;
    private int lastReason;
    private long lastExcessMicros;

    /** Forget everything: the stream is placed anew. */
    void reset() {
        seen = 0;
        learnMin = Long.MAX_VALUE;
        learnMax = Long.MIN_VALUE;
        base = 0;
        band = 0;
        applied = 0;
        clearEvidence();
        belowCount = 0;
    }

    private void clearEvidence() {
        windowCount = 0;
        shiftedWindows = 0;
        last3Count = 0;
    }

    /** True once the base and the band are fixed. */
    boolean isLearned() {
        return seen >= LEARN;
    }

    /** How far below its base the stream was when {@link #REANCHOR} was returned last (micros). */
    long lastUndoMicros() {
        return lastUndoMicros;
    }

    /** Why the last fill was made: 1 a stall seen at once, 2 a shift seen over windows (0: none yet). */
    int lastReason() {
        return lastReason;
    }

    /** What the guard saw at the last fill: the excess of the skew over its base before the fill (micros). */
    long lastExcessMicros() {
        return lastExcessMicros;
    }

    /** The width of the band the skew moves in (micros), after the learning phase. */
    long band() {
        return band;
    }

    /**
     * @param skewMicros the chunk's timestamp minus the stream time of its first frame (micros)
     * @return microseconds of silence to insert before this chunk (0: none), or {@link #REANCHOR}
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
        long excess = skewMicros - base - applied;
        if (excess < 0) {
            below[belowCount++] = excess;
            if (belowCount < BELOW_CHUNKS) {
                return 0;
            }
            belowCount = 0;
            long median = median5(below);
            if (-median >= UNDO_MICROS + band) {
                lastUndoMicros = -median;
                return REANCHOR;
            }
            // the stream is a little early against its stamps: a slow clock of the server; follow it
            base += median;
            clearEvidence();
            return 0;
        }
        belowCount = 0;
        if (last3Count < 3) {
            last3[last3Count++] = excess;
        } else {
            last3[0] = last3[1];
            last3[1] = last3[2];
            last3[2] = excess;
        }
        if (last3Count == 3) {
            long median = median3(last3[0], last3[1], last3[2]);
            if (median > band + IMMEDIATE_MICROS) {
                long fill = median - band;
                lastReason = 1;
                lastExcessMicros = median;
                applied += fill;
                clearEvidence();
                return fill;
            }
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
        if (lowest > Math.max(REFINE_TOLERANCE_MICROS, band / 2)) {
            if (++shiftedWindows >= 2) {
                clearEvidence();
                lastReason = 2;
                lastExcessMicros = lowest;
                applied += lowest;
                return lowest;
            }
        } else {
            shiftedWindows = 0;
        }
        return 0;
    }

    private static long median3(long a, long b, long c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private static long median5(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
