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
package de.yaacc.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

import de.yaacc.sendspin.hub.FlacEncoder;

/**
 * The FLAC encoder of the hub (task 11.3), checked by decoding what it writes with a small decoder of the FLAC specification (RFC 9639)
 * written for the test, which also checks both checksums and the layout of the header. An outside decoder (ffmpeg) was run over the
 * same output by hand; see the task notes.
 */
public class FlacEncoderTest {

    /** A decoder of the subset the encoder writes (constant, verbatim, fixed predictors; Rice coding), strict about the layout. */
    static final class Decoder {
        private final byte[] data;
        private int pos;
        private int bit;
        int sampleRate;
        int blockSize;

        Decoder(byte[] data) {
            this.data = data;
        }

        long read(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                int b = (data[pos] >> (7 - bit)) & 1;
                v = (v << 1) | b;
                if (++bit == 8) {
                    bit = 0;
                    pos++;
                }
            }
            return v;
        }

        long readSigned(int n) {
            long v = read(n);
            return (v & (1L << (n - 1))) != 0 ? v - (1L << n) : v;
        }

        int unary() {
            int q = 0;
            while (read(1) == 0) {
                q++;
            }
            return q;
        }

        /** Decodes one frame: the interleaved samples (left, right, ...). */
        short[] frame(long expectedNumber) {
            int start = pos;
            assertEquals(0xFFF8, read(16));
            int bsCode = (int) read(4);
            assertEquals("sample rate from STREAMINFO", 0, read(4));
            int assignment = (int) read(4);
            assertEquals("16 bits per sample", 4, read(3));
            assertEquals(0, read(1));
            long first = read(8);
            long number;
            if (first < 0x80) {
                number = first;
            } else {
                int extra = Integer.numberOfLeadingZeros(~(int) (first << 24)) - 1;
                number = first & (0x3F >> extra);
                for (int i = 0; i < extra; i++) {
                    long c = read(8);
                    assertEquals(0x80, c & 0xC0);
                    number = (number << 6) | (c & 0x3F);
                }
            }
            assertEquals(expectedNumber, number);
            int n;
            if (bsCode == 6) {
                n = (int) read(8) + 1;
            } else if (bsCode == 7) {
                n = (int) read(16) + 1;
            } else {
                int[] table = {0, 192, 576, 1152, 2304, 4608, 0, 0, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768};
                n = table[bsCode];
            }
            int crc8 = FlacEncoderTestAccess.crc8(data, pos - start, start);
            assertEquals("header crc", crc8, read(8));
            int[][] ch = new int[2][];
            int[] bps = {16, 16};
            switch (assignment) {
                case 8:
                    bps[1] = 17;
                    break;
                case 9:
                    bps[0] = 17;
                    break;
                case 10:
                    bps[1] = 17;
                    break;
                default:
                    assertEquals(1, assignment);
            }
            for (int c = 0; c < 2; c++) {
                ch[c] = subframe(n, bps[c]);
            }
            if (bit != 0) {
                assertEquals("padding is zero", 0, read(8 - bit));
            }
            int crc16 = FlacEncoderTestAccess.crc16(data, pos - start, start);
            assertEquals("frame crc", crc16, read(16));
            short[] out = new short[n * 2];
            for (int i = 0; i < n; i++) {
                int l;
                int r;
                switch (assignment) {
                    case 8:
                        l = ch[0][i];
                        r = l - ch[1][i];
                        break;
                    case 9:
                        r = ch[1][i];
                        l = r + ch[0][i];
                        break;
                    case 10: {
                        int mid = (ch[0][i] << 1) | (ch[1][i] & 1);
                        l = (mid + ch[1][i]) >> 1;
                        r = (mid - ch[1][i]) >> 1;
                        break;
                    }
                    default:
                        l = ch[0][i];
                        r = ch[1][i];
                }
                out[2 * i] = (short) l;
                out[2 * i + 1] = (short) r;
            }
            return out;
        }

