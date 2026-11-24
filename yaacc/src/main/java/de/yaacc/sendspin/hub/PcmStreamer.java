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
package de.yaacc.sendspin.hub;

import de.yaacc.multiroom.feed.PcmSink;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Turns the PCM that the player of YAACC produces (16 bit stereo, little endian, at the rate the Sendspin player listed) into the
 * timestamped chunks of a Sendspin stream and sends them with a fixed lead: a chunk goes out when it is {@code lead} ahead of the
 * moment it must play, so the player holds about {@code lead} of audio. That is the pacing the server owes the player: ExoPlayer
 * decodes faster than real time, and {@link #write} therefore waits (back pressure) until the chunk is due.
 * <p>
 * The timeline is continuous across tracks. It restarts at {@code now + lead} when a stream starts, after a {@link #discard()} (a
 * seek or a skip: {@code stream/clear}) and when the producer fell behind and the next chunk would be late (a gap in the sound, no
 * stream message). {@link #end()} sends {@code stream/end} (pause, stop) and refuses audio until {@link #begin()}; the stream starts
 * again with the next write. The audio a player may hold is bounded by its {@code buffer_capacity}: the bytes of the chunks that
 * have not finished playing, as the specification counts them (13 bytes of header and the payload of each chunk).
 * <p>
 * {@code write} is for one thread (the audio thread); {@code discard}, {@code end} and {@code begin} may come from another and
 * interrupt a wait in progress.
 */
public final class PcmStreamer implements PcmSink {

    /** What the streamer sends; the connection implements it. */
    public interface Output {
        void streamStart(long serverTransmittedMicros, int sampleRate) throws IOException;

        void streamClear(long serverTransmittedMicros) throws IOException;

        void streamEnd() throws IOException;

        void audio(long timestampMicros, long sendAheadMicros, byte[] pcm, int offset, int length) throws IOException;
    }

    /** What the player asked for, read at every chunk (it may change while a stream runs). */
    public interface Timing {
        /** How far ahead of its timestamp a chunk is sent, output delay included. */
        long leadMicros();

        /** The delay beyond the audio port that the player reported; chunks count as in the player's buffer until it has passed. */
        long outputDelayMicros();

        /** The player's {@code buffer_capacity} in bytes. */
        long capacityBytes();
    }

    /** Numbers for a log; no content. */
    public static final class Statistics {
        public long chunks;
        public long reanchors;
        public long clears;
        public long capacityWaits;
    }

    public static final int CHUNK_MILLIS = 20;
    private static final int FRAME_BYTES = 4;
    private static final int CHUNK_HEADER_BYTES = 13;
    /** The longest a single sleep of the wait lasts, so that a discard or a lead change is noticed quickly. */
    private static final long SLICE_MICROS = 20_000L;
    /** A chunk that would play less than this ahead of now is late: the timeline restarts. */
    private static final long LATE_MICROS = 30_000L;

    private final Output out;
    private final HubClock clock;
    private final Timing timing;
    private final int sampleRate;
    private final int chunkFrames;
    private final int chunkBytes;
    private final long chunkMicros;
    private final byte[] pending;
    private final Object lock = new Object();
    private final ArrayDeque<long[]> inFlight = new ArrayDeque<>(); // {completion time, bytes}
    private final Statistics statistics = new Statistics();

    private int pendingLength;
    private boolean streaming;
    private boolean accepting = true;
    private boolean failed;
    private long nextTimestamp;
    private long generation;
    private long inFlightBytes;

    public PcmStreamer(Output out, HubClock clock, Timing timing, int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 384000 || sampleRate % 50 != 0) {
            throw new IllegalArgumentException("sample rate");
        }
        this.out = out;
        this.clock = clock;
        this.timing = timing;
        this.sampleRate = sampleRate;
        this.chunkFrames = sampleRate * CHUNK_MILLIS / 1000; // exact: the rate is a multiple of 50
        this.chunkBytes = chunkFrames * FRAME_BYTES;
        this.chunkMicros = CHUNK_MILLIS * 1000L;
        this.pending = new byte[chunkBytes];
    }

    public Statistics statistics() {
        return statistics;
    }

    /** True while a stream is open at the player. */
    public boolean isStreaming() {
        synchronized (lock) {
            return streaming;
        }
    }

    // ------------------------------------------------------------------ PcmSink

    @Override
    public boolean write(byte[] data, int offset, int length) {
        int off = offset;
        int remaining = length;
        while (remaining > 0) {
            synchronized (lock) {
                if (failed) {
                    return false;
                }
                if (!accepting) {
                    return true; // the audio of a paused or stopped player: dropped
                }
                int n = Math.min(remaining, chunkBytes - pendingLength);
                System.arraycopy(data, off, pending, pendingLength, n);
                pendingLength += n;
                off += n;
                remaining -= n;
                if (pendingLength < chunkBytes) {
                    return true;
                }
            }
            if (!sendPendingChunk()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void discard() {
        synchronized (lock) {
            generation++;
            pendingLength = 0;
            if (streaming && !failed) {
                try {
                    out.streamClear(clock.nowMicros());
                    statistics.clears++;
                } catch (IOException e) {
                    failed = true;
                }
                nextTimestamp = clock.nowMicros() + timing.leadMicros();
                inFlight.clear();
                inFlightBytes = 0;
            }
        }
    }

    /** Pause or stop: {@code stream/end}; audio is refused until {@link #begin()}. */
    public void end() {
        synchronized (lock) {
            generation++;
            pendingLength = 0;
            accepting = false;
            if (streaming && !failed) {
                try {
                    out.streamEnd();
                } catch (IOException e) {
                    failed = true;
                }
            }
            streaming = false;
            inFlight.clear();
            inFlightBytes = 0;
        }
    }

    /** Play or resume: audio is accepted again; the stream starts with the next write. */
    public void begin() {
        synchronized (lock) {
            accepting = true;
        }
    }

    public boolean hasFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    // ------------------------------------------------------------------ one chunk

    private boolean sendPendingChunk() {
        final long myGeneration;
        synchronized (lock) {
            myGeneration = generation;
        }
        while (true) {
            long wait;
            synchronized (lock) {
                if (failed) {
                    return false;
                }
                if (myGeneration != generation || !accepting) {
                    return true; // a discard or an end overtook this chunk: it is stale
                }
                long now = clock.nowMicros();
                long lead = timing.leadMicros();
                try {
                    if (!streaming) {
                        out.streamStart(now, sampleRate);
                        streaming = true;
                        nextTimestamp = now + lead;
                        inFlight.clear();
                        inFlightBytes = 0;
                    } else if (nextTimestamp < now + LATE_MICROS) {
                        nextTimestamp = now + lead; // the producer fell behind: a gap, and the timeline starts again
                        statistics.reanchors++;
                    }
                } catch (IOException e) {
                    failed = true;
                    return false;
                }
                // what the player has finished with is no longer in its buffer
                while (!inFlight.isEmpty() && inFlight.peekFirst()[0] <= now) {
                    inFlightBytes -= inFlight.pollFirst()[1];
                }
                long sendAt = nextTimestamp - lead;
                wait = sendAt - now;
                long size = CHUNK_HEADER_BYTES + chunkBytes;
                if (wait <= 0 && inFlightBytes + size > timing.capacityBytes() && !inFlight.isEmpty()) {
                    wait = Math.max(1000L, inFlight.peekFirst()[0] - now);
                    statistics.capacityWaits++;
                }
                if (wait <= 0) {
                    try {
                        out.audio(nextTimestamp, nextTimestamp - now, pending, 0, chunkBytes);
                    } catch (IOException e) {
                        failed = true;
                        return false;
                    }
                    statistics.chunks++;
                    inFlight.addLast(new long[]{nextTimestamp + chunkMicros - timing.outputDelayMicros(), size});
                    inFlightBytes += size;
                    nextTimestamp += chunkMicros;
                    pendingLength = 0;
                    return true;
                }
            }
            try {
                clock.sleepMicros(Math.min(wait, SLICE_MICROS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
