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
package de.yaacc.multiroom.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Random;

/** The conversion to 16 bit stereo: channel mix and bit depth, bit-exact where nothing has to change. */
public class PcmFormatConverterTest {

    // ---- helpers: little-endian samples

    private static byte[] s16(int... samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            out[2 * i] = (byte) samples[i];
            out[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return out;
    }

    private static int[] readS16(byte[] bytes, int length) {
        int[] out = new int[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
        }
        return out;
    }

    private static byte[] s24(int... samples) {
        byte[] out = new byte[samples.length * 3];
        for (int i = 0; i < samples.length; i++) {
            out[3 * i] = (byte) samples[i];
            out[3 * i + 1] = (byte) (samples[i] >> 8);
            out[3 * i + 2] = (byte) (samples[i] >> 16);
        }
        return out;
    }

    private static byte[] s32(int... samples) {
        byte[] out = new byte[samples.length * 4];
        for (int i = 0; i < samples.length; i++) {
            for (int b = 0; b < 4; b++) {
                out[4 * i + b] = (byte) (samples[i] >> (8 * b));
            }
        }
        return out;
    }

    private static byte[] f32(float... samples) {
        int[] bits = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            bits[i] = Float.floatToRawIntBits(samples[i]);
        }
        return s32(bits);
    }

    private static int[] convert(PcmFormatConverter converter, byte[] input) {
        byte[] out = new byte[converter.outputBytesFor(input.length)];
        int written = converter.convert(input, 0, input.length, out, 0);
        assertEquals(out.length, written);
        return readS16(out, written);
    }

    // ---- bit-exact pass through

