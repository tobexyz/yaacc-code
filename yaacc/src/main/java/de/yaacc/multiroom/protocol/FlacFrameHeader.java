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

/**
 * The header of one FLAC frame, read from the first bytes of a chunk of the flac codec: where in the stream (in frames, per
 * channel) the chunk starts. The server's encoder (libFLAC, see {@code flac_encoder.cpp} of Snapcast) cuts its output at frame
 * boundaries: every chunk starts with a frame header, and the number coded in it is the position of the audio that the
 * server's encoder was given, whatever the client's decoder does with it later (a platform codec may hold a block back).
 * That every chunk of a real server starts this way is read from the source of Snapcast 0.35.0, not captured from a real
 * server; a chunk that does not is refused here and the engine counts frames instead (the guard is then off).
 *
 * <p>Layout (FLAC format specification, "frame header"; RFC 9639 section 9.1; checked here against frames made by a
 * conforming encoder, see {@code FlacFrameHeaderTest}):
 * <pre>
 *   14 bits sync 0b11111111111110, 1 bit reserved (0), 1 bit blocking strategy (0 fixed, 1 variable block size)
 *   4 bits block size code, 4 bits sample rate code
 *   4 bits channel assignment, 3 bits sample size code, 1 bit reserved (0)
 *   the coded number: UTF-8 style, 1 to 7 bytes (fixed: the frame number, variable: the sample number)
 *   0, 1 or 2 bytes of "uncommon" block size (codes 0110 and 0111: value - 1)
 *   0, 1 or 2 bytes of "uncommon" sample rate (codes 1100, 1101, 1110)
 *   1 byte CRC-8 (polynomial x^8 + x^2 + x + 1, initial value 0) over all bytes of the header before it
 * </pre>
 *
 * <p>Pure and total: {@link #parse} never throws and never allocates by a number of the data; a header that is not
 * valid comes back with a {@link Refusal}. The position of a fixed block size stream is the frame number times the block size
 * of this frame (the block size of the stream; only the last frame of a stream may be shorter, and the server does not
 * end its stream).
 */
public final class FlacFrameHeader {

    /** Why a header was not accepted. */
    public enum Refusal {
        TOO_SHORT, BAD_SYNC, RESERVED_BIT, BAD_BLOCK_SIZE, BAD_SAMPLE_RATE, BAD_CHANNELS, BAD_SAMPLE_SIZE, BAD_NUMBER, BAD_CRC
    }

    /**
     * Largest header: 4 fixed bytes (sync and flags, block size and sample rate codes, channel and sample size codes) + 7
     * (the coded number of a variable block size stream) + 2 (uncommon block size) + 2 (uncommon sample rate) + 1 (CRC-8) = 16.
     * No valid header is longer, so no read goes beyond 16 bytes however many bytes follow.
     */
    private static final int MAX_HEADER = 16;

    private final Refusal refusal;
    private final boolean variableBlockSize;
    private final int blockSize;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final long codedNumber;
    private final long firstSample;
    private final int length;

    private FlacFrameHeader(Refusal refusal, boolean variable, int blockSize, int sampleRate, int channels, int bitsPerSample,
                            long codedNumber, long firstSample, int length) {
        this.refusal = refusal;
        this.variableBlockSize = variable;
        this.blockSize = blockSize;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.codedNumber = codedNumber;
        this.firstSample = firstSample;
        this.length = length;
    }

