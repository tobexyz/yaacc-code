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
package de.yaacc.multiroom.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Decides when a chunk is to be played. A chunk is recorded at the server time {@code timestamp}, the server delays
 * everything by {@code bufferMs}, and this client's own {@code latency} setting moves its playback earlier (a slow
 * output) or later: the chunk is played at the server time {@code timestamp + bufferMs - latency}, which is the client
 * time {@code timestamp + bufferMs - latency - offset} on the clock of the {@link OffsetSource}. All times are
 * microseconds. The timestamps are the server's and are not trusted.
 *
 * <p><b>Decisions.</b>
 * <ul>
 * <li>{@code DROP_TOO_FAR_AHEAD}: the timestamp is more than 5 s after the corrected clock, that is the play time is
 * more than {@code bufferMs + 5 s} away (the client's latency setting is left out of that test, so that a latency of
 * -10 s does not turn every fresh chunk into one that is "too far ahead").</li>
 * <li>{@code DROP_LATE}: the play time is in the past by more than the tolerance (default 0).</li>
 * <li>{@code PLAY_AT}: the play time is at most {@code releaseLead} away (default 250 ms): the caller gets the time
 * and plays the chunk then.</li>
 * <li>{@code WAIT}: the play time is further away; the chunk waits in the queue and {@link #releaseDue()} hands it
 * over later. Also {@code WAIT}: the codec header was not accepted yet, or the clock is not synchronised.</li>
 * <li>{@code DROP_QUEUE_FULL}: the queue holds the maximum number of chunks or bytes (the new chunk is dropped).</li>
 * </ul>
 * No chunk is released before {@link #codecHeaderAccepted()}. When the offset source starts a new epoch (the server
 * clock jumped) the queue is emptied, because its chunks carry the old clock. The queue is ordered by timestamp.
 *
 * <p>Thread safe.
 */
public final class ChunkScheduler {

    /** What to do with a chunk. */
    public enum Action {
        PLAY_AT, WAIT, DROP_LATE, DROP_TOO_FAR_AHEAD, DROP_QUEUE_FULL
    }

    /** The decision and, for PLAY_AT and WAIT with a synchronised clock, the client time to play at. */
    public static final class Decision {
        private final Action action;
        private final long playAtClientMicros;

        Decision(Action action, long playAtClientMicros) {
            this.action = action;
            this.playAtClientMicros = playAtClientMicros;
        }

        public Action getAction() {
            return action;
        }

        /** The play time on the client clock; meaningful for PLAY_AT and for WAIT while the clock is synchronised. */
        public long getPlayAtClientMicros() {
            return playAtClientMicros;
        }
    }

    /** A chunk that is due, with the client time to play it at. */
    public static final class ScheduledChunk {
        private final WireChunkMessage chunk;
        private final long playAtClientMicros;

        ScheduledChunk(WireChunkMessage chunk, long playAtClientMicros) {
            this.chunk = chunk;
            this.playAtClientMicros = playAtClientMicros;
        }

        public WireChunkMessage getChunk() {
            return chunk;
        }

        public long getPlayAtClientMicros() {
            return playAtClientMicros;
        }
    }

    public static final class Config {
        public static final int DEFAULT_MAX_QUEUED_CHUNKS = 512;
        public static final long DEFAULT_MAX_QUEUED_BYTES = 4L * 1024 * 1024;

        private long lateToleranceMicros = 0;
        private long releaseLeadMicros = 250_000;
        private int maxQueuedChunks = DEFAULT_MAX_QUEUED_CHUNKS;
        private long maxQueuedBytes = DEFAULT_MAX_QUEUED_BYTES;

        /** How late a chunk may be and still be played (default 0). */
        public Config lateToleranceMs(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("tolerance must not be negative");
            }
            lateToleranceMicros = value * 1000L;
            return this;
        }

        /** How long before its time a chunk is handed over (default 250 ms). */
        public Config releaseLeadMs(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("lead must not be negative");
            }
            releaseLeadMicros = value * 1000L;
            return this;
        }

        /** The most chunks that wait (default 512, about 10 s of 20 ms chunks). */
        public Config maxQueuedChunks(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("at least one chunk");
            }
            maxQueuedChunks = value;
            return this;
        }

        /** The most payload bytes that wait (default 4 MiB). */
        public Config maxQueuedBytes(long value) {
            if (value < 1) {
                throw new IllegalArgumentException("at least one byte");
            }
            maxQueuedBytes = value;
            return this;
        }
    }

    /** A chunk may be stamped this far after the corrected clock (microseconds). */
    static final long TOO_FAR_AHEAD_MICROS = 5_000_000L;

    private static final class Entry implements Comparable<Entry> {
        final WireChunkMessage chunk;
        final long timestamp;
        final long sequence;

        Entry(WireChunkMessage chunk, long sequence) {
            this.chunk = chunk;
            this.timestamp = chunk.timestampMicros();
            this.sequence = sequence;
        }

        @Override
        public int compareTo(Entry other) {
            int byTime = Long.compare(timestamp, other.timestamp);
            return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
        }
    }

    private final OffsetSource offsets;
    private final MicroClock clock;
    private final long lateToleranceMicros;
    private final long releaseLeadMicros;
    private final int maxQueuedChunks;
    private final long maxQueuedBytes;
    private final PriorityQueue<Entry> queue = new PriorityQueue<>();
    private long queuedBytes;
    private long sequence;
    private int seenEpoch;
    private boolean headerAccepted;
    private long bufferMicros = SnapcastProtocolLimits.DEFAULT_BUFFER_MS * 1000L;
    private long latencyMicros;
    private long droppedLate;
    private long droppedTooFarAhead;
    private long droppedQueueFull;
    private long flushed;

    public ChunkScheduler(OffsetSource offsets, MicroClock clock, Config config) {
        this.offsets = offsets;
        this.clock = clock;
        this.lateToleranceMicros = config.lateToleranceMicros;
        this.releaseLeadMicros = config.releaseLeadMicros;
        this.maxQueuedChunks = config.maxQueuedChunks;
        this.maxQueuedBytes = config.maxQueuedBytes;
        this.seenEpoch = offsets.epoch();
    }

    /** The buffer of the server settings, clamped to 0 .. 10 000 ms. */
    public synchronized void setBufferMs(int bufferMs) {
        bufferMicros = ServerSettingsMessage.clamp(bufferMs, SnapcastProtocolLimits.MIN_BUFFER_MS,
                SnapcastProtocolLimits.MAX_BUFFER_MS) * 1000L;
    }

    /** The client latency of the server settings (plus the device's own), clamped to -10 000 .. 10 000 ms. */
    public synchronized void setLatencyMs(int latencyMs) {
        latencyMicros = ServerSettingsMessage.clamp(latencyMs, SnapcastProtocolLimits.MIN_LATENCY_MS,
                SnapcastProtocolLimits.MAX_LATENCY_MS) * 1000L;
    }

    /** The codec header was accepted: chunks may be released from now on. */
    public synchronized void codecHeaderAccepted() {
        headerAccepted = true;
    }

    /** A new connection or a new stream: the queue is emptied and chunks wait for the next accepted header. */
    public synchronized void reset() {
        queue.clear();
        queuedBytes = 0;
        headerAccepted = false;
        seenEpoch = offsets.epoch();
    }

    /** Decides what to do with a chunk; a chunk that has to wait is queued. */
    public synchronized Decision offer(WireChunkMessage chunk) {
        flushIfNewEpoch();
        long now = clock.nowMicros();
        if (!headerAccepted || !offsets.isSynchronized()) {
            return enqueue(chunk, 0);
        }
        Decision decision = classify(chunk.timestampMicros(), now, offsets.offsetMicros());
        switch (decision.getAction()) {
            case DROP_LATE:
                droppedLate++;
                return decision;
            case DROP_TOO_FAR_AHEAD:
                droppedTooFarAhead++;
                return decision;
            case WAIT:
                return enqueue(chunk, decision.getPlayAtClientMicros());
            default:
                return decision;
        }
    }

    /**
     * The chunks whose time is near, in the order of their timestamps; chunks that became late or too far ahead while
     * they waited are dropped. Empty before the codec header was accepted and while the clock is not synchronised.
     */
    public synchronized List<ScheduledChunk> releaseDue() {
        flushIfNewEpoch();
        List<ScheduledChunk> due = new ArrayList<>();
        if (!headerAccepted || !offsets.isSynchronized()) {
            return due;
        }
        long now = clock.nowMicros();
        long offset = offsets.offsetMicros();
        while (!queue.isEmpty()) {
            Entry head = queue.peek();
            Decision decision = classify(head.timestamp, now, offset);
            if (decision.getAction() == Action.WAIT) {
                break;
            }
            queue.poll();
            queuedBytes -= head.chunk.getPayloadLength();
            switch (decision.getAction()) {
                case DROP_LATE:
                    droppedLate++;
                    break;
                case DROP_TOO_FAR_AHEAD:
                    droppedTooFarAhead++;
                    break;
                default:
                    due.add(new ScheduledChunk(head.chunk, decision.getPlayAtClientMicros()));
                    break;
            }
        }
        return due;
    }

    private Decision classify(long timestamp, long clientNow, long offset) {
        long serverNow = add(clientNow, offset);
        if (sub(timestamp, serverNow) > TOO_FAR_AHEAD_MICROS) {
            return new Decision(Action.DROP_TOO_FAR_AHEAD, 0);
        }
        long playAtServer = sub(add(timestamp, bufferMicros), latencyMicros);
        long playAtClient = sub(playAtServer, offset);
        long untilPlay = sub(playAtClient, clientNow);
        if (untilPlay < -lateToleranceMicros) {
            return new Decision(Action.DROP_LATE, playAtClient);
        }
        return new Decision(untilPlay > releaseLeadMicros ? Action.WAIT : Action.PLAY_AT, playAtClient);
    }

    private Decision enqueue(WireChunkMessage chunk, long playAt) {
        int size = chunk.getPayloadLength();
        if (queue.size() >= maxQueuedChunks || queuedBytes + size > maxQueuedBytes) {
            droppedQueueFull++;
            return new Decision(Action.DROP_QUEUE_FULL, 0);
        }
        queue.add(new Entry(chunk, sequence++));
        queuedBytes += size;
        return new Decision(Action.WAIT, playAt);
    }

    private void flushIfNewEpoch() {
        int epoch = offsets.epoch();
        if (epoch != seenEpoch) {
            seenEpoch = epoch;
            flushed += queue.size();
            queue.clear();
            queuedBytes = 0;
        }
    }

    // ---- saturating arithmetic: offsets and timestamps come from the server

    private static long add(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    private static long sub(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    // ---- state for tests and the status line

    public synchronized int queuedChunks() {
        return queue.size();
    }

    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    public synchronized long droppedLate() {
        return droppedLate;
    }

    public synchronized long droppedTooFarAhead() {
        return droppedTooFarAhead;
    }

    public synchronized long droppedQueueFull() {
        return droppedQueueFull;
    }

    /** Chunks thrown away because the clock was found anew. */
    public synchronized long flushed() {
        return flushed;
    }
}
