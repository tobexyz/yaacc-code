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

import java.nio.charset.StandardCharsets;

/**
 * The format of a pcm stream, read from the RIFF WAVE header of the CodecHeader message. Only what the engine needs
 * is read and checked; the lengths in the header (the RIFF size and the length of the data chunk, which a streaming
 * header can not know) are not trusted and not used.
 */
public final class PcmFormat {

    private static final int MAX_CHUNKS = 16;

    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;

    private PcmFormat(int sampleRate, int channels, int bitsPerSample) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /** Bytes of one frame (one sample of every channel). */
    public int getFrameBytes() {
        return channels * bitsPerSample / 8;
    }

    /**
     * @throws SnapcastProtocolException MALFORMED for a header that is no RIFF WAVE header, BAD_LENGTH for a chunk that
     *                                   does not fit, UNSUPPORTED_FORMAT for values the engine does not support
     */
    public static PcmFormat parse(byte[] header) throws SnapcastProtocolException {
        if (header == null || header.length < 12 || !tag(header, 0, "RIFF") || !tag(header, 8, "WAVE")) {
            throw malformed("not a RIFF WAVE header");
        }
        int position = 12;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int blockAlign = 0;
        int formatTag = 0;
        boolean formatSeen = false;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            if (header.length - position < 8) {
                throw malformed("no data chunk");
            }
            boolean data = tag(header, position, "data");
            boolean format = tag(header, position, "fmt ");
            long length = u32(header, position + 4);
            position += 8;
            if (data) {
                if (!formatSeen) {
                    throw malformed("no format chunk");
                }
                return check(formatTag, channels, rate, bits, blockAlign);
            }
            if (length > header.length - position) {
                throw new SnapcastProtocolException(SnapcastProtocolException.Kind.BAD_LENGTH, "chunk beyond the header");
            }
            if (format) {
                if (length < 16) {
                    throw malformed("format chunk too short");
                }
                formatTag = u16(header, position);
                channels = u16(header, position + 2);
                long rateValue = u32(header, position + 4);
                rate = rateValue > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rateValue;
                blockAlign = u16(header, position + 12);
                bits = u16(header, position + 14);
                formatSeen = true;
            }
            long next = position + length + (length & 1); // chunks are padded to an even length
            if (next > header.length) {
                next = header.length;
            }
            position = (int) next;
        }
        throw malformed("too many chunks before the data chunk");
    }

    private static PcmFormat check(int formatTag, int channels, int rate, int bits, int blockAlign)
            throws SnapcastProtocolException {
        if (formatTag != 1) {
            throw unsupported("not integer PCM");
        }
        if (channels < SnapcastProtocolLimits.MIN_CHANNELS || channels > SnapcastProtocolLimits.MAX_CHANNELS) {
            throw unsupported("channel count not supported");
        }
        if (rate < SnapcastProtocolLimits.MIN_SAMPLE_RATE || rate > SnapcastProtocolLimits.MAX_SAMPLE_RATE) {
            throw unsupported("sample rate not supported");
        }
        if (bits != 16 && bits != 24 && bits != 32) {
            throw unsupported("sample size not supported");
        }
        if (blockAlign != channels * bits / 8) {
            throw unsupported("block alignment does not match");
        }
        return new PcmFormat(rate, channels, bits);
    }

    private static boolean tag(byte[] data, int offset, String tag) {
        if (data.length - offset < 4) {
            return false;
        }
        byte[] expected = tag.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 4; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8) | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    private static SnapcastProtocolException malformed(String text) {
        return new SnapcastProtocolException(SnapcastProtocolException.Kind.MALFORMED, text);
    }

    private static SnapcastProtocolException unsupported(String text) {
        return new SnapcastProtocolException(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, text);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PcmFormat)) {
            return false;
        }
        PcmFormat that = (PcmFormat) other;
        return sampleRate == that.sampleRate && channels == that.channels && bitsPerSample == that.bitsPerSample;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * sampleRate + channels) + bitsPerSample;
    }
}
