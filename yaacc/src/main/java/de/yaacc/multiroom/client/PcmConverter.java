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
package de.yaacc.multiroom.client;

/**
 * Converts samples to 16 bit little endian PCM, the only format the sink takes. Integer samples of more than 16 bit
 * keep their top 16 bits, rounded to nearest (halves up) and clipped; float samples (-1 to 1) are scaled, rounded and
 * clipped, NaN is silence. Pure.
 */
public final class PcmConverter {

    /** The sample encodings that can be converted. */
    public enum Encoding {
        PCM16(2), PCM24(3), PCM32(4), FLOAT32(4);

        private final int bytes;

        Encoding(int bytes) {
            this.bytes = bytes;
        }

        public int bytesPerSample() {
            return bytes;
        }

        /** The encoding of integer PCM with this many bits per sample, or null. */
        public static Encoding ofBits(int bits) {
            switch (bits) {
                case 16:
                    return PCM16;
                case 24:
                    return PCM24;
                case 32:
                    return PCM32;
                default:
                    return null;
            }
        }
    }

    private PcmConverter() {
    }

    /**
     * @param source      samples in {@code encoding}, little endian, interleaved
     * @param sampleCount how many samples (not frames) to convert; the source must hold at least that many
     * @return {@code sampleCount} 16 bit samples, little endian
     */
    public static byte[] toPcm16(byte[] source, int offset, int sampleCount, Encoding encoding) {
        if (sampleCount < 0 || offset < 0 || (long) offset + (long) sampleCount * encoding.bytes > source.length) {
            throw new IllegalArgumentException("source too short");
        }
        byte[] out = new byte[sampleCount * 2];
        int in = offset;
        for (int i = 0; i < sampleCount; i++) {
            int value;
            switch (encoding) {
                case PCM16:
                    out[2 * i] = source[in];
                    out[2 * i + 1] = source[in + 1];
                    in += 2;
                    continue;
                case PCM24: {
                    int v = (source[in] & 0xff) | ((source[in + 1] & 0xff) << 8) | (source[in + 2] << 16);
                    value = clip((v + 128) >> 8);
                    break;
                }
                case PCM32: {
                    long v = (source[in] & 0xffL) | ((source[in + 1] & 0xffL) << 8) | ((source[in + 2] & 0xffL) << 16)
                            | ((long) source[in + 3] << 24);
                    value = clip((int) ((v + 32768L) >> 16));
                    break;
                }
                default: {
                    int bits = (source[in] & 0xff) | ((source[in + 1] & 0xff) << 8) | ((source[in + 2] & 0xff) << 16)
                            | ((source[in + 3] & 0xff) << 24);
                    float f = Float.intBitsToFloat(bits);
                    value = Float.isNaN(f) ? 0 : clip((int) Math.floor(Math.max(-2f, Math.min(2f, f)) * 32768.0 + 0.5));
                    break;
                }
            }
            in += encoding.bytes;
            out[2 * i] = (byte) value;
            out[2 * i + 1] = (byte) (value >> 8);
        }
        return out;
    }

    private static int clip(int value) {
        return Math.max(-32768, Math.min(32767, value));
    }
}
