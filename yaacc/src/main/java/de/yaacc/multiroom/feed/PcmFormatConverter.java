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

/**
 * Turns PCM of any supported encoding and channel count into 16 bit little-endian stereo, the sample format of the
 * Snapcast stream ({@link PcmFormat#SNAPCAST_DEFAULT}) apart from the rate. The sample rate is <b>not</b> touched: a
 * rate conversion is done by the Media3 {@code SonicAudioProcessor} in front of the tap (see
 * {@link PcmTapAudioProcessor}); this class is the part that is plain Java and runs in a JVM test.
 *
 * <ul>
 *   <li>16 bit stereo passes through bit for bit.</li>
 *   <li>Mono is copied to both sides. More than two channels are folded down (see below), never clipped by the sum.</li>
 *   <li>8 bit (unsigned), 24 bit, 32 bit and float are converted to 16 bit with rounding (half up) and clipping at
 *       -32768 and 32767; NaN becomes silence.</li>
 * </ul>
 *
 * <p><b>Fold-down.</b> The channel order is the one of Android and WAV: 3 = FL FR FC, 4 = FL FR BL BR,
 * 5 = FL FR FC BL BR, 6 = FL FR FC LFE BL BR, 7 = FL FR FC LFE BC SL SR, 8 = FL FR FC LFE BL BR SL SR. The front
 * channel counts with 1, the centre, the back centre and every surround channel with 0.7071 (-3 dB) on the side they
 * belong to (the back centre on both), the LFE is dropped. Each side is divided by the sum of its weights, so equal
 * full-scale channels give exactly full scale and the fold-down can not overflow; the price is that a signal on the
 * front left alone is about 7.7 dB quieter at 5.1 (the same normalisation FFmpeg uses by default).
 *
 * <p>Instances keep a scratch array and are not thread safe; use one per thread (the playback thread of the tap).
 */
public final class PcmFormatConverter {

    /** The converter always produces stereo. */
    public static final int OUTPUT_CHANNELS = 2;
    /** The most input channels it folds down. */
    public static final int MAX_INPUT_CHANNELS = 8;
    /** Bytes of one output frame: two 16 bit samples. */
    public static final int OUTPUT_FRAME_BYTES = 4;

    private static final double SURROUND = Math.sqrt(0.5);
    /** Float samples are limited to this many 16 bit full scales before mixing, so that infinity can not become NaN. */
    private static final double FLOAT_LIMIT = 1_000_000.0;

    private final PcmEncoding encoding;
    private final int channels;
    private final int inputFrameBytes;
    private final boolean passThrough;
    private final double[] left;
    private final double[] right;
    private final double[] samples;

    /**
     * @param encoding the encoding of the input
     * @param channels the channels of the input, 1 to {@link #MAX_INPUT_CHANNELS}
     * @throws IllegalArgumentException for an unsupported channel count
     */
    public PcmFormatConverter(PcmEncoding encoding, int channels) {
        if (encoding == null) {
            throw new IllegalArgumentException("no encoding");
        }
        if (!isSupported(channels)) {
            throw new IllegalArgumentException("unsupported channel count");
        }
        this.encoding = encoding;
        this.channels = channels;
        this.inputFrameBytes = channels * encoding.bytesPerSample;
        this.passThrough = encoding == PcmEncoding.SIGNED_16 && channels == 2;
        this.left = new double[channels];
        this.right = new double[channels];
        fillWeights(channels, left, right);
        this.samples = new double[channels];
    }

    /** True for 1 to {@link #MAX_INPUT_CHANNELS} channels. */
    public static boolean isSupported(int channels) {
        return channels >= 1 && channels <= MAX_INPUT_CHANNELS;
    }

    /** True when the input already is 16 bit stereo: the bytes are copied as they are. */
    public boolean isPassThrough() {
        return passThrough;
    }

    /** Bytes of one input frame. */
    public int inputFrameBytes() {
        return inputFrameBytes;
    }

    /** The size of the output for {@code inputBytes} bytes of input (whole frames). */
    public int outputBytesFor(int inputBytes) {
        return (inputBytes / inputFrameBytes) * OUTPUT_FRAME_BYTES;
    }