    @Test
    public void sixteenBitStereoPassesThroughBitExact() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, 2);
        byte[] input = new byte[4 * 1000];
        new Random(7).nextBytes(input);
        byte[] out = new byte[input.length];

        int written = converter.convert(input, 0, input.length, out, 0);

        assertTrue(converter.isPassThrough());
        assertEquals(input.length, written);
        assertArrayEquals(input, out);
    }

    @Test
    public void passThroughHonoursOffsets() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, 2);
        byte[] input = s16(1, -1, 2, -2, 3, -3);
        byte[] out = new byte[20];

        int written = converter.convert(input, 4, 8, out, 6);

        assertEquals(8, written);
        assertArrayEquals(s16(2, -2, 3, -3), java.util.Arrays.copyOfRange(out, 6, 14));
    }

    @Test
    public void everyOtherFormatIsNotAPassThrough() {
        assertFalse(new PcmFormatConverter(PcmEncoding.SIGNED_16, 1).isPassThrough());
        assertFalse(new PcmFormatConverter(PcmEncoding.SIGNED_24, 2).isPassThrough());
        assertFalse(new PcmFormatConverter(PcmEncoding.FLOAT_32, 2).isPassThrough());
    }

    // ---- channels

    @Test
    public void monoBecomesStereoWithTheSameSampleOnBothSides() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, 1);

        int[] out = convert(converter, s16(0, 1, -1, 32767, -32768, 1234));

        assertArrayEquals(new int[]{0, 0, 1, 1, -1, -1, 32767, 32767, -32768, -32768, 1234, 1234}, out);
    }

    @Test
    public void fiveDotOneIsFoldedDownWithoutTheLfe() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, 6);
        double a = Math.sqrt(0.5);

        // front left, front right, centre, LFE, back left, back right
        int[] onlyFrontLeft = convert(converter, s16(10000, 0, 0, 0, 0, 0));
        int[] onlyCentre = convert(converter, s16(0, 0, 10000, 0, 0, 0));
        int[] onlyLfe = convert(converter, s16(0, 0, 0, 10000, 0, 0));
        int[] onlyBackRight = convert(converter, s16(0, 0, 0, 0, 0, 10000));

        double sum = 1 + a + a;
        assertEquals(Math.round(10000 / sum), onlyFrontLeft[0]);
        assertEquals(0, onlyFrontLeft[1]);
        assertEquals(Math.round(10000 * a / sum), onlyCentre[0]);
        assertEquals(onlyCentre[0], onlyCentre[1]);
        assertArrayEquals("the LFE channel is dropped", new int[]{0, 0}, onlyLfe);
        assertEquals(0, onlyBackRight[0]);
        assertEquals(Math.round(10000 * a / sum), onlyBackRight[1]);
    }

    /**
     * The channel order of Android (Media3 1.11.1 {@code Util.getAudioTrackChannelConfig}) for each count, and what each
     * channel does in the fold-down: front channels 1 on their side, centre and back centre on both sides, surrounds
     * on their side, the LFE nowhere; each side is divided by the sum of its weights.
     */
    private static final String[][] LAYOUTS = {
            null, null, null,
            {"FL", "FR", "FC"},
            {"FL", "FR", "BL", "BR"},
            {"FL", "FR", "FC", "BL", "BR"},
            {"FL", "FR", "FC", "LFE", "BL", "BR"},
            {"FL", "FR", "FC", "LFE", "BL", "BR", "BC"},
            {"FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR"}};

    private static double[] weightsOf(String channel) {
        double a = Math.sqrt(0.5);
        switch (channel) {
            case "FL":
                return new double[]{1, 0};
            case "FR":
                return new double[]{0, 1};
            case "FC":
            case "BC":
                return new double[]{a, a};
            case "BL":
            case "SL":
                return new double[]{a, 0};
            case "BR":
            case "SR":
                return new double[]{0, a};
            default: // LFE
                return new double[]{0, 0};
        }
    }

    @Test
    public void everyChannelOfThreeToEightChannelsLandsOnItsOwnSide() {
        for (int channels = 3; channels <= 8; channels++) {
            String[] layout = LAYOUTS[channels];
            double leftSum = 0;
            double rightSum = 0;
            for (String name : layout) {
                leftSum += weightsOf(name)[0];
                rightSum += weightsOf(name)[1];
            }
            PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, channels);
            for (int channel = 0; channel < channels; channel++) {
                int[] frame = new int[channels];
                frame[channel] = 20000;

                int[] out = convert(converter, s16(frame));

                double[] w = weightsOf(layout[channel]);
                String where = channels + " channels, " + layout[channel];
                assertEquals(where + " left", 20000 * w[0] / leftSum, out[0], 1.0);
                assertEquals(where + " right", 20000 * w[1] / rightSum, out[1], 1.0);
            }
        }
    }

    @Test
    public void aFullScaleSignalOnEveryChannelDoesNotClip() {
        for (int channels = 1; channels <= PcmFormatConverter.MAX_INPUT_CHANNELS; channels++) {
            PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, channels);
            int[] frame = new int[channels];
            java.util.Arrays.fill(frame, 32767);

            int[] out = convert(converter, s16(frame));

            // a normalised downmix of equal full-scale channels is exactly full scale: never above, no wrap-around
            assertEquals("left with " + channels + " channels", 32767, out[0]);
            assertEquals("right with " + channels + " channels", 32767, out[1]);
        }
    }

    @Test
    public void everyChannelCountFromOneToEightIsSupportedAndNineIsNot() {
        for (int channels = 1; channels <= 8; channels++) {
            assertTrue(PcmFormatConverter.isSupported(channels));
            new PcmFormatConverter(PcmEncoding.SIGNED_16, channels);
        }
        assertFalse(PcmFormatConverter.isSupported(0));
        assertFalse(PcmFormatConverter.isSupported(9));
        try {
            new PcmFormatConverter(PcmEncoding.SIGNED_16, 9);
            fail("nine channels must be refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // ---- bit depth

    @Test
    public void eightBitUnsignedBecomesSigned16Bit() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.UNSIGNED_8, 2);

        int[] out = convert(converter, new byte[]{0, (byte) 128, (byte) 255, 1});

        assertArrayEquals(new int[]{-32768, 0, 255 * 256 - 32768, 1 * 256 - 32768}, out);
    }

    @Test
    public void twentyFourBitIsRoundedToSixteenBitAndClipped() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_24, 2);

        int[] out = convert(converter, s24(0x000000, 0x000100, // 0, 256 -> 1
                0x00007F, 0x000080,                              // 127 -> 0, 128 -> 1 (round half up)
                0x7FFFFF, 0x800000,                              // positive overflow of the rounding clips; the minimum stays
                -0x000081, -0x000080));                          // -129 -> -1, -128 -> 0 (half up)

        assertArrayEquals(new int[]{0, 1, 0, 1, 32767, -32768, -1, 0}, out);
    }

    @Test
    public void thirtyTwoBitIsRoundedToSixteenBitAndClipped() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_32, 2);

        int[] out = convert(converter, s32(0, 0x00010000, 0x7FFFFFFF, Integer.MIN_VALUE, 0x00007FFF, 0x00008000));

        assertArrayEquals(new int[]{0, 1, 32767, -32768, 0, 1}, out);
    }

    @Test
    public void floatIsScaledRoundedAndClipped() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.FLOAT_32, 2);

        int[] out = convert(converter, f32(0f, 0.5f, 1.0f, -1.0f, 1.5f, -1.5f,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.5f));

        assertArrayEquals(new int[]{0, 16384, 32767, -32768, 32767, -32768, 0, 32767, -32768, -16384}, out);
    }

    @Test
    public void monoFloatBecomesStereo() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.FLOAT_32, 1);

        assertArrayEquals(new int[]{16384, 16384, -16384, -16384}, convert(converter, f32(0.5f, -0.5f)));
    }

    @Test
    public void sixChannelFloatCombinesTheBothSteps() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.FLOAT_32, 6);
        double a = Math.sqrt(0.5);

        int[] out = convert(converter, f32(0f, 0f, 0.5f, 0f, 0f, 0f));

        assertEquals(Math.round(16384 * a / (1 + 2 * a)), out[0]);
        assertEquals(out[0], out[1]);
    }

    // ---- sizes and arguments

    @Test
    public void outputSizeIsTheNumberOfFramesTimesFourBytes() {
        assertEquals(4 * 10, new PcmFormatConverter(PcmEncoding.SIGNED_16, 1).outputBytesFor(2 * 10));
        assertEquals(4 * 10, new PcmFormatConverter(PcmEncoding.FLOAT_32, 6).outputBytesFor(24 * 10));
        assertEquals(4 * 10, new PcmFormatConverter(PcmEncoding.SIGNED_24, 2).outputBytesFor(6 * 10));
    }

    @Test
    public void inputThatIsNotWholeFramesIsRefused() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_24, 2);
        try {
            converter.convert(new byte[7], 0, 7, new byte[16], 0);
            fail("a partial frame must be refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void anOutputThatIsTooSmallIsRefusedAndWritesNothing() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, 1);
        byte[] out = new byte[7];
        try {
            converter.convert(s16(1, 2), 0, 4, out, 0);
            fail("the output must hold 8 bytes");
        } catch (IllegalArgumentException expected) {
            assertArrayEquals(new byte[7], out);
        }
    }

    @Test
    public void aRangeOutsideTheArrayIsRefused() {
        PcmFormatConverter converter = new PcmFormatConverter(PcmEncoding.SIGNED_16, 2);
        try {
            converter.convert(new byte[8], 4, 8, new byte[8], 0);
            fail("input range");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            converter.convert(new byte[8], 0, 8, new byte[8], 4);
            fail("output range");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void formatDescribesItselfLikeASnapcastSampleFormat() {
        assertEquals("48000:16:2", new PcmFormat(48000, 16, 2).toSampleFormat());
        assertEquals(192_000, new PcmFormat(48000, 16, 2).bytesPerSecond());
        assertEquals(4, new PcmFormat(48000, 16, 2).bytesPerFrame());
        assertEquals(new PcmFormat(44100, 16, 2), new PcmFormat(44100, 16, 2));
    }

    @Test
    public void aSilentInputStaysSilent() {
        for (PcmEncoding encoding : PcmEncoding.values()) {
            PcmFormatConverter converter = new PcmFormatConverter(encoding, 2);
            byte[] silence = new byte[encoding.bytesPerSample * 2 * 100];
            if (encoding == PcmEncoding.UNSIGNED_8) {
                java.util.Arrays.fill(silence, (byte) 128);
            }
            int[] out = convert(converter, silence);
            for (int sample : out) {
                assertEquals(encoding.name(), 0, sample);
            }
        }
    }
}
