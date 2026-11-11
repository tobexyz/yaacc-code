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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * The binary messages as bytes. The fixtures are written out by hand (little endian, see {@link Bytes}); the encoder
 * is never used to make the bytes it is checked against.
 */
public class SnapcastMessageCodecTest {

    private static void assertRejected(SnapcastProtocolException.Kind kind, byte[] frame) {
        try {
            SnapcastMessageCodec.decode(frame);
            fail("expected " + kind);
        } catch (SnapcastProtocolException e) {
            assertEquals(kind, e.getKind());
        }
    }

    private static SnapcastBinaryMessage decodeMessage(byte[] frame) throws Exception {
        return SnapcastMessageCodec.decode(frame).getMessage();
    }

    // ---- hand computed bytes, both directions

    private static final byte[] TIME_FRAME = Bytes.of(
            0x04, 0x00,             // type 4
            0x01, 0x02,             // id 0x0201
            0x03, 0x04,             // refersTo 0x0403
            0x05, 0x00, 0x00, 0x00, // sent sec 5
            0x06, 0x00, 0x00, 0x00, // sent usec 6
            0x07, 0x00, 0x00, 0x00, // received sec 7
            0x08, 0x00, 0x00, 0x00, // received usec 8
            0x08, 0x00, 0x00, 0x00, // size 8
            0x01, 0x00, 0x00, 0x00, // latency sec 1
            0xff, 0xff, 0xff, 0xff); // latency usec -1

    @Test
    public void timeMessageEncodesToTheHandWrittenBytes() {
        byte[] bytes = SnapcastMessageCodec.encode(new TimeMessage(1, -1), 0x0201, 0x0403, 5, 6, 7, 8);

        assertArrayEquals(TIME_FRAME, bytes);
    }

    @Test
    public void timeMessageDecodesFromTheHandWrittenBytes() throws Exception {
        Frame frame = SnapcastMessageCodec.decode(TIME_FRAME);

        MessageHeader header = frame.getHeader();
        assertEquals(4, header.getType());
        assertEquals(0x0201, header.getId());
        assertEquals(0x0403, header.getRefersTo());
        assertEquals(5, header.getSentSec());
        assertEquals(6, header.getSentUsec());
        assertEquals(7, header.getReceivedSec());
        assertEquals(8, header.getReceivedUsec());
        assertEquals(8, header.getSize());
        TimeMessage time = (TimeMessage) frame.getMessage();
        assertEquals(1, time.getLatencySec());
        assertEquals(-1, time.getLatencyUsec());
        assertEquals(999_999L, time.latencyMicros());
        assertEquals(5_000_006L, header.sentMicros());
    }

    private static final byte[] WIRE_CHUNK_FRAME = Bytes.of(
            0x02, 0x00,             // type 2
            0x00, 0x00, 0x00, 0x00, // id, refersTo
            0x78, 0x56, 0x34, 0x12, // sent sec 0x12345678
            0x3f, 0x42, 0x0f, 0x00, // sent usec 999999
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // received
            0x0f, 0x00, 0x00, 0x00, // size 15
            0x64, 0x00, 0x00, 0x00, // timestamp sec 100
            0x90, 0xd0, 0x03, 0x00, // timestamp usec 250000
            0x03, 0x00, 0x00, 0x00, // payload size 3
            0xaa, 0xbb, 0xcc);

    @Test
    public void wireChunkEncodesToTheHandWrittenBytes() {
        byte[] bytes = SnapcastMessageCodec.encode(new WireChunkMessage(100, 250_000, Bytes.of(0xaa, 0xbb, 0xcc)), 0, 0,
                0x12345678, 999_999, 0, 0);

        assertArrayEquals(WIRE_CHUNK_FRAME, bytes);
    }

    @Test
    public void wireChunkDecodesFromTheHandWrittenBytes() throws Exception {
        WireChunkMessage chunk = (WireChunkMessage) decodeMessage(WIRE_CHUNK_FRAME);

        assertEquals(100, chunk.getTimestampSec());
        assertEquals(250_000, chunk.getTimestampUsec());
        assertEquals(100_250_000L, chunk.timestampMicros());
        assertArrayEquals(Bytes.of(0xaa, 0xbb, 0xcc), chunk.getPayload());
        assertEquals(3, chunk.getPayloadLength());
    }

