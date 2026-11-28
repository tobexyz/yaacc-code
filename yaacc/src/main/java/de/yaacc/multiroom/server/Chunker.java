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
package de.yaacc.multiroom.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The timeline of one stream (issue #330, design "Timeline and chunks"): the PCM of a producer is cut into chunks of about 20 ms and each
 * chunk is stamped with the stream clock, in microseconds of the server clock. The clock starts with the first data after a start or a
 * {@link #reset()}; every chunk adds its exact duration (counted in frames, so that a duration that is not a whole number of microseconds
 * does not drift). A chunk that is completed more than one chunk after its time restarts the clock from now, as snapserver does after a late
 * read (a gap that the clients hear and then play on). Not thread safe: one reader thread.
 * <p>
 * A client plays a chunk at {@code stamp + bufferMs - latency}, so the stamp is the time at which the audio was produced, not the time at
 * which it is to be heard.
 */
public final class Chunker {

    /** One chunk: the stamp and the PCM (a whole number of frames). */
    public static final class Chunk {
        public final long timestampMicros;
        public final byte[] pcm;

        Chunk(long timestampMicros, byte[] pcm) {
            this.timestampMicros = timestampMicros;
            this.pcm = pcm;
        }
    }

    /** The chunk duration of snapserver's default ({@code chunk_ms}). */
    public static final int CHUNK_MILLIS = 20;

    private final int sampleRate;
    private final int frameBytes;
    private final int chunkFrames;
    private final int chunkBytes;
    private final long chunkMicros;
    private final byte[] pending;

    private int pendingLength;
    private boolean started;
    private long baseMicros;
    private long framesCut;
    private long lastEndMicros;
    private int resyncs;

    public Chunker(int sampleRate, int frameBytes) {
        if (frameBytes <= 0 || sampleRate < 1000 || sampleRate > 384000) {
            throw new IllegalArgumentException("format");
        }
        this.sampleRate = sampleRate;
        this.frameBytes = frameBytes;
        this.chunkFrames = sampleRate * CHUNK_MILLIS / 1000;
        this.chunkBytes = chunkFrames * frameBytes;
        this.chunkMicros = CHUNK_MILLIS * 1000L;
        this.pending = new byte[chunkBytes];
    }

    public int chunkFrames() {
        return chunkFrames;
    }

    public int chunkBytes() {
        return chunkBytes;
    }

    /** How often the clock was restarted because a chunk was late. */
    public int resyncs() {
        return resyncs;
    }

    /** The producer went: what was pending is dropped and the clock starts again with the next data. */
    public void reset() {
        pendingLength = 0;
        started = false;
        framesCut = 0;
    }

    /**
     * Takes data that arrived at {@code nowMicros} (server clock).
     *
     * @return the chunks that are complete now, in order (often none or one)
     */
    public List<Chunk> feed(byte[] data, int offset, int length, long nowMicros) {
        List<Chunk> out = new ArrayList<>(2);
        if (length <= 0) {
            return out;
        }
        if (!started) {
            started = true;
            baseMicros = nowMicros;
            framesCut = 0;
        }
        int off = offset;
        int remaining = length;
        while (remaining > 0) {
            int n = Math.min(remaining, chunkBytes - pendingLength);
            System.arraycopy(data, off, pending, pendingLength, n);
            pendingLength += n;
            off += n;
            remaining -= n;
            if (pendingLength == chunkBytes) {
                out.add(cut(nowMicros));
                pendingLength = 0;
            }
        }
        return out;
    }

    private Chunk cut(long nowMicros) {
        long stamp = baseMicros + framesCut * 1_000_000L / sampleRate;
        long end = baseMicros + (framesCut + chunkFrames) * 1_000_000L / sampleRate;
        if (nowMicros - end > chunkMicros) {
            // late by more than one chunk: the clock starts again, so that this chunk ends now
            baseMicros = nowMicros - chunkFrames * 1_000_000L / sampleRate;
            framesCut = 0;
            stamp = baseMicros;
            end = baseMicros + chunkFrames * 1_000_000L / sampleRate;
            resyncs++;
        }
        framesCut += chunkFrames;
        lastEndMicros = end;
        return new Chunk(stamp, Arrays.copyOf(pending, chunkBytes));
    }

    /**
     * How long the reader waits before it takes the next data: until the chunk it has cut is over in real time (snapserver paces its
     * reads like this, so that a producer that is ahead is slowed by TCP and the queue stays small).
     */
    public long waitMicros(long nowMicros) {
        return Math.max(0L, lastEndMicros - nowMicros);
    }

    /** True if the PCM is only zeros (snapserver calls such a chunk silent even with a threshold of 0). */
    public static boolean isSilent(byte[] pcm) {
        for (byte b : pcm) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** For tests and logs: the frame size of this chunker. */
    int frameBytes() {
        return frameBytes;
    }
}
