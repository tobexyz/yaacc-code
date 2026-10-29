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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import de.yaacc.share.PositionalSourceTest.ArraySource;

/**
 * Tests for {@link RangeStreamer}: the body of a 200 or 206 answer is exactly the bytes the
 * {@link ByteRangeResolver.Result} names, in chunks, and streaming stops when the sink fails or
 * the source is closing. Statuses 200/416 themselves are the caller's business.
 */
public class RangeStreamerTest {

    private static final int CHUNK = 16;

    private static byte[] sequence(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    private static ByteRangeResolver.Result resolve(String header, long length) {
        return ByteRangeResolver.resolve(ByteRangeResolver.parseHeader(header), length);
    }

    /** Collects what a sink receives. */
    private static class Collector implements ChunkSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Integer> chunkSizes = new ArrayList<>();

        @Override
        public void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
            chunkSizes.add(len);
        }
    }

    // ---- whole resource (200)

    @Test
    public void aWholeResourceIsStreamedInChunksOfAtMostTheChunkSize() throws IOException {
        byte[] data = sequence(CHUNK * 2 + CHUNK / 2);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve(null, data.length), sink, CHUNK);
        assertArrayEquals(data, sink.bytes.toByteArray());
        assertEquals(Arrays.asList(CHUNK, CHUNK, CHUNK / 2), sink.chunkSizes);
    }

    @Test
    public void aSizeThatIsAMultipleOfTheChunkHasNoTrailingEmptyChunk() throws IOException {
        byte[] data = sequence(CHUNK * 3);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve(null, data.length), sink, CHUNK);
        assertArrayEquals(data, sink.bytes.toByteArray());
        assertEquals(Arrays.asList(CHUNK, CHUNK, CHUNK), sink.chunkSizes);
    }

    @Test
    public void aResourceSmallerThanOneChunkIsOneChunk() throws IOException {
        byte[] data = sequence(5);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve(null, data.length), sink, CHUNK);
        assertEquals(Arrays.asList(5), sink.chunkSizes);
    }

    @Test
    public void anEmptyResourceWritesNothing() throws IOException {
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(new byte[0]), resolve(null, 0), sink, CHUNK);
        assertEquals(0, sink.bytes.size());
        assertTrue(sink.chunkSizes.isEmpty());
    }

    @Test
    public void theDefaultChunkSizeIs64KiB() throws IOException {
        assertEquals(64 * 1024, RangeStreamer.DEFAULT_CHUNK_BYTES);
        byte[] data = sequence(RangeStreamer.DEFAULT_CHUNK_BYTES * 2 + 1);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve(null, data.length), sink);
        assertEquals(Arrays.asList(RangeStreamer.DEFAULT_CHUNK_BYTES, RangeStreamer.DEFAULT_CHUNK_BYTES, 1),
                sink.chunkSizes);
        assertArrayEquals(data, sink.bytes.toByteArray());
    }

    // ---- ranges (206)

    @Test
    public void aClosedRangeStreamsExactlyThoseBytes() throws IOException {
        byte[] data = sequence(100);
        Collector sink = new Collector();
        ByteRangeResolver.Result result = resolve("bytes=10-19", data.length);
        assertEquals(206, result.status());
        RangeStreamer.serve(new ArraySource(data), result, sink, CHUNK);
        assertArrayEquals(Arrays.copyOfRange(data, 10, 20), sink.bytes.toByteArray());
    }

    @Test
    public void aSingleByteRangeStreamsOneByte() throws IOException {
        byte[] data = sequence(100);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve("bytes=99-99", data.length), sink, CHUNK);
        assertArrayEquals(new byte[]{data[99]}, sink.bytes.toByteArray());
    }

    @Test
    public void anOpenEndedRangeStreamsToTheLastByte() throws IOException {
        byte[] data = sequence(100);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve("bytes=70-", data.length), sink, CHUNK);
        assertArrayEquals(Arrays.copyOfRange(data, 70, 100), sink.bytes.toByteArray());
    }

    @Test
    public void aSuffixRangeStreamsTheLastBytes() throws IOException {
        byte[] data = sequence(100);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve("bytes=-5", data.length), sink, CHUNK);
        assertArrayEquals(Arrays.copyOfRange(data, 95, 100), sink.bytes.toByteArray());
    }

    @Test
    public void aRangeBeyondTheEndIsClippedByTheResolverAndStreamsToTheEnd() throws IOException {
        byte[] data = sequence(100);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve("bytes=90-5000", data.length), sink, CHUNK);
        assertArrayEquals(Arrays.copyOfRange(data, 90, 100), sink.bytes.toByteArray());
    }

    @Test
    public void aRangeCrossingSeveralChunksIsChunkedFromItsStart() throws IOException {
        byte[] data = sequence(200);
        Collector sink = new Collector();
        RangeStreamer.serve(new ArraySource(data), resolve("bytes=3-40", data.length), sink, CHUNK);
        assertArrayEquals(Arrays.copyOfRange(data, 3, 41), sink.bytes.toByteArray());
        assertEquals(Arrays.asList(16, 16, 6), sink.chunkSizes);
    }

    @Test
    public void aFullCoverRangeStreamsEverything() throws IOException {
        byte[] data = sequence(40);
        Collector sink = new Collector();
        ByteRangeResolver.Result result = resolve("bytes=0-", data.length);
        assertEquals(206, result.status());
        RangeStreamer.serve(new ArraySource(data), result, sink, CHUNK);
        assertArrayEquals(data, sink.bytes.toByteArray());
    }

    // ---- incremental reading (what the async producer uses)

    @Test
    public void readDeliversChunksUntilMinusOneAndCountsDown() throws IOException {
        byte[] data = sequence(40);
        RangeStreamer streamer = new RangeStreamer(new ArraySource(data), resolve("bytes=5-34", data.length), CHUNK);
        assertEquals(30L, streamer.remaining());
        byte[] buf = new byte[64];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n;
        int calls = 0;
        while ((n = streamer.read(buf, 0, buf.length)) != -1) {
            assertTrue(n > 0 && n <= CHUNK);
            out.write(buf, 0, n);
            calls++;
        }
        assertEquals(2, calls);
        assertEquals(0L, streamer.remaining());
        assertArrayEquals(Arrays.copyOfRange(data, 5, 35), out.toByteArray());
        assertEquals(-1, streamer.read(buf, 0, buf.length));
    }

    @Test
    public void readNeverReturnsMoreThanTheCallersBufferAllows() throws IOException {
        byte[] data = sequence(40);
        RangeStreamer streamer = new RangeStreamer(new ArraySource(data), resolve(null, data.length), CHUNK);
        assertEquals(4, streamer.read(new byte[100], 10, 4));
        assertEquals(36L, streamer.remaining());
    }

    @Test
    public void readIntoAnEmptyBufferReturnsZero() throws IOException {
        byte[] data = sequence(10);
        RangeStreamer streamer = new RangeStreamer(new ArraySource(data), resolve(null, data.length), CHUNK);
        assertEquals(0, streamer.read(new byte[4], 0, 0));
        assertEquals(10L, streamer.remaining());
    }

    // ---- stopping

    @Test
    public void aSinkFailureStopsReadingAndPropagates() {
        byte[] data = sequence(CHUNK * 10);
        final int[] writes = new int[1];
        ArraySource source = new ArraySource(data);
        final int[] reads = new int[1];
        PositionalSource counting = new PositionalSource(data.length) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) throws IOException {
                reads[0]++;
                return source.readAt(position, buf, off, len);
            }

            @Override
            protected void closeInternal() {
                source.close();
            }
        };
        ChunkSink failing = (b, off, len) -> {
            writes[0]++;
            if (writes[0] == 2) {
                throw new IOException("client went away");
            }
        };
        try {
            RangeStreamer.serve(counting, resolve(null, data.length), failing, CHUNK);
            fail("expected the sink's IOException");
        } catch (IOException expected) {
            assertEquals("client went away", expected.getMessage());
        }
        assertEquals(2, writes[0]);
        assertEquals("no read after the failed write", 2, reads[0]);
    }

    @Test
    public void aSourceThatIsClosingStopsTheStream() {
        byte[] data = sequence(CHUNK * 10);
        ArraySource source = new ArraySource(data);
        Collector sink = new Collector() {
            @Override
            public void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                source.close();
            }
        };
        try {
            RangeStreamer.serve(source, resolve(null, data.length), sink, CHUNK);
            fail("expected IOException");
        } catch (IOException expected) {
            // the source refused the next read
        }
        assertEquals("only the chunk before the close was written", CHUNK, sink.bytes.size());
    }

    @Test
    public void aSourceClosedBeforehandWritesNothing() {
        ArraySource source = new ArraySource(sequence(100));
        source.close();
        Collector sink = new Collector();
        try {
            RangeStreamer.serve(source, resolve(null, 100), sink, CHUNK);
            fail("expected IOException");
        } catch (IOException expected) {
            // fine
        }
        assertEquals(0, sink.bytes.size());
    }

    @Test
    public void aSourceThatEndsEarlyIsAnErrorNotAShortBody() {
        byte[] data = sequence(100);
        PositionalSource truncating = new PositionalSource(100L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                if (position >= 40) {
                    return -1;
                }
                int n = (int) Math.min(len, 40 - position);
                System.arraycopy(data, (int) position, buf, off, n);
                return n;
            }

            @Override
            protected void closeInternal() {
            }
        };
        Collector sink = new Collector();
        try {
            RangeStreamer.serve(truncating, resolve(null, 100), sink, CHUNK);
            fail("expected IOException");
        } catch (IOException expected) {
            // Content-Length promised 100 bytes: the response must be aborted
        }
        assertEquals(40, sink.bytes.size());
    }

    @Test
    public void aBackendThatReturnsFewerBytesThanAskedKeepsGoing() throws IOException {
        byte[] data = sequence(50);
        PositionalSource trickle = new PositionalSource(50L) {
            @Override
            protected int readAtInternal(long position, byte[] buf, int off, int len) {
                int n = Math.min(len, 3);
                System.arraycopy(data, (int) position, buf, off, n);
                return n;
            }

            @Override
            protected void closeInternal() {
            }
        };
        Collector sink = new Collector();
        RangeStreamer.serve(trickle, resolve(null, 50), sink, CHUNK);
        assertArrayEquals(data, sink.bytes.toByteArray());
    }

    // ---- contract

    @Test
    public void a416ResultHasNoBodyToServe() {
        ByteRangeResolver.Result result = resolve("bytes=500-", 100);
        assertEquals(416, result.status());
        try {
            RangeStreamer.serve(new ArraySource(sequence(100)), result, new Collector(), CHUNK);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException | IOException expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }

    @Test
    public void aResultThatDoesNotFitTheSourceIsRejected() {
        ByteRangeResolver.Result result = resolve(null, 1000);
        try {
            new RangeStreamer(new ArraySource(sequence(100)), result, CHUNK);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // the resolver was run against another length than the source's
        }
    }

    @Test
    public void anUnknownLengthResultIsRejected() {
        ByteRangeResolver.Result result = resolve(null, -1);
        try {
            new RangeStreamer(new ArraySource(sequence(100)), result, CHUNK);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void nullArgumentsAndANonPositiveChunkSizeAreRejected() {
        ByteRangeResolver.Result result = resolve(null, 10);
        ArraySource source = new ArraySource(sequence(10));
        assertIllegal(() -> new RangeStreamer(null, result, CHUNK));
        assertIllegal(() -> new RangeStreamer(source, null, CHUNK));
        assertIllegal(() -> new RangeStreamer(source, result, 0));
        assertIllegal(() -> new RangeStreamer(source, result, -5));
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertIllegal(Action action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fine
        } catch (Exception other) {
            fail("unexpected " + other);
        }
    }
}