        private int[] subframe(int n, int bps) {
            assertEquals(0, read(1));
            int type = (int) read(6);
            assertEquals("no wasted bits", 0, read(1));
            int[] s = new int[n];
            if (type == 0) {
                int v = (int) readSigned(bps);
                java.util.Arrays.fill(s, v);
            } else if (type == 1) {
                for (int i = 0; i < n; i++) {
                    s[i] = (int) readSigned(bps);
                }
            } else {
                assertTrue("fixed predictor", type >= 8 && type <= 12);
                int order = type - 8;
                for (int i = 0; i < order; i++) {
                    s[i] = (int) readSigned(bps);
                }
                assertEquals("Rice coding with 4 bit parameters", 0, read(2));
                int po = (int) read(4);
                int parts = 1 << po;
                int size = n >> po;
                for (int p = 0; p < parts; p++) {
                    int k = (int) read(4);
                    int from = p == 0 ? order : p * size;
                    int to = (p + 1) * size;
                    for (int i = from; i < to; i++) {
                        int q = unary();
                        long u = ((long) q << k) | (k == 0 ? 0 : read(k));
                        int r = (int) ((u >>> 1) ^ -(u & 1));
                        long pred;
                        switch (order) {
                            case 0:
                                pred = 0;
                                break;
                            case 1:
                                pred = s[i - 1];
                                break;
                            case 2:
                                pred = 2L * s[i - 1] - s[i - 2];
                                break;
                            case 3:
                                pred = 3L * s[i - 1] - 3L * s[i - 2] + s[i - 3];
                                break;
                            default:
                                pred = 4L * s[i - 1] - 6L * s[i - 2] + 4L * s[i - 3] - s[i - 4];
                        }
                        s[i] = (int) (pred + r);
                    }
                }
            }
            return s;
        }
    }

    /** The checksums of the test decoder, written out again here (an independent copy of the specification's polynomials). */
    static final class FlacEncoderTestAccess {
        static int crc8(byte[] d, int len, int start) {
            int c = 0;
            for (int i = 0; i < len; i++) {
                c ^= d[start + i] & 0xFF;
                for (int b = 0; b < 8; b++) {
                    c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
                }
            }
            return c;
        }

        static int crc16(byte[] d, int len, int start) {
            int c = 0;
            for (int i = 0; i < len; i++) {
                c ^= (d[start + i] & 0xFF) << 8;
                for (int b = 0; b < 8; b++) {
                    c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF;
                }
            }
            return c;
        }
    }

    private static byte[] pcm(short[] interleaved) {
        byte[] b = new byte[interleaved.length * 2];
        for (int i = 0; i < interleaved.length; i++) {
            b[2 * i] = (byte) interleaved[i];
            b[2 * i + 1] = (byte) (interleaved[i] >> 8);
        }
        return b;
    }

    private static void roundTrip(short[] samples, int frames) {
        FlacEncoder encoder = new FlacEncoder(48000);
        byte[] pcm = pcm(samples);
        byte[] flac = encoder.encode(pcm, 0, pcm.length);
        Decoder d = new Decoder(flac);
        assertArrayEquals(samples, d.frame(0));
        assertEquals("the frame ends at a byte boundary with nothing after it", flac.length, d.pos);
    }

    @Test
    public void streamHeaderIsMarkerAndStreamInfo() {
        byte[] h = FlacEncoder.streamHeader(44100, 882);
        assertEquals(42, h.length);
        assertEquals('f', h[0]);
        assertEquals('L', h[1]);
        assertEquals('a', h[2]);
        assertEquals('C', h[3]);
        assertEquals((byte) 0x80, h[4]); // last block, STREAMINFO
        assertEquals(34, ((h[5] & 0xFF) << 16) | ((h[6] & 0xFF) << 8) | (h[7] & 0xFF));
        assertEquals(882, ((h[8] & 0xFF) << 8) | (h[9] & 0xFF));
        assertEquals(882, ((h[10] & 0xFF) << 8) | (h[11] & 0xFF));
        long packed = 0;
        for (int i = 18; i < 26; i++) {
            packed = (packed << 8) | (h[i] & 0xFF);
        }
        assertEquals(44100, packed >>> 44); // 20 bits of the sample rate
        assertEquals(1, (packed >>> 41) & 7); // channels - 1
        assertEquals(15, (packed >>> 36) & 31); // bits per sample - 1
    }

    @Test
    public void silenceIsConstantAndSmall() {
        short[] s = new short[960 * 2];
        FlacEncoder e = new FlacEncoder(48000);
        byte[] flac = e.encode(pcm(s), 0, s.length * 2);
        assertTrue("silence takes a few bytes: " + flac.length, flac.length < 20);
        roundTrip(s, 960);
    }

    @Test
    public void sineRoundTripsAndShrinks() {
        short[] s = new short[960 * 2];
        for (int i = 0; i < 960; i++) {
            s[2 * i] = (short) (12000 * Math.sin(2 * Math.PI * 440 * i / 48000.0));
            s[2 * i + 1] = (short) (9000 * Math.sin(2 * Math.PI * 660 * i / 48000.0));
        }
        roundTrip(s, 960);
        FlacEncoder e = new FlacEncoder(48000);
        byte[] flac = e.encode(pcm(s), 0, s.length * 2);
        assertTrue("a sine takes well under half of the PCM: " + flac.length, flac.length < 960 * 4 / 2);
    }

    @Test
    public void noiseRoundTripsAtFullScale() {
        Random r = new Random(7);
        short[] s = new short[960 * 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) r.nextInt(65536);
        }
        roundTrip(s, 960);
    }

    @Test
    public void extremesRoundTrip() {
        short[] s = new short[882 * 2];
        for (int i = 0; i < 882; i++) {
            s[2 * i] = (i & 1) == 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
            s[2 * i + 1] = (i & 1) == 0 ? Short.MIN_VALUE : Short.MAX_VALUE;
        }
        roundTrip(s, 882);
    }

    @Test
    public void identicalChannelsUseMidSide() {
        short[] s = new short[960 * 2];
        Random r = new Random(3);
        for (int i = 0; i < 960; i++) {
            short v = (short) (r.nextInt(8000) - 4000);
            s[2 * i] = v;
            s[2 * i + 1] = v;
        }
        roundTrip(s, 960);
        FlacEncoder e = new FlacEncoder(48000);
        byte[] flac = e.encode(pcm(s), 0, s.length * 2);
        assertTrue("a mono signal costs about one channel: " + flac.length, flac.length < 960 * 4 / 2); // white noise of 13 bits: one channel is about 1560 bytes, two would be about 3100
    }

    @Test
    public void blockSizesOfBothRates() {
        for (int n : new int[]{960, 882, 441, 1920, 100, 16, 4, 1}) {
            Random r = new Random(n);
            short[] s = new short[n * 2];
            int v = 0;
            for (int i = 0; i < s.length; i++) {
                v += r.nextInt(2001) - 1000;
                v = Math.max(-30000, Math.min(30000, v));
                s[i] = (short) v;
            }
            roundTrip(s, n);
        }
    }

    @Test
    public void frameNumbersCountOnAndUseTheUtf8Form() {
        FlacEncoder e = new FlacEncoder(48000);
        short[] s = new short[960 * 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) (i * 7);
        }
        byte[] pcm = pcm(s);
        for (long number = 0; number < 300; number++) {
            byte[] flac = e.encode(pcm, 0, pcm.length);
            assertArrayEquals(s, new Decoder(flac).frame(number));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAHalfFrame() {
        new FlacEncoder(48000).encode(new byte[6], 0, 6);
    }
}
