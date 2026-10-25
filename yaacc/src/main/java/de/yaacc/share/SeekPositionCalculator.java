/*
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
package de.yaacc.share;

/**
 * The estimate behind the server-side time range management for renderers that cannot seek
 * themselves: from a playback time to a byte position of the file. Plain Java, long/double
 * arithmetic that cannot overflow; every result is inside the file.
 * <p>
 * The formula is the one of the original implementation: the first 10 percent of the file are
 * treated as header/metadata, the playback time is spread over the remaining 90 percent, and
 * the result never goes beyond 85 percent of the file. The duration is guessed from the file
 * size assuming 128 kbit/s.
 */
public final class SeekPositionCalculator {

    private static final double HEADER_FREE_SHARE = 0.9;
    private static final double MAX_SHARE = 0.85;
    /** 128 * 1024 bit/s in bytes per ... see {@link #estimateDurationMs}. */
    private static final long ASSUMED_BITS_PER_SECOND = 128L * 1024L;

    private SeekPositionCalculator() {
    }

    /**
     * @param totalSize       size of the file in bytes; &lt;= 0 means unknown
     * @param timeMs          playback time in milliseconds
     * @param totalDurationMs duration in milliseconds; &lt;= 0 means unknown
     * @return the byte position, 0 if the size or the duration is unknown, never negative and
     * never beyond 85 percent of the file
     */
    public static long bytePositionForTime(long totalSize, long timeMs, long totalDurationMs) {
        if (totalSize <= 0L || totalDurationMs <= 0L) {
            return 0L;
        }
        double ratio = (double) timeMs / (double) totalDurationMs;
        long dataSize = (long) (totalSize * HEADER_FREE_SHARE);
        long headerSize = totalSize - dataSize;
        double estimate = headerSize + dataSize * ratio;
        double max = (double) (long) (totalSize * MAX_SHARE);
        if (Double.isNaN(estimate) || estimate < 0.0) {
            return 0L;
        }
        return (long) Math.min(estimate, max);
    }

    /**
     * Guesses the duration of a file from its size at 128 kbit/s:
     * {@code size * 8 * 1000 / (128 * 1024)}, computed without overflow.
     *
     * @return the duration in milliseconds, 0 for an unknown (&lt;= 0) size
     */
    public static long estimateDurationMs(long totalSize) {
        if (totalSize <= 0L) {
            return 0L;
        }
        long whole = totalSize / ASSUMED_BITS_PER_SECOND;
        long rest = totalSize % ASSUMED_BITS_PER_SECOND;
        return whole * 8000L + rest * 8000L / ASSUMED_BITS_PER_SECOND;
    }
}