    @Test
    public void aWireChunkKeepsItsOwnCopyOfThePayload() throws Exception {
        byte[] source = Bytes.of(1, 2, 3);
        WireChunkMessage chunk = new WireChunkMessage(1, 2, source);
        source[0] = 99;
        chunk.getPayload()[1] = 98;

        assertArrayEquals(Bytes.of(1, 2, 3), chunk.getPayload());
    }

    @Test
    public void codecHeaderWithAPcmHeaderDecodesFromHandWrittenBytes() throws Exception {
        // type 1, size = 4 + 3 + 4 + 44
        byte[] frame = Bytes.concat(Bytes.header(1, 0, 0, 0, 0, 0, 0, 4 + 3 + 4 + 44),
                Bytes.u32(3), Bytes.ascii("pcm"), Bytes.u32(44), Bytes.WAVE_48000_STEREO_16);

        CodecHeaderMessage header = (CodecHeaderMessage) decodeMessage(frame);

        assertEquals(Codec.PCM, header.getCodec());
        assertEquals(48000, header.getSampleRate());
        assertEquals(2, header.getChannels());
        assertEquals(16, header.getBitsPerSample());
        assertNotNull(header.getPcmFormat());
        assertNull(header.getFlacStreamInfo());
        assertArrayEquals(Bytes.WAVE_48000_STEREO_16, header.getPayload());
    }

    @Test
    public void codecHeaderWithAFlacHeaderRoundTripsAndEncodesTheNameWithoutTerminator() throws Exception {
        CodecHeaderMessage original = CodecHeaderMessage.of(Codec.FLAC, Bytes.FLAC_48000_STEREO_16);

        byte[] bytes = SnapcastMessageCodec.encode(original, 7, 0, 1, 2, 0, 0);

        byte[] expectedBody = Bytes.concat(Bytes.u32(4), Bytes.ascii("flac"), Bytes.u32(42), Bytes.FLAC_48000_STEREO_16);
        assertArrayEquals(Bytes.concat(Bytes.header(1, 7, 0, 1, 2, 0, 0, expectedBody.length), expectedBody), bytes);
        CodecHeaderMessage decoded = (CodecHeaderMessage) decodeMessage(bytes);
        assertEquals(original, decoded);
        assertEquals(48000, decoded.getFlacStreamInfo().getSampleRate());
    }

    @Test
    public void errorMessageDecodesFromHandWrittenBytes() throws Exception {
        byte[] body = Bytes.concat(Bytes.u32(401), Bytes.u32(12), Bytes.ascii("Unauthorized"), Bytes.u32(3),
                Bytes.ascii("bad"));
        byte[] frame = Bytes.concat(Bytes.header(8, 0, 0, 0, 0, 0, 0, body.length), body);

        ErrorMessage error = (ErrorMessage) decodeMessage(frame);

        assertEquals(401L, error.getCode());
        assertEquals("Unauthorized", error.getMessage());
        assertEquals("bad", error.getDetails());
        assertTrue(error.isAuthorizationError());
        assertEquals(27, body.length);
        assertArrayEquals(frame, SnapcastMessageCodec.encode(error, 0, 0, 0, 0, 0, 0));
    }

    @Test
    public void anErrorCodeAbove2To31IsKeptAsAnUnsignedValue() throws Exception {
        byte[] body = Bytes.concat(Bytes.u32(0xfffffffeL), Bytes.u32(0), Bytes.u32(0));

        ErrorMessage error = (ErrorMessage) decodeMessage(Bytes.frame(8, body));

        assertEquals(0xfffffffeL, error.getCode());
        assertFalse(error.isAuthorizationError());
    }

    @Test
    public void serverSettingsDecodeFromAJsonBodyWithItsLengthPrefix() throws Exception {
        byte[] frame = Bytes.frame(3,
                Bytes.jsonBody("{\"bufferMs\":1000,\"latency\":-5,\"muted\":true,\"volume\":42}"));

        ServerSettingsMessage settings = (ServerSettingsMessage) decodeMessage(frame);

        assertEquals(1000, settings.getBufferMs());
        assertEquals(-5, settings.getLatencyMs());
        assertTrue(settings.isMuted());
        assertEquals(42, settings.getVolume());
    }

