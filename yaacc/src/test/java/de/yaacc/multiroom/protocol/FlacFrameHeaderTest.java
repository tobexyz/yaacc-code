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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

/**
 * The parser of the frame header of a flac chunk: hand-built headers by the specification (FlacFrameBuilder), frames of a
 * real encoder, and everything that can be wrong with the bytes (a bad CRC, truncation, junk).
 *
 * <p>The real frames in {@code snapcast/flac/*.bin} were made by FFmpeg's flac encoder (a conforming FLAC encoder, not
 * libFLAC): {@code ffmpeg -f lavfi -i sine=frequency=440:sample_rate=R:duration=S -ac C -sample_fmt s16 -c:a flac
 * -frame_size B -compression_level 5 x.flac}, the byte ranges of some frames taken from {@code ffprobe -show_packets}
 * (listed in the {@code .txt} files: offset in the .bin, size, first sample, block size, rate, channels). The numbers
 * expected here come from the probe, not from the parser.
 */
public class FlacFrameHeaderTest {

    private static FlacFrameHeader parse(byte[] header) {
        return FlacFrameHeader.parse(header, 0, header.length);
    }

    // ---- frames of a real encoder

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = FlacFrameHeaderTest.class.getResourceAsStream("/snapcast/flac/" + name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void checkRealFile(String name) throws IOException {
        byte[] data = resource(name + ".bin");
        String[] lines = new String(resource(name + ".txt"), StandardCharsets.US_ASCII).trim().split("\n");
        for (String line : lines) {
            String[] f = line.trim().split(" ");
            int offset = Integer.parseInt(f[0]);
            long firstSample = Long.parseLong(f[2]);
            int blockSize = Integer.parseInt(f[3]);
            int rate = Integer.parseInt(f[4]);
            int channels = Integer.parseInt(f[5]);
            FlacFrameHeader h = FlacFrameHeader.parse(data, offset, data.length - offset);
            assertTrue(name + " frame at " + offset + ": " + h.getRefusal(), h.isValid());
            assertEquals(name + " " + line, firstSample, h.getFirstSample());
            assertEquals(name + " " + line, blockSize, h.getBlockSize());
            assertEquals(name + " " + line, rate, h.getSampleRate());
            assertEquals(name + " " + line, channels, h.getChannels());
            assertEquals(16, h.getBitsPerSample());
            assertFalse(h.isVariableBlockSize());
            assertEquals(firstSample / blockSize, h.getCodedNumber());
        }
    }

    @Test
    public void framesOfARealEncoderAt48kHzWithBlocksOf1152() throws IOException {
        checkRealFile("s48_1152"); // frames 0, 1 (one byte numbers) and 126 to 129 (two bytes)
    }

    @Test
    public void framesOfARealEncoderAt44100HzWithBlocksOf4096() throws IOException {
        checkRealFile("s441_4096");
    }

    @Test
    public void framesOfARealMonoStream() throws IOException {
        checkRealFile("m441_1152");
    }

    @Test
    public void framesOfARealEncoderAt96kHzWithBlocksOf4608() throws IOException {
        checkRealFile("s96_4608");
    }

    @Test
    public void framesWithTwoAndThreeByteFrameNumbers() throws IOException {
        checkRealFile("t16"); // blocks of 16 frames: frame numbers up to 9000
    }

    // ---- hand-built headers, the codes of the specification

    @Test
    public void everyBlockSizeCode() {
        int[][] codes = {{1, 192}, {2, 576}, {3, 1152}, {4, 2304}, {5, 4608}, {8, 256}, {9, 512}, {10, 1024}, {11, 2048},
                {12, 4096}, {13, 8192}, {14, 16384}, {15, 32768}};
        for (int[] c : codes) {
            byte[] h = FlacFrameBuilder.rawHeader(false, 5, c[0], 10, 1, 4, new byte[0], new byte[0]);
            FlacFrameHeader parsed = parse(h);
            assertTrue("code " + c[0], parsed.isValid());
            assertEquals(c[1], parsed.getBlockSize());
            assertEquals(5L * c[1], parsed.getFirstSample());
        }
    }

    @Test
    public void theBlockSizeTakenFromTheEndOfTheHeader() {
        for (int size : new int[]{1, 16, 100, 255, 256}) { // code 0110: one byte, value - 1
            byte[] h = FlacFrameBuilder.rawHeader(false, 3, 6, 10, 1, 4, new byte[]{(byte) (size - 1)}, new byte[0]);
            FlacFrameHeader parsed = parse(h);
            assertTrue(size + ": " + parsed.getRefusal(), parsed.isValid());
            assertEquals(size, parsed.getBlockSize());
            assertEquals(3L * size, parsed.getFirstSample());
            assertEquals(h.length, parsed.getLength());
        }
        for (int size : new int[]{257, 1000, 4097, 65535, 65536}) { // code 0111: two bytes, value - 1
            byte[] h = FlacFrameBuilder.rawHeader(false, 3, 7, 10, 1, 4, new byte[]{(byte) ((size - 1) >> 8), (byte) (size - 1)}, new byte[0]);
            FlacFrameHeader parsed = parse(h);
            assertTrue(size + ": " + parsed.getRefusal(), parsed.isValid());
            assertEquals(size, parsed.getBlockSize());
            assertEquals(3L * size, parsed.getFirstSample());
        }
    }

    @Test
    public void everySampleRateCode() {
        int[][] codes = {{0, 0}, {1, 88200}, {2, 176400}, {3, 192000}, {4, 8000}, {5, 16000}, {6, 22050}, {7, 24000},
                {8, 32000}, {9, 44100}, {10, 48000}, {11, 96000}};
        for (int[] c : codes) {
            FlacFrameHeader parsed = parse(FlacFrameBuilder.rawHeader(false, 1, 3, c[0], 1, 4, new byte[0], new byte[0]));
            assertTrue("code " + c[0], parsed.isValid());
            assertEquals(c[1], parsed.getSampleRate()); // 0: the rate of STREAMINFO
        }
        // 1100: kHz in one byte, 1101: Hz in two bytes, 1110: tens of Hz in two bytes
        assertEquals(255000, parse(FlacFrameBuilder.rawHeader(false, 1, 3, 12, 1, 4, new byte[0], new byte[]{(byte) 255})).getSampleRate());
        assertEquals(37800, parse(FlacFrameBuilder.rawHeader(false, 1, 3, 13, 1, 4, new byte[0], new byte[]{(byte) (37800 >> 8), (byte) 37800})).getSampleRate());
        assertEquals(655350, parse(FlacFrameBuilder.rawHeader(false, 1, 3, 14, 1, 4, new byte[0], new byte[]{(byte) 0xff, (byte) 0xff})).getSampleRate());
        assertEquals(4410, parse(FlacFrameBuilder.rawHeader(false, 1, 3, 14, 1, 4, new byte[0], new byte[]{(byte) (441 >> 8), (byte) 441})).getSampleRate());
    }

    @Test
    public void bothBlockSizeAndRateFromTheEndOfTheHeader() {
        byte[] h = FlacFrameBuilder.rawHeader(false, 7, 7, 13, 1, 4, new byte[]{0x03, (byte) 0xe7}, new byte[]{(byte) 0xac, 0x44});
        FlacFrameHeader parsed = parse(h);
        assertTrue(parsed.getRefusal() + "", parsed.isValid());
        assertEquals(1000, parsed.getBlockSize());
        assertEquals(44100, parsed.getSampleRate());
        assertEquals(7000L, parsed.getFirstSample());
        assertEquals(h.length, parsed.getLength());
    }

    @Test
    public void channelAssignmentsAndSampleSizes() {
        for (int channels = 0; channels <= 10; channels++) {
            FlacFrameHeader parsed = parse(FlacFrameBuilder.rawHeader(false, 1, 3, 10, channels, 4, new byte[0], new byte[0]));
            assertTrue("channel code " + channels, parsed.isValid());
            assertEquals(channels <= 7 ? channels + 1 : 2, parsed.getChannels());
        }
        for (int code = 11; code <= 15; code++) {
            assertEquals(FlacFrameHeader.Refusal.BAD_CHANNELS,
                    parse(FlacFrameBuilder.rawHeader(false, 1, 3, 10, code, 4, new byte[0], new byte[0])).getRefusal());
        }
        int[][] sizes = {{0, 0}, {1, 8}, {2, 12}, {4, 16}, {5, 20}, {6, 24}, {7, 32}};
        for (int[] s : sizes) {
            FlacFrameHeader parsed = parse(FlacFrameBuilder.rawHeader(false, 1, 3, 10, 1, s[0], new byte[0], new byte[0]));
            assertTrue("size code " + s[0], parsed.isValid());
            assertEquals(s[1], parsed.getBitsPerSample());
        }
        assertEquals(FlacFrameHeader.Refusal.BAD_SAMPLE_SIZE,
                parse(FlacFrameBuilder.rawHeader(false, 1, 3, 10, 1, 3, new byte[0], new byte[0])).getRefusal());
    }

    @Test
    public void theCodedNumberHasOneToSevenBytes() {
        long[] numbers = {0, 1, 127, 128, 2047, 2048, 65535, 65536, 0x1fffffL, 0x200000L, 0x3ffffffL, 0x4000000L, 0x7fffffffL};
        int[] lengths = {1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6};
        for (int i = 0; i < numbers.length; i++) {
            byte[] h = FlacFrameBuilder.rawHeader(false, numbers[i], 3, 10, 1, 4, new byte[0], new byte[0]);
            FlacFrameHeader parsed = parse(h);
            assertTrue("fixed " + numbers[i] + ": " + parsed.getRefusal(), parsed.isValid());
            assertEquals(numbers[i], parsed.getCodedNumber());
            assertEquals(4 + lengths[i] + 1, parsed.getLength());
            assertEquals(numbers[i] * 1152, parsed.getFirstSample());
        }
        // a variable block size stream numbers samples, with up to 36 bits (seven bytes)
        long[] samples = {0, 5, 1152, 4_000_000_000L, 0x7fffffffL, 0x80000000L, 0xfffffffffL};
        for (long sample : samples) {
            byte[] h = FlacFrameBuilder.rawHeader(true, sample, 3, 10, 1, 4, new byte[0], new byte[0]);
            FlacFrameHeader parsed = parse(h);
            assertTrue("variable " + sample + ": " + parsed.getRefusal(), parsed.isValid());
            assertTrue(parsed.isVariableBlockSize());
            assertEquals(sample, parsed.getCodedNumber());
            assertEquals(sample, parsed.getFirstSample()); // the sample number is the position, whatever the block size
        }
    }

    @Test
    public void aFixedBlockSizeStreamHasNoSevenByteNumber() {
        byte[] h = FlacFrameBuilder.rawHeader(false, 0x80000000L, 3, 10, 1, 4, new byte[0], new byte[0]);
        assertEquals(FlacFrameHeader.Refusal.BAD_NUMBER, parse(h).getRefusal());
    }

    @Test
    public void theNumberOfACoding_thatIsLongerThanNeeded_isRefused() {
        // 5 coded in two bytes (0xc0 0x85): an overlong coding, as the specification forbids
        byte[] body = {(byte) 0xff, (byte) 0xf8, 0x3a, 0x18, (byte) 0xc0, (byte) 0x85};
        byte[] h = Arrays.copyOf(body, body.length + 1);
        h[body.length] = (byte) FlacFrameBuilder.crc8(body, body.length);
        assertEquals(FlacFrameHeader.Refusal.BAD_NUMBER, parse(h).getRefusal());
    }

    @Test
    public void numbersThatStartWithAContinuationByteOrFF() {
        for (int first : new int[]{0x80, 0xbf, 0xff}) {
            byte[] body = {(byte) 0xff, (byte) 0xf8, 0x3a, 0x18, (byte) first, (byte) 0x85, (byte) 0x85, (byte) 0x85, (byte) 0x85, (byte) 0x85, (byte) 0x85};
            byte[] h = Arrays.copyOf(body, body.length + 1);
            h[body.length] = (byte) FlacFrameBuilder.crc8(body, body.length);
            assertEquals("first byte " + first, FlacFrameHeader.Refusal.BAD_NUMBER, parse(h).getRefusal());
        }
        // a continuation byte that is none
        byte[] body = {(byte) 0xff, (byte) 0xf8, 0x3a, 0x18, (byte) 0xc2, 0x05};
        byte[] h = Arrays.copyOf(body, body.length + 1);
        h[body.length] = (byte) FlacFrameBuilder.crc8(body, body.length);
        assertEquals(FlacFrameHeader.Refusal.BAD_NUMBER, parse(h).getRefusal());
    }

    @Test
    public void reservedValuesAreRefused() {
        assertEquals(FlacFrameHeader.Refusal.BAD_BLOCK_SIZE,
                parse(FlacFrameBuilder.rawHeader(false, 1, 0, 10, 1, 4, new byte[0], new byte[0])).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.BAD_SAMPLE_RATE,
                parse(FlacFrameBuilder.rawHeader(false, 1, 3, 15, 1, 4, new byte[0], new byte[0])).getRefusal());
        byte[] h = FlacFrameBuilder.rawHeader(false, 1, 3, 10, 1, 4, new byte[0], new byte[0]);
        h[1] |= 0x02; // reserved bit after the sync code
        assertEquals(FlacFrameHeader.Refusal.RESERVED_BIT, parse(h).getRefusal());
        h = FlacFrameBuilder.rawHeader(false, 1, 3, 10, 1, 4, new byte[0], new byte[0]);
        h[3] |= 0x01; // reserved bit after the sample size
        assertEquals(FlacFrameHeader.Refusal.RESERVED_BIT, parse(h).getRefusal());
    }

    @Test
    public void aWrongSyncCodeIsRefused() {
        byte[] h = FlacFrameBuilder.rawHeader(false, 1, 3, 10, 1, 4, new byte[0], new byte[0]);
        for (int bad : new int[]{0x00, 0xfe, 0xfc, 0xf0}) {
            byte[] copy = h.clone();
            copy[1] = (byte) bad;
            assertEquals("second byte " + bad, FlacFrameHeader.Refusal.BAD_SYNC, parse(copy).getRefusal());
        }
        byte[] copy = h.clone();
        copy[0] = 0x7f;
        assertEquals(FlacFrameHeader.Refusal.BAD_SYNC, parse(copy).getRefusal());
        // the header of the stream ("fLaC") is no frame
        assertEquals(FlacFrameHeader.Refusal.BAD_SYNC, parse("fLaC\u0080\0\0\"1234".getBytes(StandardCharsets.ISO_8859_1)).getRefusal());
    }

    @Test
    public void everySingleBitFlipInAHeaderIsRefusedOrReadDifferently() {
        // CRC-8 detects every single bit error: no flipped header may come back valid with the same meaning
        byte[] h = FlacFrameBuilder.rawHeader(false, 1234, 3, 10, 1, 4, new byte[0], new byte[0]);
        FlacFrameHeader good = parse(h);
        assertTrue(good.isValid());
        for (int byteIndex = 0; byteIndex < h.length; byteIndex++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] copy = h.clone();
                copy[byteIndex] ^= (byte) (1 << bit);
                assertFalse("flipped bit " + bit + " of byte " + byteIndex, parse(copy).isValid());
            }
        }
    }

    @Test
    public void aWrongCrcIsRefused() {
        byte[] h = FlacFrameBuilder.rawHeader(false, 1234, 3, 10, 1, 4, new byte[0], new byte[0]);
        h[h.length - 1] ^= 0x5a;
        assertEquals(FlacFrameHeader.Refusal.BAD_CRC, parse(h).getRefusal());
    }

    @Test
    public void everyTruncationIsRefusedAsTooShort() {
        byte[] h = FlacFrameBuilder.rawHeader(false, 130000, 7, 13, 1, 4, new byte[]{0x04, 0x7f}, new byte[]{(byte) 0xac, 0x44});
        assertTrue(parse(h).isValid());
        for (int cut = 0; cut < h.length; cut++) {
            FlacFrameHeader parsed = FlacFrameHeader.parse(h, 0, cut);
            assertFalse("cut at " + cut, parsed.isValid());
            assertNotNull(parsed.getRefusal());
        }
    }

    @Test
    public void theHeaderIsReadAtAnOffsetAndFollowedByAnything() {
        byte[] h = FlacFrameBuilder.fixedHeaderAt(1152L * 70, 1152, 48000, 2, 16);
        byte[] data = new byte[3 + h.length + 50];
        Arrays.fill(data, (byte) 0xaa);
        System.arraycopy(h, 0, data, 3, h.length);
        FlacFrameHeader parsed = FlacFrameHeader.parse(data, 3, data.length - 3);
        assertTrue(parsed.getRefusal() + "", parsed.isValid());
        assertEquals(1152L * 70, parsed.getFirstSample());
        assertEquals(h.length, parsed.getLength());
    }

    @Test
    public void badArgumentsAreRefusedNotThrown() {
        byte[] h = FlacFrameBuilder.fixedHeaderAt(0, 1152, 48000, 2, 16);
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(null, 0, 0).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(h, -1, 5).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(h, 0, -1).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(h, 0, h.length + 1).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(h, h.length + 1, 0).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(h, Integer.MAX_VALUE, Integer.MAX_VALUE).getRefusal());
        assertEquals(FlacFrameHeader.Refusal.TOO_SHORT, FlacFrameHeader.parse(new byte[0], 0, 0).getRefusal());
    }

    @Test
    public void junkNeverThrowsAndNeverReadsBeyondTheBytes() {
        Random random = new Random(319);
        int valid = 0;
        for (int i = 0; i < 300_000; i++) {
            int length = random.nextInt(24);
            byte[] data = new byte[length];
            random.nextBytes(data);
            if (length >= 2 && random.nextBoolean()) { // make a sync code half of the time, to get past the first check
                data[0] = (byte) 0xff;
                data[1] = (byte) (0xf8 | random.nextInt(4));
            }
            FlacFrameHeader parsed = FlacFrameHeader.parse(data, 0, length);
            if (parsed.isValid()) {
                valid++;
                assertTrue(parsed.getLength() <= length && parsed.getLength() >= 6);
                assertTrue(parsed.getBlockSize() >= 1 && parsed.getBlockSize() <= 65536);
                assertTrue(parsed.getFirstSample() >= 0);
            } else {
                assertNotNull(parsed.getRefusal());
            }
        }
        // a random header passes the CRC with a chance of 1 in 256 once the rest is plausible: a few, never most
        assertTrue("valid by chance: " + valid, valid < 300_000 / 50);
    }
}
