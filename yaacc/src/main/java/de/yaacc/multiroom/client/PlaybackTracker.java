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
    private long resetAtMicros;
    private boolean valid;
    private long frames;
    private long presentedAtMicros;
    private long accepted;
    private long rejected;
    private long revision;

    public PlaybackTracker(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    /** Forget everything: the output was flushed at {@code nowMicros}, positions older than that do not count. */
    public void reset(long nowMicros) {
        valid = false;
        frames = 0;
        presentedAtMicros = 0;
        resetAtMicros = nowMicros;
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
        frames = f;
        presentedAtMicros = at;
        valid = true;
        accepted++;
        revision++;
        return true;
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