    @Test
    public void clientInfoDecodesFromAJsonBody() throws Exception {
        ClientInfoMessage info = (ClientInfoMessage) decodeMessage(
                Bytes.frame(7, Bytes.jsonBody("{\"volume\":55,\"muted\":false}")));

        assertEquals(55, info.getVolume());
        assertFalse(info.isMuted());
    }

    @Test
    public void helloEncodesALengthPrefixedJsonObjectUnderTheCorrectHeader() throws Exception {
        HelloMessage hello = HelloMessage.create("yaacc-1234", "pixel", "YAACC", "Android 14", "arm64", "1.2.3");

        byte[] bytes = SnapcastMessageCodec.encode(hello, 1, 0, 9, 10, 0, 0);

        assertEquals(5, bytes[0]);
        assertEquals(0, bytes[1]);
        assertArrayEquals(Bytes.of(0x01, 0x00, 0x00, 0x00), Arrays.copyOfRange(bytes, 2, 6));
        long size = (bytes[22] & 0xffL) | ((bytes[23] & 0xffL) << 8) | ((bytes[24] & 0xffL) << 16)
                | ((bytes[25] & 0xffL) << 24);
        assertEquals(bytes.length - 26, size);
        long jsonLength = (bytes[26] & 0xffL) | ((bytes[27] & 0xffL) << 8) | ((bytes[28] & 0xffL) << 16)
                | ((bytes[29] & 0xffL) << 24);
        assertEquals(bytes.length - 30, jsonLength);
        JSONObject json = new JSONObject(new String(bytes, 30, bytes.length - 30, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("yaacc-1234", json.getString("ID"));
        assertEquals(2, json.getInt("SnapStreamProtocolVersion"));
    }

    // ---- round trips

    @Test
    public void everyMessageRoundTripsThroughEncodeAndDecode() throws Exception {
        SnapcastBinaryMessage[] messages = {
                HelloMessage.create("id-1", "host", "YAACC", "Android", "arm64-v8a", "1.0"),
                new ServerSettingsMessage(1000, -20, true, 77),
                CodecHeaderMessage.of(Codec.PCM, Bytes.WAVE_48000_STEREO_16),
                CodecHeaderMessage.of(Codec.FLAC, Bytes.FLAC_48000_STEREO_16),
                new WireChunkMessage(-3, 999_999, new byte[]{1, 2, 3, 4, 5}),
                new WireChunkMessage(0, 0, new byte[0]),
                new TimeMessage(Integer.MAX_VALUE, Integer.MIN_VALUE),
                new ClientInfoMessage(100, true),
                new ErrorMessage(403, "Forbidden", "no access to the stream"),
        };
        for (SnapcastBinaryMessage message : messages) {
            byte[] bytes = SnapcastMessageCodec.encode(message, 65535, 12, -1, 5, 6, 7);

            Frame frame = SnapcastMessageCodec.decode(bytes);

            assertEquals(message, frame.getMessage());
            assertEquals(message.hashCode(), frame.getMessage().hashCode());
            assertEquals(message.type().id(), frame.getHeader().getType());
            assertEquals(65535, frame.getHeader().getId());
            assertEquals(12, frame.getHeader().getRefersTo());
            assertEquals(-1, frame.getHeader().getSentSec());
            assertEquals(bytes.length - 26, frame.getHeader().getSize());
        }
    }

    @Test
    public void theMessageTypesCarryTheIdsOfTheProtocol() {
        assertEquals(1, MessageType.CODEC_HEADER.id());
        assertEquals(2, MessageType.WIRE_CHUNK.id());
        assertEquals(3, MessageType.SERVER_SETTINGS.id());
        assertEquals(4, MessageType.TIME.id());
        assertEquals(5, MessageType.HELLO.id());
        assertEquals(7, MessageType.CLIENT_INFO.id());
        assertEquals(8, MessageType.ERROR.id());
        assertNull(MessageType.of(0));
        assertNull(MessageType.of(6));
        assertNull(MessageType.of(9));
        assertNull(MessageType.of(0xffff));
    }

    // ---- framing errors

    @Test
    public void aHeaderThatIsCutShortIsTruncated() {
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Arrays.copyOf(TIME_FRAME, 25));
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, new byte[0]);
    }

