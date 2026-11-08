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

import java.io.IOException;

/**
 * Streams the body of a 200 or 206 answer from a {@link PositionalSource}: exactly the bytes the
 * {@link ByteRangeResolver.Result} names ({@code start()} to {@code endInclusive()}), in chunks of
 * at most the chunk size, so a large file is never held in memory. Plain Java.
 * <p>
 * Two ways to use it: {@link #read} hands out one chunk at a time (what an asynchronous response
 * producer needs, one call per "output is ready"), {@link #serve} pumps the whole body into a
 * {@link ChunkSink} (blocking). Both stop with an {@link IOException} as soon as the source is
 * closing or ends before the promised length (the response header already announced the length,
 * so a short body must abort the connection), and {@code serve} stops as soon as the sink fails.
 * The status (200, 206 or 416) and its headers are the caller's business; a 416 has no body.
 */
public final class RangeStreamer {

    /** Default chunk size: 64 KiB. */
    public static final int DEFAULT_CHUNK_BYTES = 64 * 1024;

    private final PositionalSource source;
    private final int chunkBytes;
    private long position;
    private long remaining;

    /**
     * @param result a 200 or 206 result that was resolved against this source's size
     * @throws IllegalArgumentException for a null argument, a 416 or unknown-length result, a
     *                                  result that does not fit the source or a non-positive chunk size
     */
    public RangeStreamer(PositionalSource source, ByteRangeResolver.Result result, int chunkBytes) {
        if (source == null || result == null) {
            throw new IllegalArgumentException("source and result must not be null");
        }
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("chunkBytes must be positive");
        }
        if (result.status() != 200 && result.status() != 206) {
            throw new IllegalArgumentException("only 200 and 206 have a body");
        }
        long length = result.length();
        long start = result.start();
        if (length < 0L || start < 0L || start > source.size() || length > source.size() - start) {
            throw new IllegalArgumentException("result does not fit the source");
        }
        this.source = source;
        this.chunkBytes = chunkBytes;
        this.position = start;
        this.remaining = length;
    }

    public RangeStreamer(PositionalSource source, ByteRangeResolver.Result result) {
        this(source, result, DEFAULT_CHUNK_BYTES);
    }

    /** @return how many body bytes are still to be read */
    public long remaining() {
        return remaining;
    }

    /**
     * Reads the next chunk: at most {@code len}, at most the chunk size, at most what is left.
     *
     * @return the number of bytes (&gt; 0), 0 only for {@code len == 0}, or -1 when the body is
     * complete
     * @throws IOException if the source is closing or closed, fails, or ends before the promised
     *                     length
     */
    public int read(byte[] buf, int off, int len) throws IOException {
        if (buf == null || off < 0 || len < 0 || off > buf.length - len) {
            throw new IllegalArgumentException("invalid buffer range");
        }
        if (remaining == 0L) {
            return -1;
        }
        if (len == 0) {
            return 0;
        }
        int want = (int) Math.min(Math.min((long) len, (long) chunkBytes), remaining);
        int read = source.readAt(position, buf, off, want);
        if (read <= 0) {
            throw new IOException("source ended before the promised length");
        }
        position += read;
        remaining -= read;
        return read;
    }

    /** Same with {@link #DEFAULT_CHUNK_BYTES}. */
    public static void serve(PositionalSource source, ByteRangeResolver.Result result, ChunkSink sink)
            throws IOException {
        serve(source, result, sink, DEFAULT_CHUNK_BYTES);
    }

    /**
     * Writes the whole body to the sink.
     *
     * @throws IOException              from the sink (streaming stops at once) or if the source is
     *                                  closing, fails or ends early
     * @throws IllegalArgumentException as for the constructor, and for a null sink
     */
    public static void serve(PositionalSource source, ByteRangeResolver.Result result, ChunkSink sink,
                             int chunkBytes) throws IOException {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        RangeStreamer streamer = new RangeStreamer(source, result, chunkBytes);
        byte[] buffer = new byte[(int) Math.min((long) chunkBytes, Math.max(1L, streamer.remaining()))];
        int read;
        while ((read = streamer.read(buffer, 0, buffer.length)) != -1) {
            sink.write(buffer, 0, read);
        }
    }
}
