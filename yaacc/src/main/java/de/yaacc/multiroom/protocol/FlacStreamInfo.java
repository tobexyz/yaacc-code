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
 * The STREAMINFO block of a FLAC header (CodecHeader of the flac codec). The header must be "fLaC", then a STREAMINFO
 * block of exactly 34 bytes, then metadata blocks whose lengths fit into the header and whose chain ends where the
 * header ends. The numbers are those of the engine's supported ranges.
 */
public final class FlacStreamInfo {

    private static final int STREAMINFO_LENGTH = 34;
    private static final int MAX_BLOCKS = 32;

    private final int minBlockSize;
    private final int maxBlockSize;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final long totalSamples;

    private FlacStreamInfo(int minBlockSize, int maxBlockSize, int sampleRate, int channels, int bitsPerSample,
                           long totalSamples) {
        this.minBlockSize = minBlockSize;
        this.maxBlockSize = maxBlockSize;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.totalSamples = totalSamples;
    }

    public int getMinBlockSize() {
        return minBlockSize;
    }

    public int getMaxBlockSize() {
        return maxBlockSize;
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

    /** The total number of samples per channel; 0 when unknown (a stream that is still being made). */
    public long getTotalSamples() {
        return totalSamples;
    }

    /**
     * @throws SnapcastProtocolException MALFORMED for a header that is no FLAC header, BAD_LENGTH for a block that does
     *                                   not fit, UNSUPPORTED_FORMAT for values the engine does not support
     */
    public static FlacStreamInfo parse(byte[] header) throws SnapcastProtocolException {
        if (header == null || header.length < 4 || header[0] != 'f' || header[1] != 'L' || header[2] != 'a'
                || header[3] != 'C') {
            throw malformed("not a FLAC header");
        }
        int position = 4;
        FlacStreamInfo info = null;
        for (int block = 0; block < MAX_BLOCKS; block++) {
            if (info != null && position == header.length) {
                return info; // the chain ends with the header, without the last-block flag set
            }
            if (header.length - position < 4) {
                throw malformed("metadata block header cut short");
            }
            int flags = header[position] & 0xff;
            boolean last = (flags & 0x80) != 0;
            int type = flags & 0x7f;
            int length = ((header[position + 1] & 0xff) << 16) | ((header[position + 2] & 0xff) << 8)
                    | (header[position + 3] & 0xff);
            position += 4;
            if (type == 127) {
                throw malformed("invalid block type");
            }
            if (info == null && (type != 0 || length != STREAMINFO_LENGTH)) {
                throw malformed("the first block is not a STREAMINFO block of 34 bytes");
            }
            if (length > header.length - position) {
                throw new SnapcastProtocolException(SnapcastProtocolException.Kind.BAD_LENGTH, "block beyond the header");
            }
            if (info == null) {
                info = streamInfo(header, position);
            }
            position += length;
            if (last) {
                if (position != header.length) {
                    throw malformed("bytes after the last block");
                }
                return info;
            }
        }
        throw malformed("too many metadata blocks");
    }

    private static FlacStreamInfo streamInfo(byte[] data, int start) throws SnapcastProtocolException {
        int minBlock = ((data[start] & 0xff) << 8) | (data[start + 1] & 0xff);
        int maxBlock = ((data[start + 2] & 0xff) << 8) | (data[start + 3] & 0xff);
        long packed = 0;
        for (int i = 0; i < 8; i++) { // 20 bits rate, 3 bits channels - 1, 5 bits bits - 1, 36 bits samples (big endian)
            packed = (packed << 8) | (data[start + 10 + i] & 0xffL);
        }
        int rate = (int) ((packed >>> 44) & 0xfffff);
        int channels = (int) ((packed >>> 41) & 0x7) + 1;
        int bits = (int) ((packed >>> 36) & 0x1f) + 1;
        long samples = packed & 0xfffffffffL;
        if (rate < SnapcastProtocolLimits.MIN_SAMPLE_RATE || rate > SnapcastProtocolLimits.MAX_SAMPLE_RATE) {
            throw unsupported("sample rate not supported");
        }
        if (bits != 16 && bits != 24) {
            throw unsupported("sample size not supported");
        }
        return new FlacStreamInfo(minBlock, maxBlock, rate, channels, bits, samples);
    }

    private static SnapcastProtocolException malformed(String text) {
        return new SnapcastProtocolException(SnapcastProtocolException.Kind.MALFORMED, text);
    }

    private static SnapcastProtocolException unsupported(String text) {
        return new SnapcastProtocolException(SnapcastProtocolException.Kind.UNSUPPORTED_FORMAT, text);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FlacStreamInfo)) {
            return false;
        }
        FlacStreamInfo that = (FlacStreamInfo) other;
        return minBlockSize == that.minBlockSize && maxBlockSize == that.maxBlockSize && sampleRate == that.sampleRate
                && channels == that.channels && bitsPerSample == that.bitsPerSample && totalSamples == that.totalSamples;
    }

    @Override
    public int hashCode() {
        long h = 17;
        h = 31 * h + minBlockSize;
        h = 31 * h + maxBlockSize;
        h = 31 * h + sampleRate;
        h = 31 * h + channels;
        h = 31 * h + bitsPerSample;
        h = 31 * h + totalSamples;
        return Long.hashCode(h);
    }
}
