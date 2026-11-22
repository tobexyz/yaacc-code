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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import de.yaacc.fcast.FakeFCastReceiver;

/** Task 2.1: the framing, checked against bytes written by hand and against the fixture's independent wire code. */
public class FCastFramingTest {

    // ---- encode

    @Test
    public void aPacketWithoutBodyIsFiveBytes() throws Exception {
        assertArrayEquals(new byte[]{1, 0, 0, 0, 2}, FCastPacketCodec.encode(FCastOpcode.PAUSE.code(), null));
    }

    @Test
    public void theSizeCountsTheOpcodeAndIsLittleEndian() throws Exception {
        byte[] bytes = FCastPacketCodec.encode(FCastOpcode.SEEK.code(), "{\"time\":5.5}");
        assertEquals(13, bytes[0]);          // 1 + 12 body bytes
        assertEquals(0, bytes[1]);
        assertEquals(5, bytes[4]);
        assertEquals("{\"time\":5.5}", new String(bytes, 5, 12, StandardCharsets.UTF_8));
    }

    @Test
    public void aSizeAbove255UsesTheSecondByte() throws Exception {
        char[] chars = new char[300];
        Arrays.fill(chars, 'a');
        byte[] bytes = FCastPacketCodec.encode(1, new String(chars));
        assertEquals(301 & 0xff, bytes[0] & 0xff);
        assertEquals(1, bytes[1]);
    }

    @Test
    public void theSizeCountsBytesNotCharacters() throws Exception {
        byte[] bytes = FCastPacketCodec.encode(1, "é€😀"); // 2 + 3 + 4 bytes
        assertEquals(1 + 9, bytes[0]);
        assertEquals(4 + 1 + 9, bytes.length);
    }

