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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Reading frames from a stream: caps before any allocation, deadlines, timeouts and unknown types. */
public class FrameReaderTest {

    /** An input stream that plays a script: chunks of bytes, timeouts and a counter of the bytes handed out. */
    private static final class ScriptedStream extends InputStream {
        private final Deque<Object> script = new ArrayDeque<>();
        private final ManualClock clock;
        private final long advanceMicrosPerRead;
        long consumed;

        ScriptedStream(ManualClock clock, long advanceMicrosPerRead) {
            this.clock = clock;
            this.advanceMicrosPerRead = advanceMicrosPerRead;
        }

        ScriptedStream bytes(byte[] data) {
            script.add(data);
            return this;
        }

        ScriptedStream timeout() {
            script.add(new SocketTimeoutException("scripted"));
            return this;
        }

        /** The bytes, one per read call. */
        ScriptedStream drip(byte[] data) {
            for (byte b : data) {
                script.add(new byte[]{b});
            }
            return this;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (clock != null) {
                clock.advanceMicros(advanceMicrosPerRead);
            }
            Object next = script.peek();
            if (next == null) {
                return -1;
            }
            if (next instanceof IOException) {
                script.pollFirst();
                throw (IOException) next;
            }
            byte[] data = (byte[]) next;
            int count = Math.min(length, data.length);
            System.arraycopy(data, 0, buffer, offset, count);
            consumed += count;
            script.pollFirst();
            if (count < data.length) {
                script.addFirst(Arrays.copyOfRange(data, count, data.length));
            }
            return count;
        }
    }

    private static FrameReader readerOf(InputStream in) {
        return new FrameReader(in, new ManualClock(0), 10_000);
    }

    @Test
    public void readsFramesBackToBackAndSkipsUnknownTypes() throws Exception {
        byte[] time = SnapcastMessageCodec.encode(new TimeMessage(1, 2), 1, 0, 0, 0, 0, 0);
        byte[] unknown = Bytes.frame(6, Bytes.of(9, 9, 9, 9, 9, 9, 9));
        byte[] settings = SnapcastMessageCodec.encode(new ServerSettingsMessage(500, 0, false, 80), 2, 0, 0, 0, 0, 0);
        FrameReader reader = readerOf(new ByteArrayInputStream(Bytes.concat(time, unknown, settings)));

        Frame first = reader.read();
        Frame second = reader.read();
        Frame third = reader.read();

        assertEquals(new TimeMessage(1, 2), first.getMessage());
        assertTrue(second.isSkipped());
        assertEquals(6, second.getHeader().getType());
        assertEquals(new ServerSettingsMessage(500, 0, false, 80), third.getMessage());
        try {
            reader.read();
            fail();
        } catch (EOFException expected) {
            // a clean end between two frames is the server closing
        }
    }

    @Test
    public void aFrameWithTheLargestAllowedPayloadIsReadInPieces() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        byte[] frame = SnapcastMessageCodec.encode(new WireChunkMessage(1, 2, payload), 0, 0, 0, 0, 0, 0);
        ScriptedStream stream = new ScriptedStream(null, 0);
        for (int offset = 0; offset < frame.length; offset += 777) {
            stream.bytes(Arrays.copyOfRange(frame, offset, Math.min(frame.length, offset + 777)));
        }

        Frame read = readerOf(stream).read();

