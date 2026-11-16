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
package de.yaacc.multiroom.feed;

/**
 * The pacing math of the feed: when may the next bytes of a real-time PCM stream be written?
 *
 * <p>The server reads its tcp source in real time, one chunk per {@code chunk_ms}, and a late read makes it resync
 * (an audible break). So the feeder writes at <b>absolute</b> deadlines derived from one anchor time and the number of
 * bytes sent since: a late wake-up does not push the following writes back, and a slow loop does not accumulate a
 * delay. Two rules keep the producer from running away:
 * <ul>
 *   <li><b>Look-ahead.</b> The audio written so far may end at most {@code lookAheadChunks} chunks (default 3, 60 ms)
 *       after "now". Everything in socket buffers beyond that would delay a pause or a stop.</li>
 *   <li><b>No catching up.</b> When a write comes more than {@code stallChunks} chunks (default 2) after the time its
 *       audio was due, the pacer <b>re-anchors</b> to now instead of writing the missing chunks as a burst: the gap is
 *       lost (the server resyncs once), there is never an accumulating debt, and the stream is never faster than
 *       real time. A write that is late by up to the limit is caught up by the next writes, which are due at once.</li>
 * </ul>
 * Writes of any size are accounted by their bytes, so partial chunks are fine. The pacer only calculates; the caller
 * sleeps and writes. It is thread safe and has no thread of its own.
 */
public final class RealTimePacer {

    /** The default look-ahead in chunks. */
    public static final int DEFAULT_LOOK_AHEAD_CHUNKS = 3;
    /** The default number of chunks a write may be late before the pacer re-anchors. */
    public static final int DEFAULT_STALL_CHUNKS = 2;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final FeedClock clock;
    private final long bytesPerSecond;
    private final int chunkBytes;
    private final long chunkNanos;
    private final long lookAheadNanos;
    private final long stallNanos;

    private boolean anchored;
    private long anchorNanos;
    /** Bytes written since the anchor; always below one second's worth (the anchor moves up by whole seconds). */
    private long sentBytes;

    private long lateChunks;
    private long reanchors;
    private long maxLeadNanos;

    /**
     * @param chunkBytes the size of one chunk of the stream (whole frames); look-ahead and stall limit are counted in these
     */
    public RealTimePacer(FeedClock clock, int bytesPerSecond, int chunkBytes, int lookAheadChunks, int stallChunks) {
        if (clock == null || bytesPerSecond < 1 || chunkBytes < 1 || lookAheadChunks < 1 || stallChunks < 1) {
            throw new IllegalArgumentException("bad pacer arguments");
        }
        this.clock = clock;
        this.bytesPerSecond = bytesPerSecond;
        this.chunkBytes = chunkBytes;
        this.chunkNanos = nanosFor(chunkBytes);
        this.lookAheadNanos = nanosFor((long) chunkBytes * lookAheadChunks);
        this.stallNanos = nanosFor((long) chunkBytes * stallChunks);
    }

    /** The default look-ahead and stall limit, chunks of {@code chunkMillis} milliseconds (whole frames). */
    public RealTimePacer(FeedClock clock, PcmFormat format, int chunkMillis) {
        this(clock, format.bytesPerSecond(), chunkBytesFor(format, chunkMillis), DEFAULT_LOOK_AHEAD_CHUNKS,
                DEFAULT_STALL_CHUNKS);
    }

    /** The size of a chunk of {@code chunkMillis} ms in whole frames, at least one frame. */
    public static int chunkBytesFor(PcmFormat format, int chunkMillis) {
        if (chunkMillis < 1) {
            throw new IllegalArgumentException("chunk length out of range");
        }
        long frames = (long) format.getSampleRate() * chunkMillis / 1000;
        return (int) Math.max(1, frames) * format.bytesPerFrame();
    }

    private long nanosFor(long bytes) {
        return bytes * NANOS_PER_SECOND / bytesPerSecond;
    }

    public int getChunkBytes() {
        return chunkBytes;
    }

    public int getBytesPerSecond() {
        return (int) bytesPerSecond;
    }

    /**
     * How long to wait before {@code bytes} more may be written so that the audio sent so far ends no more than the
     * look-ahead after now. Zero when it may be written at once (also before the first write).
     */
    public synchronized long nanosUntilWritable(int bytes) {
        if (!anchored) {
            return 0;
        }
        long earliest = anchorNanos + nanosFor(sentBytes + bytes) - lookAheadNanos;
        return Math.max(0, earliest - clock.nanoTime());
    }

    /**
     * How long until the audio sent so far has been played, that is until the next byte is due. Zero when it is due
     * or overdue (also before the first write). Silence to avoid an underrun is written when this is zero.
     */
    public synchronized long nanosUntilDue() {
        if (!anchored) {
            return 0;
        }
        return Math.max(0, anchorNanos + nanosFor(sentBytes) - clock.nanoTime());
    }

    /**
     * Records that {@code bytes} were written now. The first write sets the anchor; a write that is more than the
     * stall limit late re-anchors (see the class comment).
     */
    public synchronized void onWritten(int bytes) {
        final long now = clock.nanoTime();
        if (!anchored) {
            anchored = true;
            anchorNanos = now;
            sentBytes = 0;
        } else {
            long lateness = now - (anchorNanos + nanosFor(sentBytes));
            if (lateness > chunkNanos) {
                lateChunks++;
            }
            if (lateness > stallNanos) {
                reanchors++;
                anchorNanos = now;
                sentBytes = 0;
            }
        }
        sentBytes += bytes;
        if (sentBytes >= bytesPerSecond) {
            long seconds = sentBytes / bytesPerSecond;
            sentBytes -= seconds * bytesPerSecond;
            anchorNanos += seconds * NANOS_PER_SECOND;
        }
        long lead = anchorNanos + nanosFor(sentBytes) - now;
        if (lead > maxLeadNanos) {
            maxLeadNanos = lead;
        }
    }

    /** Forgets the anchor: the next write is due at once and anchors the clock again (after a pause in the feed). */
    public synchronized void reset() {
        anchored = false;
        sentBytes = 0;
    }

    /**
     * The audio sent so far minus the time since the anchor: how far the producer is ahead of real time. Negative
     * when the audio has been played already (the stream is behind). Zero before the first write.
     */
    public synchronized long leadNanos() {
        if (!anchored) {
            return 0;
        }
        return anchorNanos + nanosFor(sentBytes) - clock.nanoTime();
    }

    /** The largest lead seen right after a write. */
    public synchronized long getMaxLeadNanos() {
        return maxLeadNanos;
    }

    /** Writes that came more than one chunk after their audio was due. */
    public synchronized long getLateChunks() {
        return lateChunks;
    }

    /** Stalls that made the pacer re-anchor. */
    public synchronized long getReanchors() {
        return reanchors;
    }
}
