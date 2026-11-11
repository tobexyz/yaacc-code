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

/** The RIFF WAVE header of the pcm codec: what is read, what is not trusted, what is refused. */
public class PcmFormatTest {

    /** A 44 byte header with the given fields; the data length is whatever the test passes. */
    private static byte[] wave(int formatTag, int channels, long sampleRate, int bits, int blockAlign, long dataLength) {
        return Bytes.concat(Bytes.ascii("RIFF"), Bytes.u32(36), Bytes.ascii("WAVE"),
                Bytes.ascii("fmt "), Bytes.u32(16), Bytes.u16(formatTag), Bytes.u16(channels), Bytes.u32(sampleRate),
                Bytes.u32(sampleRate * blockAlign), Bytes.u16(blockAlign), Bytes.u16(bits),
                Bytes.ascii("data"), Bytes.u32(dataLength));
    }

    private static byte[] wave(int channels, long sampleRate, int bits) {
        return wave(1, channels, sampleRate, bits, channels * bits / 8, 0);
    }

    private static void assertRejected(SnapcastProtocolException.Kind kind, byte[] header) {
        try {
            PcmFormat.parse(header);
            fail("expected " + kind);
        } catch (SnapcastProtocolException e) {
            assertEquals(kind, e.getKind());
        }
    }

    @Test
    public void readsTheHandWrittenHeader() throws Exception {
        PcmFormat format = PcmFormat.parse(Bytes.WAVE_48000_STEREO_16);

        assertEquals(48000, format.getSampleRate());
        assertEquals(2, format.getChannels());
        assertEquals(16, format.getBitsPerSample());
        assertEquals(4, format.getFrameBytes());
    }

    @Test
    public void readsTheSupportedRanges() throws Exception {
        assertEquals(1, PcmFormat.parse(wave(1, 8000, 16)).getChannels());
        assertEquals(8, PcmFormat.parse(wave(8, 192000, 32)).getChannels());
        assertEquals(24, PcmFormat.parse(wave(2, 44100, 24)).getBitsPerSample());
        assertEquals(6, PcmFormat.parse(wave(2, 44100, 24)).getFrameBytes());
        assertEquals(32, PcmFormat.parse(wave(2, 96000, 32)).getBitsPerSample());
    }

    @Test
    public void theLengthsInTheHeaderAreNotTrusted() throws Exception {
        for (long dataLength : new long[]{0, 0xffffffffL, 0x7fffffffL, 12345}) {
            assertEquals(48000, PcmFormat.parse(wave(1, 2, 48000, 16, 4, dataLength)).getSampleRate());
        }
        byte[] header = wave(2, 48000, 16);
        header[4] = (byte) 0xff; // the RIFF size
        header[5] = (byte) 0xff;
        header[6] = (byte) 0xff;
        header[7] = (byte) 0xff;
        assertEquals(48000, PcmFormat.parse(header).getSampleRate());
    }

    @Test
    public void aChunkBeforeTheDataChunkIsSkippedByItsLength() throws Exception {
        byte[] plain = wave(2, 48000, 16);
        byte[] withList = Bytes.concat(Arrays.copyOf(plain, 36), Bytes.ascii("LIST"), Bytes.u32(5), Bytes.of(1, 2, 3, 4, 5),
                Bytes.of(0), // the pad byte of an odd length
                Arrays.copyOfRange(plain, 36, 44));

        assertEquals(48000, PcmFormat.parse(withList).getSampleRate());
    }

    @Test
    public void aLongerFormatChunkIsReadByItsFirst16Bytes() throws Exception {
        byte[] plain = wave(2, 48000, 16);
        byte[] longFmt = Bytes.concat(Arrays.copyOf(plain, 16), Bytes.u32(18), Arrays.copyOfRange(plain, 20, 36),
                Bytes.of(0, 0), Arrays.copyOfRange(plain, 36, 44));

        assertEquals(2, PcmFormat.parse(longFmt).getChannels());
    }

    @Test
    public void headersThatAreNotWaveAreRefused() {
        byte[] ok = wave(2, 48000, 16);
        byte[] notRiff = ok.clone();
        notRiff[0] = 'X';
        byte[] notWave = ok.clone();
        notWave[8] = 'X';
        byte[] noFmt = ok.clone();
        noFmt[12] = 'x';
        byte[] noData = Arrays.copyOf(ok, 36);
        byte[] dataNamedWrong = ok.clone();
        dataNamedWrong[36] = 'x';

        assertRejected(SnapcastProtocolException.Kind.MALFORMED, notRiff);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, notWave);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, noFmt);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, noData);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, dataNamedWrong);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, new byte[0]);
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, null);
    }

    @Test
    public void aHeaderThatEndsInsideAFieldIsRefused() {
        byte[] ok = wave(2, 48000, 16);
        for (int cut : new int[]{4, 11, 15, 19}) {
            assertRejected(SnapcastProtocolException.Kind.MALFORMED, Arrays.copyOf(ok, cut)); // no complete chunk header
        }
        for (int cut : new int[]{25, 35}) {
            assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, Arrays.copyOf(ok, cut)); // the chunk promises more
        }
    }

    @Test
    public void aFormatChunkThatIsTooShortOrLongerThanTheHeaderIsRefused() {
        byte[] ok = wave(2, 48000, 16);
        byte[] shortFmt = ok.clone();
        shortFmt[16] = 15;
        byte[] hugeFmt = ok.clone();
        hugeFmt[16] = (byte) 0xff;
        hugeFmt[17] = (byte) 0xff;
        hugeFmt[18] = (byte) 0xff;
        hugeFmt[19] = (byte) 0xff;

        assertRejected(SnapcastProtocolException.Kind.MALFORMED, shortFmt);
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, hugeFmt);
    }

    @Test
    public void aChunkLengthBeyondTheHeaderIsRefusedAndTheWalkIsBounded() {
        byte[] ok = wave(2, 48000, 16);
        byte[] listTooLong = Bytes.concat(Arrays.copyOf(ok, 12), Bytes.ascii("LIST"), Bytes.u32(0xfffffff0L), new byte[8]);
        assertRejected(SnapcastProtocolException.Kind.BAD_LENGTH, listTooLong);

        byte[] many = Arrays.copyOf(ok, 12);
        for (int i = 0; i < 200; i++) {
            many = Bytes.concat(many, Bytes.ascii("JUNK"), Bytes.u32(0));
        }
        assertRejected(SnapcastProtocolException.Kind.MALFORMED, many);
    }

    @Test
    public void onlyIntegerPcmIsAccepted() {
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(3, 2, 48000, 32, 8, 0)); // float
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(0xfffe, 2, 48000, 16, 4, 0));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(0, 2, 48000, 16, 4, 0));
    }

    @Test
    public void valuesOutsideTheRangesTheEngineSupportsAreRefused() {
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(0, 48000, 16));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(9, 48000, 16));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(2, 7999, 16));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(2, 192001, 16));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(2, 0, 16));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(2, 0xffffffffL, 16));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 8, 2, 0));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 12, 3, 0));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 64, 16, 0));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 0, 0, 0));
    }

    @Test
    public void aBlockAlignThatDoesNotMatchChannelsAndBitsIsRefused() {
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 16, 2, 0));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 16, 8, 0));
        assertRejected(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, wave(1, 2, 48000, 16, 0, 0));
    }
}