    /**
     * Converts whole frames.
     *
     * @return the number of bytes written to {@code out}
     * @throws IllegalArgumentException when {@code inLength} is not a multiple of the frame size, or a range lies
     *                                  outside its array (nothing is written then)
     */
    public int convert(byte[] in, int inOffset, int inLength, byte[] out, int outOffset) {
        if (inOffset < 0 || inLength < 0 || (long) inOffset + inLength > in.length) {
            throw new IllegalArgumentException("input range outside the array");
        }
        if (inLength % inputFrameBytes != 0) {
            throw new IllegalArgumentException("input is not whole frames");
        }
        final int frames = inLength / inputFrameBytes;
        final long needed = (long) frames * OUTPUT_FRAME_BYTES;
        if (outOffset < 0 || (long) outOffset + needed > out.length) {
            throw new IllegalArgumentException("output range outside the array");
        }
        if (passThrough) {
            System.arraycopy(in, inOffset, out, outOffset, inLength);
            return inLength;
        }
        int position = inOffset;
        int target = outOffset;
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                samples[channel] = decode(in, position);
                position += encoding.bytesPerSample;
            }
            double l = 0;
            double r = 0;
            for (int channel = 0; channel < channels; channel++) {
                l += left[channel] * samples[channel];
                r += right[channel] * samples[channel];
            }
            put16(out, target, clip(l));
            put16(out, target + 2, clip(r));
            target += OUTPUT_FRAME_BYTES;
        }
        return (int) needed;
    }

    /** One input sample in units of a 16 bit step (so a 16 bit sample is exact). */
    private double decode(byte[] in, int p) {
        switch (encoding) {
            case UNSIGNED_8:
                return ((in[p] & 0xFF) - 128) * 256.0;
            case SIGNED_16:
                return (short) ((in[p] & 0xFF) | (in[p + 1] << 8));
            case SIGNED_24: {
                int value = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8) | (in[p + 2] << 16);
                return value / 256.0;
            }
            case SIGNED_32:
                return littleEndianInt(in, p) / 65536.0;
            case FLOAT_32:
            default: {
                double value = Float.intBitsToFloat(littleEndianInt(in, p)) * 32768.0;
                if (value != value) {
                    return 0;
                }
                return Math.max(-FLOAT_LIMIT, Math.min(FLOAT_LIMIT, value));
            }
        }
    }

    private static int littleEndianInt(byte[] in, int p) {
        return (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8) | ((in[p + 2] & 0xFF) << 16) | (in[p + 3] << 24);
    }

    private static int clip(double value) {
        long rounded = Math.round(value);
        if (rounded > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (rounded < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (int) rounded;
    }

    private static void put16(byte[] out, int p, int value) {
        out[p] = (byte) value;
        out[p + 1] = (byte) (value >> 8);
    }

    /** The weights of every input channel for the left and the right output, see the class comment. */
    private static void fillWeights(int channels, double[] l, double[] r) {
        switch (channels) {
            case 1:
                l[0] = 1;
                r[0] = 1;
                return;
            case 2:
                l[0] = 1;
                r[1] = 1;
                return;
            default:
                break;
        }
        l[0] = 1;
        r[1] = 1;
        switch (channels) {
            case 3: // FL FR FC
                l[2] = SURROUND;
                r[2] = SURROUND;
                break;
            case 4: // FL FR BL BR
                l[2] = SURROUND;
                r[3] = SURROUND;
                break;
            case 5: // FL FR FC BL BR
                l[2] = SURROUND;
                r[2] = SURROUND;
                l[3] = SURROUND;
                r[4] = SURROUND;
                break;
            case 6: // FL FR FC LFE BL BR
                l[2] = SURROUND;
                r[2] = SURROUND;
                l[4] = SURROUND;
                r[5] = SURROUND;
                break;
            case 7: // FL FR FC LFE BC SL SR
                l[2] = SURROUND;
                r[2] = SURROUND;
                l[4] = SURROUND;
                r[4] = SURROUND;
                l[5] = SURROUND;
                r[6] = SURROUND;
                break;
            default: // 8: FL FR FC LFE BL BR SL SR
                l[2] = SURROUND;
                r[2] = SURROUND;
                l[4] = SURROUND;
                r[5] = SURROUND;
                l[6] = SURROUND;
                r[7] = SURROUND;
                break;
        }
        normalise(l);
        normalise(r);
    }

    private static void normalise(double[] weights) {
        double sum = 0;
        for (double weight : weights) {
            sum += weight;
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] /= sum;
        }
    }
}
