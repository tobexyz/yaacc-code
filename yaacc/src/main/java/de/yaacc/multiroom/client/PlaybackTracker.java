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
 * Keeps the latest trustworthy position of the output and extrapolates it: {@code frame f is presented at
 * presentedAt + (f - frames) / rate}. The platform's positions are sparse, may be stale after a flush, may stall
 * during an underrun and (on Android) wrap at 32 bits, so a report is accepted only when it can be true: it must not
 * lie before the last flush, must not be in the far future or old, must not claim more frames than were written, and
 * must advance. Pure.
 */
public final class PlaybackTracker {

    /** A position may be this far in the future: the platform reports frames "committed to be presented". */
    static final long MAX_FUTURE_MICROS = 1_000_000L;
    /** A position older than this is rejected at once (stale after a route change). */
    static final long MAX_AGE_MICROS = 3_000_000L;

    private final int sampleRate;
    private final long outlierMicros;
    private boolean candidateValid;
    private long candidateFrames;
    private long candidateAtMicros;
    private long outliers;
    private long resetAtMicros;
    private boolean valid;
    private long frames;
    private long presentedAtMicros;
    private long accepted;
    private long rejected;
    private long revision;

    public PlaybackTracker(int sampleRate) {
        this(sampleRate, Long.MAX_VALUE);
    }

    /**
     * @param outlierMicros a report that is off the extrapolation of the last one by more than this is not taken at once:
     *                      it must be confirmed by the next report (a lone odd report would make the engine think the
     *                      output is far ahead or behind, and stop writing or skip audio)
     */
    public PlaybackTracker(int sampleRate, long outlierMicros) {
        this.sampleRate = sampleRate;
        this.outlierMicros = outlierMicros;
    }

    /** Forget everything: the output was flushed at {@code nowMicros}, positions older than that do not count. */
    public void reset(long nowMicros) {
        valid = false;
        frames = 0;
        presentedAtMicros = 0;
        resetAtMicros = nowMicros;
        candidateValid = false;
        revision++;
    }

    /**
     * @return true when the report was taken as a new position
     */
    public boolean update(PlaybackPosition position, long writtenFrames, long nowMicros) {
        if (position == null) {
            return false;
        }
        long f = position.getFrames();
        long at = position.getPresentedAtMicros();
        if (f < 0 || f > writtenFrames || at < resetAtMicros || at - nowMicros > MAX_FUTURE_MICROS
                || nowMicros - at > MAX_AGE_MICROS) {
            rejected++;
            return false;
        }
        if (valid && f <= frames) {
            if (f < frames) {
                rejected++; // going backwards is never true
            }
            return false; // the same report again, or the output stands still
        }
        if (valid && at <= presentedAtMicros) {
            rejected++; // more frames at an earlier time
            return false;
        }
        if (valid && outlierMicros != Long.MAX_VALUE) {
            if (offLine(frames, presentedAtMicros, f, at)) {
                boolean confirmed = candidateValid && !offLine(candidateFrames, candidateAtMicros, f, at);
                candidateFrames = f;
                candidateAtMicros = at;
                candidateValid = true;
                if (!confirmed) {
                    outliers++;
                    return false;
                }
            }
            candidateValid = false;
        }
        frames = f;
        presentedAtMicros = at;
        valid = true;
        accepted++;
        revision++;
        return true;
    }

    /** True when the report (f, at) is not where the line through (f0, at0) at the nominal rate puts it. */
    private boolean offLine(long f0, long at0, long f, long at) {
        long expected = f0 + Math.floorDiv((at - at0) * (long) sampleRate, 1_000_000L);
        return Math.abs(f - expected) * 1_000_000L / sampleRate > outlierMicros;
    }

    /** Reports that were held back because they were off the line (and not confirmed by the next one). */
    public long outlierCount() {
        return outliers;
    }

    /** For tests: the output has counted {@code delta} frames more. */
    void debugShift(long delta) {
        frames += delta;
    }

    public boolean isValid() {
        return valid;
    }

    /** The engine clock time at which the frame with this index (counted from the last flush) is presented. */
    public long presentationMicros(long frame) {
        return presentedAtMicros + Math.floorDiv((frame - frames) * 1_000_000L, (long) sampleRate);
    }

    /** How old the newest accepted position is, or -1 when there is none. */
    public long ageMicros(long nowMicros) {
        return valid ? nowMicros - presentedAtMicros : -1;
    }

    public long acceptedCount() {
        return accepted;
    }

    public long rejectedCount() {
        return rejected;
    }

    /** Grows whenever a new position was taken or the tracker was reset. */
    public long revision() {
        return revision;
    }
}