    private static FlacFrameHeader refused(Refusal refusal) {
        return new FlacFrameHeader(refusal, false, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean isValid() {
        return refusal == null;
    }

    /** Null for a valid header. */
    public Refusal getRefusal() {
        return refusal;
    }

    public boolean isVariableBlockSize() {
        return variableBlockSize;
    }

    /** Frames (samples per channel) in this block. */
    public int getBlockSize() {
        return blockSize;
    }

    /** Hz; 0 when the frame says "see STREAMINFO". */
    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    /** 0 when the frame says "see STREAMINFO". */
    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /** The frame number (fixed block size) or the sample number (variable block size), as coded. */
    public long getCodedNumber() {
        return codedNumber;
    }

    /** The stream position of the first frame (sample per channel) of this block. */
    public long getFirstSample() {
        return firstSample;
    }

    /** Bytes of the header including the CRC; the frame's subframes follow. */
    public int getLength() {
        return length;
    }

    /** Reads the header at {@code offset}; {@code length} bytes from there are available. */
    public static FlacFrameHeader parse(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset > data.length || length > data.length - offset) {
            return refused(Refusal.TOO_SHORT);
        }
        int limit = Math.min(length, MAX_HEADER);
        if (limit < 6) { // sync (2) + 2 bytes + a number of at least 1 byte + crc
            return refused(Refusal.TOO_SHORT);
        }
        int b0 = data[offset] & 0xff;
        int b1 = data[offset + 1] & 0xff;
        if (b0 != 0xff || (b1 & 0xfc) != 0xf8) {
            return refused(Refusal.BAD_SYNC);
        }
        if ((b1 & 0x02) != 0) {
            return refused(Refusal.RESERVED_BIT);
        }
        boolean variable = (b1 & 0x01) != 0;
        int b2 = data[offset + 2] & 0xff;
        int b3 = data[offset + 3] & 0xff;
        int blockCode = b2 >>> 4;
        int rateCode = b2 & 0x0f;
        int channelCode = b3 >>> 4;
        int sizeCode = (b3 >>> 1) & 0x07;
        if ((b3 & 0x01) != 0) {
            return refused(Refusal.RESERVED_BIT);
        }
        int channels;
        if (channelCode <= 7) {
            channels = channelCode + 1;
        } else if (channelCode <= 10) {
            channels = 2; // left/side, side/right, mid/side
        } else {
            return refused(Refusal.BAD_CHANNELS);
        }
        int bits;
        switch (sizeCode) {
            case 0:
                bits = 0;
                break;
            case 1:
                bits = 8;
                break;
            case 2:
                bits = 12;
                break;
            case 4:
                bits = 16;
                break;
            case 5:
                bits = 20;
                break;
            case 6:
                bits = 24;
                break;
            case 7:
                bits = 32;
                break;
            default:
                return refused(Refusal.BAD_SAMPLE_SIZE);
        }
        if (blockCode == 0) {
            return refused(Refusal.BAD_BLOCK_SIZE);
        }
        if (rateCode == 15) {
            return refused(Refusal.BAD_SAMPLE_RATE);
        }
        // the coded number: 1 byte 0xxxxxxx, 2 bytes 110xxxxx 10xxxxxx, ... 7 bytes 11111110 and six continuation bytes
        int at = 4;
        int first = data[offset + at] & 0xff;
        int extra;
        long number;
        if (first < 0x80) {
            extra = 0;
            number = first;
        } else if (first >= 0xc0 && first < 0xfe) {
            extra = first >= 0xfc ? 5 : first >= 0xf8 ? 4 : first >= 0xf0 ? 3 : first >= 0xe0 ? 2 : 1;
            number = first & (0x3f >>> extra);
        } else if (first == 0xfe) {
            extra = 6;
            number = 0;
        } else {
            return refused(Refusal.BAD_NUMBER); // 10xxxxxx alone, or 0xff
        }
        if (extra > (variable ? 6 : 5)) {
            return refused(Refusal.BAD_NUMBER); // a fixed block size stream numbers frames with at most 31 bits
        }
        if (at + 1 + extra > limit) {
            return refused(Refusal.TOO_SHORT);
        }
        for (int i = 1; i <= extra; i++) {
            int next = data[offset + at + i] & 0xff;
            if ((next & 0xc0) != 0x80) {
                return refused(Refusal.BAD_NUMBER);
            }
            number = (number << 6) | (next & 0x3f);
        }
        if (extra > 0 && number < MIN_FOR_LENGTH[extra]) {
            return refused(Refusal.BAD_NUMBER); // an overlong coding
        }
        at += 1 + extra;
        int blockSize;
        if (blockCode == 1) {
            blockSize = 192;
        } else if (blockCode <= 5) {
            blockSize = 576 << (blockCode - 2);
        } else if (blockCode == 6) {
            if (at + 1 > limit) {
                return refused(Refusal.TOO_SHORT);
            }
            blockSize = (data[offset + at] & 0xff) + 1;
            at += 1;
        } else if (blockCode == 7) {
            if (at + 2 > limit) {
                return refused(Refusal.TOO_SHORT);
            }
            blockSize = (((data[offset + at] & 0xff) << 8) | (data[offset + at + 1] & 0xff)) + 1;
            at += 2;
        } else {
            blockSize = 256 << (blockCode - 8);
        }
        int rate;
        switch (rateCode) {
            case 0:
                rate = 0;
                break;
            case 1:
                rate = 88200;
                break;
            case 2:
                rate = 176400;
                break;
            case 3:
                rate = 192000;
                break;
            case 4:
                rate = 8000;
                break;
            case 5:
                rate = 16000;
                break;
            case 6:
                rate = 22050;
                break;
            case 7:
                rate = 24000;
                break;
            case 8:
                rate = 32000;
                break;
            case 9:
                rate = 44100;
                break;
            case 10:
                rate = 48000;
                break;
            case 11:
                rate = 96000;
                break;
            case 12:
                if (at + 1 > limit) {
                    return refused(Refusal.TOO_SHORT);
                }
                rate = (data[offset + at] & 0xff) * 1000;
                at += 1;
                break;
            case 13:
                if (at + 2 > limit) {
                    return refused(Refusal.TOO_SHORT);
                }
                rate = ((data[offset + at] & 0xff) << 8) | (data[offset + at + 1] & 0xff);
                at += 2;
                break;
            default: // 14
                if (at + 2 > limit) {
                    return refused(Refusal.TOO_SHORT);
                }
                rate = (((data[offset + at] & 0xff) << 8) | (data[offset + at + 1] & 0xff)) * 10;
                at += 2;
                break;
        }
        if (rateCode >= 12 && rate == 0) {
            return refused(Refusal.BAD_SAMPLE_RATE);
        }
        if (at + 1 > limit) {
            return refused(Refusal.TOO_SHORT);
        }
        int crc = 0;
        for (int i = 0; i < at; i++) {
            crc = CRC8[(crc ^ (data[offset + i] & 0xff)) & 0xff];
        }
        if (crc != (data[offset + at] & 0xff)) {
            return refused(Refusal.BAD_CRC);
        }
        long firstSample = variable ? number : number * blockSize;
        return new FlacFrameHeader(null, variable, blockSize, rate, channels, bits, number, firstSample, at + 1);
    }

    /** The smallest number that needs {@code extra + 1} bytes of the coding (index: extra continuation bytes). */
    private static final long[] MIN_FOR_LENGTH = {0, 0x80L, 0x800L, 0x10000L, 0x200000L, 0x4000000L, 0x80000000L};

    private static final int[] CRC8 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int value = i;
            for (int bit = 0; bit < 8; bit++) {
                value = (value & 0x80) != 0 ? ((value << 1) ^ 0x07) & 0xff : (value << 1) & 0xff;
            }
            CRC8[i] = value;
        }
    }

    /** CRC-8 as the header uses it (for tests that build headers). */
    static int crc8(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc = CRC8[(crc ^ (data[offset + i] & 0xff)) & 0xff];
        }
        return crc;
    }
}