    @Test
    public void theLargestBodyIsAcceptedAndOneByteMoreIsRefused() throws Exception {
        char[] chars = new char[FCastPacketCodec.MAX_BODY];
        Arrays.fill(chars, 'x');
        byte[] bytes = FCastPacketCodec.encode(1, new String(chars));
        assertEquals(4 + FCastPacketCodec.MAX_PACKET, bytes.length);
        try {
            FCastPacketCodec.encode(1, new String(chars) + "x");
            fail("a packet over the limit was encoded");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.TOO_LARGE, e.getKind());
        }
    }

    @Test
    public void aBodyOfMultiByteCharactersIsMeasuredInBytes() {
        char[] chars = new char[FCastPacketCodec.MAX_BODY / 2 + 1];
        Arrays.fill(chars, 'é'); // two bytes each: just over the limit
        try {
            FCastPacketCodec.encode(1, new String(chars));
            fail("the byte length was not checked");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.TOO_LARGE, e.getKind());
        }
    }

    @Test
    public void anOpcodeThatIsNotAByteIsRefused() {
        for (int opcode : new int[]{-1, 256}) {
            try {
                FCastPacketCodec.encode(opcode, null);
                fail("opcode " + opcode);
            } catch (FCastProtocolException e) {
                assertEquals(FCastProtocolException.Kind.UNSUPPORTED, e.getKind());
            }
        }
    }

    @Test
    public void whatTheCodecWritesTheIndependentWireCodeReads() throws Exception {
        byte[] bytes = FCastPacketCodec.encode(FCastOpcode.PLAY.code(), "{\"container\":\"video/mp4\",\"url\":\"http://h/ü.mp4\"}");
        FakeFCastReceiver.Packet packet = FakeFCastReceiver.Wire.read(new ByteArrayInputStream(bytes));
        assertEquals(1, packet.opcode);
        assertEquals("{\"container\":\"video/mp4\",\"url\":\"http://h/ü.mp4\"}", packet.body);
    }

    // ---- read

    @Test
    public void whatTheIndependentWireCodeWritesTheReaderReads() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FakeFCastReceiver.Wire.write(out, FakeFCastReceiver.Op.PLAYBACK_UPDATE, "{\"state\":1}".getBytes(StandardCharsets.UTF_8));
        FakeFCastReceiver.Wire.write(out, FakeFCastReceiver.Op.PING, null);
        FCastFrameReader reader = new FCastFrameReader(new ByteArrayInputStream(out.toByteArray()));
        FCastPacket first = reader.read();
        assertEquals(FCastOpcode.PLAYBACK_UPDATE, first.getOpcode());
        assertEquals("{\"state\":1}", first.getBody());
        FCastPacket second = reader.read();
        assertEquals(FCastOpcode.PING, second.getOpcode());
        assertNull(second.getBody());
        assertNull("the clean end of the stream", reader.read());
    }

    /** A stream that hands out one byte per read, as a slow network may. */
    private static final class OneByteStream extends InputStream {
        private final byte[] data;
        private int position;

        OneByteStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int b = read();
            if (b < 0) {
                return -1;
            }
            buffer[offset] = (byte) b;
            return 1;
        }
    }

    @Test
    public void aPacketAssembledFromSingleByteReadsIsTheSame() throws Exception {
        byte[] bytes = FCastPacketCodec.encode(FCastOpcode.INITIAL.code(), "{\"displayName\":\"TV ünï\"}");
        FCastPacket packet = new FCastFrameReader(new OneByteStream(bytes)).read();
        assertEquals(FCastOpcode.INITIAL, packet.getOpcode());
        assertEquals("{\"displayName\":\"TV ünï\"}", packet.getBody());
    }

    @Test
    public void theLargestPacketIsReadAndOneByteMoreIsRefusedBeforeItsBody() throws Exception {
        char[] chars = new char[FCastPacketCodec.MAX_BODY];
        Arrays.fill(chars, 'x');
        FCastPacket largest = new FCastFrameReader(new ByteArrayInputStream(FCastPacketCodec.encode(1, new String(chars)))).read();
        assertEquals(FCastPacketCodec.MAX_BODY, largest.getBody().length());

        // the announced size is 32001; the reader must not wait for (or allocate) a body
        byte[] header = {(byte) 0x01, (byte) 0x7d, 0, 0, 1};
        ByteArrayInputStream oversize = new ByteArrayInputStream(header);
        try {
            new FCastFrameReader(oversize).read();
            fail("oversize accepted");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.OVERSIZE, e.getKind());
            assertTrue(e.endsConnection());
        }
        assertEquals("only the size field and nothing of the body was read", 1, oversize.available());
    }

    @Test
    public void aHugeAnnouncedSizeIsRefusedWithoutAllocation() throws Exception {
        byte[] header = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 1};
        try {
            new FCastFrameReader(new ByteArrayInputStream(header)).read();
            fail("a size of 4 GB accepted");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.OVERSIZE, e.getKind());
        }
    }

    @Test
    public void aPacketWithoutOpcodeEndsTheConnection() throws Exception {
        try {
            new FCastFrameReader(new ByteArrayInputStream(new byte[]{0, 0, 0, 0})).read();
            fail("size 0 accepted");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.EMPTY_PACKET, e.getKind());
            assertTrue(e.endsConnection());
        }
    }

    @Test
    public void aStreamThatEndsInsideAPacketIsTruncated() throws Exception {
        byte[][] cases = {
                {5, 0},                          // inside the size
                {5, 0, 0, 0},                    // before the opcode
                {9, 0, 0, 0, 1, '{'},            // inside the body
        };
        for (byte[] data : cases) {
            try {
                new FCastFrameReader(new ByteArrayInputStream(data)).read();
                fail("a truncated packet was accepted: " + Arrays.toString(data));
            } catch (FCastProtocolException e) {
                assertEquals(FCastProtocolException.Kind.TRUNCATED, e.getKind());
                assertTrue(e.endsConnection());
            }
        }
    }

    @Test
    public void anEmptyStreamIsTheNormalEnd() throws Exception {
        assertNull(new FCastFrameReader(new ByteArrayInputStream(new byte[0])).read());
    }

    @Test
    public void aBodyThatIsNotUtf8DoesNotEndTheConnection() throws Exception {
        byte[] bad = {3, 0, 0, 0, 6, (byte) 0xc3, (byte) 0x28};
        byte[] good = FCastPacketCodec.encode(FCastOpcode.PONG.code(), null);
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.write(bad);
        both.write(good);
        FCastFrameReader reader = new FCastFrameReader(new ByteArrayInputStream(both.toByteArray()));
        try {
            reader.read();
            fail("invalid UTF-8 accepted");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.BAD_BODY, e.getKind());
            assertTrue("the packet was read completely", !e.endsConnection());
        }
        assertEquals("the next packet is read normally", FCastOpcode.PONG, reader.read().getOpcode());
    }

    @Test
    public void anUnknownOpcodeIsReturnedNotRefused() throws Exception {
        FCastPacket packet = new FCastFrameReader(new ByteArrayInputStream(new byte[]{1, 0, 0, 0, (byte) 200})).read();
        assertNotNull(packet);
        assertEquals(200, packet.getOpcodeCode());
        assertNull(packet.getOpcode());
    }

    @Test
    public void aStreamFailureIsPassedOn() throws Exception {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("reset");
            }
        };
        try {
            new FCastFrameReader(failing).read();
            fail();
        } catch (FCastProtocolException e) {
            fail("a stream failure is no protocol error");
        } catch (IOException e) {
            assertEquals("reset", e.getMessage());
        }
    }

    // ---- opcodes

    @Test
    public void theOpcodeNumbersAreTheWireValues() {
        int[] expected = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21};
        FCastOpcode[] all = FCastOpcode.values();
        assertEquals(expected.length, all.length);
        for (int i = 0; i < all.length; i++) {
            assertEquals(expected[i], all[i].code());
            assertEquals(all[i], FCastOpcode.fromCode(expected[i]));
        }
        assertNull(FCastOpcode.fromCode(22));
        assertEquals(4, FCastOpcode.FLATBUF.sinceVersion()); // Flatbuf exists only from v4
        assertNull(FCastOpcode.fromCode(-1));
    }

    @Test
    public void theFirstVersionOfAnOpcode() {
        assertEquals(1, FCastOpcode.SET_VOLUME.sinceVersion());
        assertEquals(2, FCastOpcode.SET_SPEED.sinceVersion());
        assertEquals(2, FCastOpcode.PONG.sinceVersion());
        assertEquals(3, FCastOpcode.INITIAL.sinceVersion());
        assertEquals(3, FCastOpcode.EVENT.sinceVersion());
    }
}
