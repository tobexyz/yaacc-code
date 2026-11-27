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
package de.yaacc.sendspin.hub;

/**
 * A FLAC encoder for the stream to a Sendspin player (issue #325, task 11.3): 16 bit stereo PCM in, one complete FLAC frame per
 * chunk out, and the {@code codec_header} ({@code fLaC} marker and STREAMINFO) that {@code stream/start} carries. Written for what
 * the hub needs and nothing more: fixed blocks (the chunk of the stream), the fixed predictors of order 0 to 4, a constant or a
 * verbatim subframe where that is smaller, Rice coding with partitions, and the four channel assignments (independent, left/side,
 * right/side, mid/side) of which the smallest is taken for every frame. Lossless; no state between frames except the frame number.
 * <p>
 * The format is the one of the FLAC specification (RFC 9639): frame header with a CRC-8, subframes, CRC-16 of the frame. An instance
 * is for one stream and one thread.
 */
public final class FlacEncoder {

    private static final int BITS_PER_SAMPLE = 16;
    private static final int MAX_FIXED_ORDER = 4;
    private static final int MAX_PARTITION_ORDER = 8;
    private static final int MAX_RICE_PARAMETER = 14; // 15 is the escape code of the 4 bit method
    private static final int[] CRC8 = new int[256];
    private static final int[] CRC16 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c8 = i;
            int c16 = i << 8;
            for (int b = 0; b < 8; b++) {
                c8 = (c8 & 0x80) != 0 ? ((c8 << 1) ^ 0x07) & 0xFF : (c8 << 1) & 0xFF;
                c16 = (c16 & 0x8000) != 0 ? ((c16 << 1) ^ 0x8005) & 0xFFFF : (c16 << 1) & 0xFFFF;
            }
            CRC8[i] = c8;
            CRC16[i] = c16;
        }
    }

    private final int sampleRate;
    private long frameNumber;

    public FlacEncoder(int sampleRate) {
        if (sampleRate < 1 || sampleRate > 655350) {
            throw new IllegalArgumentException("sample rate");
        }
        this.sampleRate = sampleRate;
    }

    /**
     * The codec header of the stream: the {@code fLaC} marker and the STREAMINFO block (34 bytes), as the specification of Sendspin
     * demands for the codec {@code flac}. Sizes and the number of samples are unknown (0) in a stream; the checksum of the audio is unset.
     */
    public static byte[] streamHeader(int sampleRate, int blockFrames) {
        BitWriter w = new BitWriter(48);
        w.write('f', 8);
        w.write('L', 8);
        w.write('a', 8);
        w.write('C', 8);
        w.write(0x80, 8); // the last metadata block, type STREAMINFO
        w.write(34, 24);
        w.write(blockFrames, 16); // minimum block size
        w.write(blockFrames, 16); // maximum block size
        w.write(0, 24); // minimum frame size: unknown
        w.write(0, 24); // maximum frame size: unknown
        w.write(sampleRate, 20);
        w.write(1, 3); // channels - 1
        w.write(BITS_PER_SAMPLE - 1, 5);
        w.write(0, 36); // total samples: unknown
        for (int i = 0; i < 16; i++) {
            w.write(0, 8); // MD5 of the audio: not set
        }
        return w.toByteArray();
    }

    /** Encodes {@code length} bytes of 16 bit little endian stereo PCM (a whole number of frames) as one FLAC frame. */
    public byte[] encode(byte[] pcm, int offset, int length) {
        if (length < 4 || length % 4 != 0 || length / 4 > 65535) {
            throw new IllegalArgumentException("pcm length");
        }
        int n = length / 4;
        int[] left = new int[n];
        int[] right = new int[n];
        int[] mid = new int[n];
        int[] side = new int[n];
        for (int i = 0; i < n; i++) {
            int p = offset + i * 4;
            int l = (short) ((pcm[p] & 0xFF) | (pcm[p + 1] << 8));
            int r = (short) ((pcm[p + 2] & 0xFF) | (pcm[p + 3] << 8));
            left[i] = l;
            right[i] = r;
            mid[i] = (l + r) >> 1;
            side[i] = l - r;
        }
        Subframe sl = Subframe.best(left, BITS_PER_SAMPLE);
        Subframe sr = Subframe.best(right, BITS_PER_SAMPLE);
        Subframe sm = Subframe.best(mid, BITS_PER_SAMPLE);
        Subframe ss = Subframe.best(side, BITS_PER_SAMPLE + 1);
        long independent = sl.bits + sr.bits;
        long leftSide = sl.bits + ss.bits;
        long rightSide = ss.bits + sr.bits;
        long midSide = sm.bits + ss.bits;
        int assignment = 1; // independent
        Subframe first = sl;
        Subframe second = sr;
        long best = independent;
        if (leftSide < best) {
            best = leftSide;
            assignment = 8;
            first = sl;
            second = ss;
        }
        if (rightSide < best) {
            best = rightSide;
            assignment = 9;
            first = ss;
            second = sr;
        }
        if (midSide < best) {
            assignment = 10;
            first = sm;
            second = ss;
        }
        BitWriter w = new BitWriter(length / 2 + 64);
        w.write(0xFFF8, 16); // sync code and the fixed block size strategy
        w.write(blockSizeCode(n), 4);
        w.write(0, 4); // the sample rate is in STREAMINFO
        w.write(assignment, 4);
        w.write(4, 3); // 16 bits per sample
        w.write(0, 1);
        writeFrameNumber(w, frameNumber++);
        if (blockSizeCode(n) == 6) {
            w.write(n - 1, 8);
        } else if (blockSizeCode(n) == 7) {
            w.write(n - 1, 16);
        }
        w.write(crc8(w.bytes(), w.byteCount()), 8);
        first.write(w);
        second.write(w);
        w.alignToByte();
        w.write(crc16(w.bytes(), w.byteCount()), 16);
        return w.toByteArray();
    }

    // ------------------------------------------------------------------ the frame header

    private static int blockSizeCode(int n) {
        switch (n) {
            case 192:
                return 1;
            case 576:
                return 2;
            case 1152:
                return 3;
            case 2304:
                return 4;
            case 4608:
                return 5;
            case 256:
                return 8;
            case 512:
                return 9;
            case 1024:
                return 10;
            case 2048:
                return 11;
            case 4096:
                return 12;
            case 8192:
                return 13;
            case 16384:
                return 14;
            case 32768:
                return 15;
            default:
                return n <= 256 ? 6 : 7;
        }
    }

    /** The frame number in the UTF-8 style of the specification (up to 31 bits). */
    private static void writeFrameNumber(BitWriter w, long number) {
        if (number < 0x80) {
            w.write((int) number, 8);
            return;
        }
        int continuation = 1;
        while (continuation < 6 && number >= (1L << (6 * continuation + (6 - continuation)))) {
            continuation++;
        }
        int lead = (0xFF00 >> (continuation + 1)) & 0xFF;
        w.write(lead | (int) (number >> (6 * continuation)), 8);
        for (int i = continuation - 1; i >= 0; i--) {
            w.write(0x80 | (int) ((number >> (6 * i)) & 0x3F), 8);
        }
    }

    static int crc8(byte[] data, int length) {
        int c = 0;
        for (int i = 0; i < length; i++) {
            c = CRC8[c ^ (data[i] & 0xFF)];
        }
        return c;
    }

    static int crc16(byte[] data, int length) {
        int c = 0;
        for (int i = 0; i < length; i++) {
            c = ((c << 8) & 0xFFFF) ^ CRC16[((c >> 8) ^ data[i]) & 0xFF];
        }
        return c;
    }

    // ------------------------------------------------------------------ one channel

    /** The cheapest coding found for one channel of a block: what it costs in bits and how to write it. */
    private static final class Subframe {
        static final int CONSTANT = 0;
        static final int VERBATIM = 1;
        static final int FIXED = 2;

        int kind;
        int order;
        int partitionOrder;
        int[] parameters;
        int[] samples;
        int[] residual;
        int bps;
        long bits;

        static Subframe best(int[] samples, int bps) {
            int n = samples.length;
            Subframe s = new Subframe();
            s.samples = samples;
            s.bps = bps;
            boolean constant = true;
            for (int i = 1; i < n; i++) {
                if (samples[i] != samples[0]) {
                    constant = false;
                    break;
                }
            }
            if (constant) {
                s.kind = CONSTANT;
                s.bits = 8 + bps;
                return s;
            }
            s.kind = VERBATIM;
            s.bits = 8L + (long) n * bps;
            for (int order = 0; order <= MAX_FIXED_ORDER && order < n; order++) {
                int[] res = residual(samples, order);
                long warmup = (long) order * bps;
                // the residual of the first 'order' samples does not exist: the partitions hold the rest
                for (int po = 0; po <= MAX_PARTITION_ORDER; po++) {
                    if ((n & ((1 << po) - 1)) != 0 || (n >> po) <= order) {
                        break;
                    }
                    int[] parameters = new int[1 << po];
                    long cost = 8 + warmup + 2 + 4 + riceCost(res, order, n, po, parameters);
                    if (cost < s.bits) {
                        s.kind = FIXED;
                        s.order = order;
                        s.partitionOrder = po;
                        s.parameters = parameters;
                        s.residual = res;
                        s.bits = cost;
                    }
                }
            }
            return s;
        }

        private static int[] residual(int[] x, int order) {
            int n = x.length;
            int[] r = new int[n];
            for (int i = order; i < n; i++) {
                long p;
                switch (order) {
                    case 0:
                        p = 0;
                        break;
                    case 1:
                        p = x[i - 1];
                        break;
                    case 2:
                        p = 2L * x[i - 1] - x[i - 2];
                        break;
                    case 3:
                        p = 3L * x[i - 1] - 3L * x[i - 2] + x[i - 3];
                        break;
                    default:
                        p = 4L * x[i - 1] - 6L * x[i - 2] + 4L * x[i - 3] - x[i - 4];
                        break;
                }
                r[i] = (int) (x[i] - p);
            }
            return r;
        }

        private static int fold(int r) {
            return (r << 1) ^ (r >> 31);
        }

        /** Chooses the Rice parameter of every partition and returns the bits of all partitions (parameters included). */
        private static long riceCost(int[] res, int order, int n, int po, int[] parameters) {
            int parts = 1 << po;
            int size = n >> po;
            long total = 0;
            for (int p = 0; p < parts; p++) {
                int from = p == 0 ? order : p * size;
                int to = (p + 1) * size;
                int count = to - from;
                long sum = 0;
                for (int i = from; i < to; i++) {
                    sum += fold(res[i]) & 0xFFFFFFFFL;
                }
                int guess = 0;
                if (count > 0 && sum > count) {
                    guess = 63 - Long.numberOfLeadingZeros(sum / count);
                }
                long bestCost = Long.MAX_VALUE;
                int bestK = 0;
                for (int k = Math.max(0, guess - 1); k <= Math.min(MAX_RICE_PARAMETER, guess + 1); k++) {
                    long cost = (long) count * (k + 1);
                    for (int i = from; i < to; i++) {
                        cost += (fold(res[i]) & 0xFFFFFFFFL) >> k;
                    }
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestK = k;
                    }
                }
                parameters[p] = bestK;
                total += 4 + bestCost;
            }
            return total;
        }

        void write(BitWriter w) {
            int n = samples.length;
            w.write(0, 1);
            switch (kind) {
                case CONSTANT:
                    w.write(0, 6);
                    w.write(0, 1);
                    w.write(samples[0], bps);
                    return;
                case VERBATIM:
                    w.write(1, 6);
                    w.write(0, 1);
                    for (int i = 0; i < n; i++) {
                        w.write(samples[i], bps);
                    }
                    return;
                default:
                    w.write(8 + order, 6);
                    w.write(0, 1);
                    for (int i = 0; i < order; i++) {
                        w.write(samples[i], bps);
                    }
                    w.write(0, 2); // Rice coding with 4 bit parameters
                    w.write(partitionOrder, 4);
                    int parts = 1 << partitionOrder;
                    int size = n >> partitionOrder;
                    for (int p = 0; p < parts; p++) {
                        int k = parameters[p];
                        w.write(k, 4);
                        int from = p == 0 ? order : p * size;
                        int to = (p + 1) * size;
                        for (int i = from; i < to; i++) {
                            int u = fold(residual[i]);
                            w.writeUnary(u >>> k);
                            if (k > 0) {
                                w.write(u & ((1 << k) - 1), k);
                            }
                        }
                    }
            }
        }
    }

    // ------------------------------------------------------------------ bits

    /** Writes bits, most significant first. */
    private static final class BitWriter {
        private byte[] buffer;
        private int bytes;
        private long accumulator;
        private int accumulated;

        BitWriter(int capacity) {
            buffer = new byte[Math.max(16, capacity)];
        }

        /** Writes the low {@code bits} bits of {@code value} (up to 36). */
        void write(long value, int bits) {
            if (bits == 0) {
                return;
            }
            accumulator = (accumulator << bits) | (value & ((1L << bits) - 1));
            accumulated += bits;
            while (accumulated >= 8) {
                put((int) (accumulator >> (accumulated - 8)) & 0xFF);
                accumulated -= 8;
            }
            accumulator &= (1L << accumulated) - 1;
        }

        void writeUnary(int zeros) {
            int left = zeros;
            while (left >= 32) {
                write(0, 32);
                left -= 32;
            }
            write(1, left + 1);
        }

        void alignToByte() {
            if (accumulated > 0) {
                write(0, 8 - accumulated);
            }
        }

        private void put(int b) {
            if (bytes == buffer.length) {
                buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2);
            }
            buffer[bytes++] = (byte) b;
        }

        byte[] bytes() {
            return buffer;
        }

        /** The whole bytes written so far (a CRC is taken over these while the writer is at a byte boundary). */
        int byteCount() {
            return bytes;
        }

        byte[] toByteArray() {
            alignToByte();
            return java.util.Arrays.copyOf(buffer, bytes);
        }
    }
}
