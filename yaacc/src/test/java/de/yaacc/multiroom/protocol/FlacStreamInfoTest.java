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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/** The FLAC header of the flac codec: "fLaC" and a STREAMINFO block of exactly 34 bytes, then well-formed blocks only. */
public class FlacStreamInfoTest {

    /** The 8 bytes after the frame sizes of a STREAMINFO: rate (20 bits), channels - 1 (3), bits - 1 (5), samples (36). */
    private static byte[] rateChannelsBitsSamples(long rate, int channels, int bits, long samples) {
        long high = (rate << 12) | ((long) (channels - 1) << 9) | ((long) (bits - 1) << 4) | ((samples >>> 32) & 0xf);
        long low = samples & 0xffffffffL;
        byte[] out = new byte[8];
        for (int i = 0; i < 4; i++) {
            out[i] = (byte) (high >>> (8 * (3 - i)));
            out[4 + i] = (byte) (low >>> (8 * (3 - i)));
        }
        return out;
    }

    private static byte[] streamInfo(int minBlock, int maxBlock, long rate, int channels, int bits, long samples) {
        return Bytes.concat(Bytes.of(minBlock >> 8, minBlock & 0xff, maxBlock >> 8, maxBlock & 0xff),
                new byte[6], rateChannelsBitsSamples(rate, channels, bits, samples), new byte[16]);
    }

    /** "fLaC" + a block header (flags/type, 24 bit big endian length) + the body. */
    private static byte[] block(int flagsAndType, int length, byte[] body) {
        return Bytes.concat(Bytes.of(flagsAndType, (length >> 16) & 0xff, (length >> 8) & 0xff, length & 0xff), body);
    }

    private static byte[] flac(byte[]... blocks) {
        return Bytes.concat(Bytes.ascii("fLaC"), Bytes.concat(blocks));
    }

    private static void assertRejected(SnapcastProtocolException.Kind kind, byte[] header) {
        try {
            FlacStreamInfo.parse(header);
            fail("expected " + kind);
        } catch (SnapcastProtocolException e) {
            assertEquals(kind, e.getKind());
        }
    }

    @Test
    public void readsTheHandWrittenHeader() throws Exception {
        FlacStreamInfo info = FlacStreamInfo.parse(Bytes.FLAC_48000_STEREO_16);

        assertEquals(48000, info.getSampleRate());
        assertEquals(2, info.getChannels());
        assertEquals(16, info.getBitsPerSample());
        assertEquals(1024, info.getMinBlockSize());
        assertEquals(1024, info.getMaxBlockSize());
        assertEquals(0L, info.getTotalSamples());
    }

    @Test
    public void thePackedFieldsAreReadForOtherValues() throws Exception {
        FlacStreamInfo info = FlacStreamInfo.parse(flac(block(0x80, 34, streamInfo(960, 960, 44100, 1, 24, 0x123456789L))));

        assertEquals(44100, info.getSampleRate());
        assertEquals(1, info.getChannels());
        assertEquals(24, info.getBitsPerSample());
        assertEquals(0x123456789L, info.getTotalSamples());
        assertEquals(8, FlacStreamInfo.parse(flac(block(0x80, 34, streamInfo(16, 4608, 192000, 8, 16, 0)))).getChannels());
    }

    @Test
    public void wellFormedBlocksAfterTheStreamInfoAreAccepted() throws Exception {
        byte[] withComment = flac(block(0x00, 34, streamInfo(1024, 1024, 48000, 2, 16, 0)),
                block(0x84, 8, new byte[8]));

        assertEquals(48000, FlacStreamInfo.parse(withComment).getSampleRate());
    }

    @Test
    public void aChainThatEndsAtTheEndWithoutTheLastFlagIsAccepted() throws Exception {
        assertEquals(48000, FlacStreamInfo.parse(flac(block(0x00, 34, streamInfo(1024, 1024, 48000, 2, 16, 0)))).getSampleRate());
    }

    @Test
    public void theStreamInfoMustBeFirstAndExactly34Bytes() {
        byte[] body = streamInfo(1024, 1024, 48000, 2, 16, 0);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(block(0x84, 34, body))); // type 4 first
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(block(0x80, 33, Arrays.copyOf(body, 33))));
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(block(0x80, 35, Bytes.concat(body, Bytes.of(0)))));
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(block(0x80, 0, new byte[0])));
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(block(0x80, 0xffffff, body)));
    }

    @Test
    public void aHeaderWithoutTheMagicOrCutShortIsRefused() {
        byte[] ok = Bytes.FLAC_48000_STEREO_16;
        byte[] noMagic = ok.clone();
        noMagic[0] = 'x';

        assertRejected(SnapcastProtocolException.Kind.MALFORMED, noMagic);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, Arrays.copyOf(ok, 3));
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, Arrays.copyOf(ok, 4));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, Arrays.copyOf(ok, 8)); // the block promises 34 bytes
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, Arrays.copyOf(ok, 41));
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, new byte[0]);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, null);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, Bytes.WAVE_48000_STEREO_16);
    }

    @Test
    public void aFollowingBlockThatDoesNotFitIsRefused() {
        byte[] info = block(0x00, 34, streamInfo(1024, 1024, 48000, 2, 16, 0));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, flac(info, block(0x84, 100, new byte[8])));
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, flac(info, block(0x84, 0xffffff, new byte[8])));
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(info, Bytes.of(0x84, 0)));
    }

    @Test
    public void bytesAfterTheLastBlockAreRefused() {
        byte[] info = block(0x80, 34, streamInfo(1024, 1024, 48000, 2, 16, 0));

        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(info, Bytes.of(1, 2, 3)));
    }

    @Test
    public void theChainOfBlocksIsBounded() {
        byte[][] blocks = new byte[1 + 100][];
        blocks[0] = block(0x00, 34, streamInfo(1024, 1024, 48000, 2, 16, 0));
        for (int i = 1; i < blocks.length; i++) {
            blocks[i] = block(i == blocks.length - 1 ? 0x82 : 0x02, 0, new byte[0]);
        }

        assertRejected(SnapcastProtocolException.Kind.MALFORMED, flac(blocks));
    }

    @Test
    public void valuesOutsideTheRangesTheEngineSupportsAreRefused() {
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, flac(block(0x80, 34, streamInfo(1024, 1024, 7999, 2, 16, 0))));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, flac(block(0x80, 34, streamInfo(1024, 1024, 0, 2, 16, 0))));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, flac(block(0x80, 34, streamInfo(1024, 1024, 192001, 2, 16, 0))));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, flac(block(0x80, 34, streamInfo(1024, 1024, 48000, 2, 8, 0))));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, flac(block(0x80, 34, streamInfo(1024, 1024, 48000, 2, 12, 0))));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, flac(block(0x80, 34, streamInfo(1024, 1024, 48000, 2, 32, 0))));
    }
}
