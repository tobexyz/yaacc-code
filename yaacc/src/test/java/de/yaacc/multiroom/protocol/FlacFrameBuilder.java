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

import java.io.ByteArrayOutputStream;

/**
 * Builds FLAC frame headers by the FLAC format specification (RFC 9639 section 9.1), independently of
 * {@link FlacFrameHeader} (a bitwise CRC-8, its own number coding), for the tests of the parser and for the simulated
 * server of the client tests.
 */
public final class FlacFrameBuilder {

    private FlacFrameBuilder() {
    }

    private static final int[] COMMON_RATES = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000};

    /** CRC-8, polynomial x^8 + x^2 + x + 1, initial value 0, bit by bit. */
    public static int crc8(byte[] data, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff;
            }
        }
        return crc;
    }

    /** The "UTF-8" coding of the frame or sample number, 1 to 7 bytes. */
    public static byte[] codedNumber(long value) {
        if (value < 0x80) {
            return new byte[]{(byte) value};
        }
        int extra = value < 0x800 ? 1 : value < 0x10000 ? 2 : value < 0x200000 ? 3 : value < 0x4000000 ? 4 : value < 0x80000000L ? 5 : 6;
        byte[] out = new byte[extra + 1];
        long rest = value;
        for (int i = extra; i >= 1; i--) {
            out[i] = (byte) (0x80 | (rest & 0x3f));
            rest >>>= 6;
        }
        int lead = (0xff00 >>> (extra + 1)) & 0xff; // extra + 1 leading one bits
        out[0] = (byte) (lead | (int) rest);
        return out;
    }

    /** A header with every field given as the code it has in the frame. */
    public static byte[] rawHeader(boolean variable, long number, int blockCode, int rateCode, int channelCode, int sizeCode,
                                   byte[] blockExtra, byte[] rateExtra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xff);
        out.write(variable ? 0xf9 : 0xf8);
        out.write((blockCode << 4) | rateCode);
        out.write((channelCode << 4) | (sizeCode << 1));
        byte[] coded = codedNumber(number);
        out.write(coded, 0, coded.length);
        out.write(blockExtra, 0, blockExtra.length);
        out.write(rateExtra, 0, rateExtra.length);
        byte[] bytes = out.toByteArray();
        out.write(crc8(bytes, bytes.length));
        return out.toByteArray();
    }

    public static int blockCode(int blockSize) {
        if (blockSize == 192) {
            return 1;
        }
        for (int code = 2; code <= 5; code++) {
            if (blockSize == 576 << (code - 2)) {
                return code;
            }
        }
        for (int code = 8; code <= 15; code++) {
            if (blockSize == 256 << (code - 8)) {
                return code;
            }
        }
        return blockSize <= 256 ? 6 : 7;
    }

    public static byte[] blockExtra(int blockSize) {
        int code = blockCode(blockSize);
        if (code == 6) {
            return new byte[]{(byte) (blockSize - 1)};
        }
        if (code == 7) {
            return new byte[]{(byte) ((blockSize - 1) >> 8), (byte) (blockSize - 1)};
        }
        return new byte[0];
    }

    public static int rateCode(int rate) {
        for (int code = 1; code < COMMON_RATES.length; code++) {
            if (COMMON_RATES[code] == rate) {
                return code;
            }
        }
        if (rate % 1000 == 0 && rate / 1000 <= 255) {
            return 12;
        }
        if (rate <= 65535) {
            return 13;
        }
        return 14;
    }

    public static byte[] rateExtra(int rate) {
        int code = rateCode(rate);
        if (code == 12) {
            return new byte[]{(byte) (rate / 1000)};
        }
        if (code == 13) {
            return new byte[]{(byte) (rate >> 8), (byte) rate};
        }
        if (code == 14) {
            return new byte[]{(byte) ((rate / 10) >> 8), (byte) (rate / 10)};
        }
        return new byte[0];
    }

    /** The common case: independent channels, 16 bit, codes picked from the values. */
    public static byte[] header(boolean variable, long number, int blockSize, int rate, int channels, int bits) {
        int sizeCode = bits == 8 ? 1 : bits == 12 ? 2 : bits == 16 ? 4 : bits == 20 ? 5 : bits == 24 ? 6 : 7;
        return rawHeader(variable, number, blockCode(blockSize), rateCode(rate), channels - 1, sizeCode, blockExtra(blockSize),
                rateExtra(rate));
    }

    /** The header of the fixed block size frame that starts at {@code firstSample} (a multiple of the block size). */
    public static byte[] fixedHeaderAt(long firstSample, int blockSize, int rate, int channels, int bits) {
        return header(false, firstSample / blockSize, blockSize, rate, channels, bits);
    }
}