        assertArrayEquals(payload, ((WireChunkMessage) read.getMessage()).getPayload());
    }

    @Test
    public void anOversizedSizeIsRefusedAfterTheHeaderAndBeforeAnyBodyByteIsRead() throws Exception {
        byte[] header = Bytes.header(2, 0, 0, 0, 0, 0, 0, 8L * 1024 * 1024 + 1);
        ScriptedStream stream = new ScriptedStream(null, 0).bytes(Bytes.concat(header, new byte[100]));

        try {
            readerOf(stream).read();
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.TOO_LARGE, e.getKind());
        }
        assertEquals("only the header was consumed", 26, stream.consumed);
    }

    @Test
    public void aNegativeSizeIsRefusedAfterTheHeader() throws Exception {
        ScriptedStream stream = new ScriptedStream(null, 0)
                .bytes(Bytes.concat(Bytes.header(2, 0, 0, 0, 0, 0, 0, 0xfffffff0L), new byte[100]));

        try {
            readerOf(stream).read();
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.NEGATIVE_SIZE, e.getKind());
        }
        assertEquals(26, stream.consumed);
    }

    @Test
    public void aSizeThatPromisesMoreThanArrivesIsTruncatedWithoutReservingTheWholeSize() throws Exception {
        byte[] header = Bytes.header(2, 0, 0, 0, 0, 0, 0, 8L * 1024 * 1024);
        ScriptedStream stream = new ScriptedStream(null, 0).bytes(Bytes.concat(header, new byte[100]));

        try {
            readerOf(stream).read();
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.TRUNCATED, e.getKind());
        }
    }

    @Test
    public void aStreamThatEndsInsideTheHeaderOrTheBodyIsTruncated() throws Exception {
        byte[] frame = SnapcastMessageCodec.encode(new TimeMessage(1, 2), 1, 0, 0, 0, 0, 0);
        for (int cut : new int[]{1, 25, 26, 30, frame.length - 1}) {
            try {
                readerOf(new ByteArrayInputStream(Arrays.copyOf(frame, cut))).read();
                fail("cut at " + cut);
            } catch (SnapcastProtocolException e) {
                assertEquals(SnapcastProtocolException.Kind.TRUNCATED, e.getKind());
            }
        }
    }

    @Test
    public void aTimeoutBetweenFramesIsPassedOnAndTheReaderStillWorksAfterIt() throws Exception {
        byte[] frame = SnapcastMessageCodec.encode(new TimeMessage(3, 4), 1, 0, 0, 0, 0, 0);
        ScriptedStream stream = new ScriptedStream(null, 0).timeout().timeout().bytes(frame);
        FrameReader reader = readerOf(stream);

        for (int i = 0; i < 2; i++) {
            try {
                reader.read();
                fail();
            } catch (SocketTimeoutException expected) {
                // nothing of a frame had arrived: not an error of the protocol
            }
        }
        assertEquals(new TimeMessage(3, 4), reader.read().getMessage());
    }

    @Test
    public void aTimeoutInTheMiddleOfAFrameIsATypedTimeout() throws Exception {
        byte[] frame = SnapcastMessageCodec.encode(new TimeMessage(3, 4), 1, 0, 0, 0, 0, 0);
        for (int cut : new int[]{1, 10, 26, 30}) {
            ScriptedStream stream = new ScriptedStream(null, 0).bytes(Arrays.copyOf(frame, cut)).timeout();

            try {
                readerOf(stream).read();
                fail("cut at " + cut);
            } catch (SnapcastProtocolException e) {
                assertEquals(SnapcastProtocolException.Kind.TIMEOUT, e.getKind());
            }
        }
    }

    @Test
    public void aFrameThatCrawlsInIsCutOffAtTheDeadlineEvenWhenEveryReadSucceeds() throws Exception {
        byte[] frame = SnapcastMessageCodec.encode(new WireChunkMessage(1, 2, new byte[100]), 1, 0, 0, 0, 0, 0);
        ManualClock clock = new ManualClock(0);
        // each byte takes 1 s: the reads never time out, but 10 s after the first byte the frame is declared too slow
        ScriptedStream stream = new ScriptedStream(clock, 1_000_000).drip(frame);
        FrameReader reader = new FrameReader(stream, clock, 10_000);

        try {
            reader.read();
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.DEADLINE, e.getKind());
        }
        assertTrue("stopped after about 11 bytes, not the whole frame: " + stream.consumed, stream.consumed <= 12);
    }

    @Test
    public void theDeadlineStartsWithTheFirstByteOfAFrameNotWithTheWait() throws Exception {
        byte[] frame = SnapcastMessageCodec.encode(new TimeMessage(3, 4), 1, 0, 0, 0, 0, 0);
        ManualClock clock = new ManualClock(0);
        ScriptedStream stream = new ScriptedStream(null, 0).timeout().bytes(frame);
        FrameReader reader = new FrameReader(stream, clock, 10_000);

        try {
            reader.read();
            fail();
        } catch (SocketTimeoutException expected) {
            // idle
        }
        clock.advanceMillis(3_600_000); // an hour of silence is the connection's idle limit to judge, not the frame's
        assertEquals(new TimeMessage(3, 4), reader.read().getMessage());
    }

    @Test
    public void anUnknownFrameIsConsumedExactlyByItsSize() throws Exception {
        byte[] body = new byte[64 * 1024];
        byte[] unknown = Bytes.frame(42, body);
        byte[] time = SnapcastMessageCodec.encode(new TimeMessage(3, 4), 1, 0, 0, 0, 0, 0);
        FrameReader reader = readerOf(new ByteArrayInputStream(Bytes.concat(unknown, time)));

        Frame skipped = reader.read();
        Frame next = reader.read();

        assertTrue(skipped.isSkipped());
        assertEquals(body.length, skipped.getHeader().getSize());
        assertEquals(new TimeMessage(3, 4), next.getMessage());
    }

    @Test
    public void anUnknownFrameLargerThanTheSmallCapIsRefused() throws Exception {
        ScriptedStream stream = new ScriptedStream(null, 0)
                .bytes(Bytes.concat(Bytes.header(42, 0, 0, 0, 0, 0, 0, 64 * 1024 + 1), new byte[100]));

        try {
            readerOf(stream).read();
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.TOO_LARGE, e.getKind());
        }
        assertEquals(26, stream.consumed);
    }

    @Test
    public void aMessageWithAnInnerLengthBeyondTheMessageIsATypedErrorAndTheReaderStaysInStep() throws Exception {
        byte[] bad = Bytes.frame(2, Bytes.concat(Bytes.u32(1), Bytes.u32(2), Bytes.u32(99), Bytes.of(1)));
        byte[] time = SnapcastMessageCodec.encode(new TimeMessage(3, 4), 1, 0, 0, 0, 0, 0);
        FrameReader reader = readerOf(new ByteArrayInputStream(Bytes.concat(bad, time)));

        try {
            reader.read();
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.BAD_LENGTH, e.getKind());
        }
        // the whole bad frame was consumed, so a caller that wants to go on is at the next header
        assertEquals(new TimeMessage(3, 4), reader.read().getMessage());
    }

    @Test
    public void aStreamThatReturnsNothingForAPositiveLengthIsAnError() {
        InputStream silent = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return 0;
            }
        };
        try {
            readerOf(silent).read();
            fail();
        } catch (IOException | SnapcastProtocolException expected) {
            // no endless loop on a stream that makes no progress
        }
    }
}