    @Test
    public void aBodyThatIsCutShortIsTruncated() {
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Arrays.copyOf(TIME_FRAME, 30));
    }

    @Test
    public void bytesAfterTheFrameAreRejected() {
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.concat(TIME_FRAME, Bytes.of(0)));
    }

    @Test
    public void aSizeAbove2To31MinusOneIsRejectedAsNegative() {
        assertRejected(SnapcastProtocolException.Kind.NEGATIVE_SIZE,
                Bytes.header(2, 0, 0, 0, 0, 0, 0, 0x80000000L));
        assertRejected(SnapcastProtocolException.Kind.NEGATIVE_SIZE,
                Bytes.header(2, 0, 0, 0, 0, 0, 0, 0xffffffffL));
        assertRejected(SnapcastProtocolException.Kind.NEGATIVE_SIZE,
                Bytes.header(99, 0, 0, 0, 0, 0, 0, 0xffffffffL));
    }

    @Test
    public void theSizeCapIs8MibForChunksAboutTheHeaderCapForCodecHeadersAnd64KibForTheRest() throws Exception {
        long bulk = 8L * 1024 * 1024;
        assertEquals(bulk, SnapcastMessageCodec.parseHeader(Bytes.header(2, 0, 0, 0, 0, 0, 0, bulk)).getSize());
        assertRejected(SnapcastProtocolException.Kind.TOO_LARGE, Bytes.header(2, 0, 0, 0, 0, 0, 0, bulk + 1));
        // a codec header holds a name and a header of at most 64 KiB, the rest of its room is for the two lengths
        long header = SnapcastProtocolLimits.MAX_CODEC_HEADER_PAYLOAD + 64;
        assertEquals(header, SnapcastMessageCodec.parseHeader(Bytes.header(1, 0, 0, 0, 0, 0, 0, header)).getSize());
        assertRejected(SnapcastProtocolException.Kind.TOO_LARGE, Bytes.header(1, 0, 0, 0, 0, 0, 0, header + 1));
        assertRejected(SnapcastProtocolException.Kind.TOO_LARGE, Bytes.header(1, 0, 0, 0, 0, 0, 0, bulk));
        long small = 64L * 1024;
        for (int type : new int[]{3, 4, 5, 7, 8, 6, 0, 9, 1000}) {
            assertEquals(small, SnapcastMessageCodec.parseHeader(Bytes.header(type, 0, 0, 0, 0, 0, 0, small)).getSize());
            assertRejected(SnapcastProtocolException.Kind.TOO_LARGE,
                    Bytes.header(type, 0, 0, 0, 0, 0, 0, small + 1));
        }
    }

    @Test
    public void anUnknownTypeIsSkippedBySizeAndHasNoMessage() throws Exception {
        for (int type : new int[]{0, 6, 9, 0xffff}) {
            Frame frame = SnapcastMessageCodec.decode(Bytes.frame(type, Bytes.of(1, 2, 3, 4, 5)));

            assertTrue(frame.isSkipped());
            assertNull(frame.getMessage());
            assertEquals(5, frame.getHeader().getSize());
            assertEquals(type, frame.getHeader().getType());
        }
    }

    // ---- a length inside a message that does not fit

    @Test
    public void aWireChunkPayloadLongerThanTheMessageIsRejected() {
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(2, Bytes.concat(Bytes.u32(1), Bytes.u32(2), Bytes.u32(10), Bytes.of(1, 2, 3))));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(2, Bytes.concat(Bytes.u32(1), Bytes.u32(2), Bytes.u32(0xffffffffL), Bytes.of(1, 2, 3))));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(2, Bytes.concat(Bytes.u32(1), Bytes.u32(2), Bytes.u32(0x80000000L))));
    }

    @Test
    public void aWireChunkWithoutRoomForItsTimestampIsTruncated() {
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Bytes.frame(2, Bytes.of(1, 2, 3, 4, 5, 6)));
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Bytes.frame(2, new byte[0]));
    }

    @Test
    public void aCodecHeaderWithLengthsThatDoNotFitIsRejected() {
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(1, Bytes.concat(Bytes.u32(50), Bytes.ascii("flac"))));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(1, Bytes.concat(Bytes.u32(0xffffffffL), Bytes.ascii("flac"))));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(1, Bytes.concat(Bytes.u32(4), Bytes.ascii("flac"), Bytes.u32(43), Bytes.FLAC_48000_STEREO_16)));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(1, Bytes.concat(Bytes.u32(4), Bytes.ascii("flac"), Bytes.u32(0x80000000L))));
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Bytes.frame(1, Bytes.of(1, 0)));
    }

    @Test
    public void anErrorWithLengthsThatDoNotFitIsRejected() {
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(8, Bytes.concat(Bytes.u32(1), Bytes.u32(100), Bytes.ascii("short"))));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(8, Bytes.concat(Bytes.u32(1), Bytes.u32(2), Bytes.ascii("ab"), Bytes.u32(0xffffffffL))));
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Bytes.frame(8, Bytes.of(1, 0, 0)));
    }

    @Test
    public void aJsonLengthLargerThanTheMessageIsRejected() {
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(3, Bytes.concat(Bytes.u32(500), Bytes.ascii("{}"))));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH,
                Bytes.frame(5, Bytes.concat(Bytes.u32(0xffffffffL), Bytes.ascii("{}"))));
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Bytes.frame(7, Bytes.of(1)));
    }

    @Test
    public void aTimeMessageThatIsTooShortIsTruncated() {
        assertRejected(SnapcastProtocolException.Kind.TRUNCATED, Bytes.frame(4, Bytes.of(1, 0, 0, 0)));
    }

    // ---- content

    @Test
    public void onlyPcmAndFlacAreAcceptedAsCodecs() {
        for (String name : new String[]{"opus", "ogg", "", "FLAC", "pcm ", "flac\0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}) {
            byte[] frame = Bytes.frame(1, Bytes.concat(Bytes.lengthPrefixed(Bytes.utf8(name)),
                    Bytes.lengthPrefixed(Bytes.WAVE_48000_STEREO_16)));
            assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_CODEC, frame);
        }
    }

    @Test
    public void aCodecNameThatIsNotAllowedIsNotEchoedWithControlCharacters() {
        byte[] frame = Bytes.frame(1, Bytes.concat(Bytes.lengthPrefixed(Bytes.utf8("evil\n‮codec")),
                Bytes.lengthPrefixed(Bytes.of(1))));
        try {
            SnapcastMessageCodec.decode(frame);
            fail();
        } catch (SnapcastProtocolException e) {
            assertEquals(SnapcastProtocolException.Kind.UNSUPPORTED_CODEC, e.getKind());
            assertFalse(e.getMessage().contains("\n"));
            assertFalse(e.getMessage().contains("‮"));
        }
    }

    @Test
    public void aCodecHeaderWhoseHeaderDoesNotMatchTheCodecIsRejected() {
        byte[] wavAsFlac = Bytes.frame(1, Bytes.concat(Bytes.lengthPrefixed(Bytes.ascii("flac")),
                Bytes.lengthPrefixed(Bytes.WAVE_48000_STEREO_16)));
        byte[] flacAsPcm = Bytes.frame(1, Bytes.concat(Bytes.lengthPrefixed(Bytes.ascii("pcm")),
                Bytes.lengthPrefixed(Bytes.FLAC_48000_STEREO_16)));

        assertRejected(SnapcastProtocolException.Kind.MALFORMED, wavAsFlac);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flacAsPcm);
    }

    @Test
    public void malformedJsonIsRejectedWithATypedError() {
        for (String json : new String[]{"not json", "[1,2]", "{\"a\":", "{'a':1}", "{\"a\":1} trailing", "", "null"}) {
            assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.frame(3, Bytes.jsonBody(json)));
            assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.frame(7, Bytes.jsonBody(json)));
            assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.frame(5, Bytes.jsonBody(json)));
        }
    }

    @Test
    public void jsonNestedDeeperThanTheControlClientAllowsIsRejectedBeforeItIsParsed() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("{\"a\":");
        }
        deep.append('1');
        for (int i = 0; i < 40; i++) {
            deep.append('}');
        }
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.frame(3, Bytes.jsonBody(deep.toString())));

        // the largest body that passes the size cap: 60,000 opening brackets, which would overflow the stack of a parser
        StringBuilder bomb = new StringBuilder("{\"a\":");
        for (int i = 0; i < 60_000; i++) {
            bomb.append('[');
        }
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.frame(3, Bytes.jsonBody(bomb.toString())));
    }

    @Test
    public void textFromTheServerIsCleanedAndCut() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            longText.append('x');
        }
        byte[] body = Bytes.concat(Bytes.u32(1), Bytes.lengthPrefixed(Bytes.utf8("a\nb‮c​d")),
                Bytes.lengthPrefixed(Bytes.utf8(longText.toString())));

        ErrorMessage error = (ErrorMessage) decodeMessage(Bytes.frame(8, body));

        assertEquals("a?b?c?d", error.getMessage());
        assertEquals(1024, error.getDetails().length());
    }

    @Test
    public void extraBytesAfterTheFieldsOfAMessageAreIgnoredForForwardCompatibility() throws Exception {
        byte[] body = Bytes.concat(Bytes.u32(7), Bytes.u32(8), Bytes.u32(2), Bytes.of(9, 9), Bytes.of(1, 2, 3));

        WireChunkMessage chunk = (WireChunkMessage) decodeMessage(Bytes.frame(2, body));

        assertArrayEquals(Bytes.of(9, 9), chunk.getPayload());
    }

    @Test
    public void constructorsRefuseValuesThatCannotBeEncoded() {
        try {
            new WireChunkMessage(0, 0, new byte[8 * 1024 * 1024 + 1]);
            fail();
        } catch (IllegalArgumentException expected) {
            // the cap of the framing also holds for what we build
        }
        try {
            new WireChunkMessage(0, 0, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // no payload
        }
    }

    // ---- nothing but a typed error comes out of hostile bytes

    @Test
    public void randomBytesNeverEndInAnythingButAFrameOrATypedError() {
        Random random = new Random(0x5eed);
        for (int i = 0; i < 5000; i++) {
            byte[] bytes = new byte[random.nextInt(120)];
            random.nextBytes(bytes);
            if (bytes.length >= 2 && random.nextBoolean()) {
                bytes[0] = (byte) (1 + random.nextInt(8)); // a known type more often
                bytes[1] = 0;
            }
            assertOnlyTypedFailure(bytes);
        }
    }

    @Test
    public void everyByteOfValidFramesMutatedNeverEndInAnythingButAFrameOrATypedError() {
        byte[][] frames = {
                TIME_FRAME,
                WIRE_CHUNK_FRAME,
                SnapcastMessageCodec.encode(CodecHeaderMessage.of(Codec.FLAC, Bytes.FLAC_48000_STEREO_16), 1, 0, 0, 0, 0, 0),
                SnapcastMessageCodec.encode(CodecHeaderMessage.of(Codec.PCM, Bytes.WAVE_48000_STEREO_16), 1, 0, 0, 0, 0, 0),
                SnapcastMessageCodec.encode(new ServerSettingsMessage(1000, 0, false, 100), 1, 0, 0, 0, 0, 0),
                SnapcastMessageCodec.encode(new ErrorMessage(401, "Unauthorized", "x"), 1, 0, 0, 0, 0, 0),
        };
        int[] replacements = {0x00, 0x01, 0x7f, 0x80, 0xff};
        for (byte[] frame : frames) {
            for (int position = 0; position < frame.length; position++) {
                for (int replacement : replacements) {
                    byte[] mutated = frame.clone();
                    mutated[position] = (byte) replacement;
                    assertOnlyTypedFailure(mutated);
                }
            }
        }
    }

    private static void assertOnlyTypedFailure(byte[] bytes) {
        try {
            SnapcastMessageCodec.decode(bytes);
        } catch (SnapcastProtocolException expected) {
            assertNotNull(expected.getKind());
        } catch (Throwable other) {
            throw new AssertionError("untyped failure " + other + " for " + Arrays.toString(bytes), other);
        }
    }
}
