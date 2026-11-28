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

import java.util.List;

/**
 * One stream of the server: the PCM of its producer goes through the {@link Chunker} (stream clock, 20 ms chunks), the
 * {@link StreamActivity} (idle or playing), the {@link ChunkEncoder}, and out to the clients of the groups that play the stream. One
 * producer thread calls {@link #write}; {@link #header()} may be read from any thread.
 */
public final class StreamPipeline {

    /** Where the chunks go and what the stream tells about itself. */
    public interface Sink {
        /** A chunk is ready for the clients of the stream. */
        void chunk(String streamId, long timestampMicros, byte[] payload);

        /** The stream became idle or playing. */
        void status(String streamId, StreamActivity.Status status);
    }

    private final String id;
    private final Chunker chunker;
    private final StreamActivity activity;
    private final ChunkEncoder encoder;
    private final Sink sink;
    private final long idleThresholdMillis;

    public StreamPipeline(String id, int sampleRate, int frameBytes, ChunkEncoder encoder, Sink sink) {
        this.id = id;
        this.chunker = new Chunker(sampleRate, frameBytes);
        this.activity = new StreamActivity(100, Chunker.CHUNK_MILLIS);
        this.encoder = encoder;
        this.sink = sink;
        this.idleThresholdMillis = 100;
    }

    public String id() {
        return id;
    }

    public ChunkEncoder encoder() {
        return encoder;
    }

    public int chunkBytes() {
        return chunker.chunkBytes();
    }

    /** How long the producer's reader waits before the next read (see {@link Chunker#waitMicros}). */
    public long waitMicros(long nowMicros) {
        return chunker.waitMicros(nowMicros);
    }

    /** PCM from the producer, read at {@code nowMicros} (server clock). */
    public void write(byte[] data, int offset, int length, long nowMicros) {
        List<Chunker.Chunk> chunks = chunker.feed(data, offset, length, nowMicros);
        for (Chunker.Chunk chunk : chunks) {
            StreamActivity.Status before = activity.status();
            activity.chunk(Chunker.isSilent(chunk.pcm));
            if (activity.status() != before) {
                sink.status(id, activity.status());
            }
            sink.chunk(id, chunk.timestampMicros, encoder.encode(chunk.pcm));
        }
    }

    /** The producer went: the stream is idle and its clock starts again with the next producer. */
    public void producerGone() {
        chunker.reset();
        StreamActivity.Status before = activity.status();
        activity.producerGone();
        if (before != activity.status()) {
            sink.status(id, activity.status());
        }
    }

    long idleThresholdMillis() {
        return idleThresholdMillis;
    }
}
