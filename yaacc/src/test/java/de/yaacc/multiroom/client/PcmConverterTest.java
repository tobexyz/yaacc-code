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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PcmConverterTest {

    private static short[] shorts(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        short[] s = new short[data.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = b.getShort(2 * i);
        }
        return s;
    }

    private static byte[] ints24(int... values) {
        byte[] out = new byte[values.length * 3];
        for (int i = 0; i < values.length; i++) {
            out[3 * i] = (byte) values[i];
            out[3 * i + 1] = (byte) (values[i] >> 8);
            out[3 * i + 2] = (byte) (values[i] >> 16);
        }
        return out;
    }

    private static byte[] ints32(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    private static byte[] floats(float... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            b.putFloat(v);
        }
        return b.array();
    }

    @Test
    public void sixteenBitIsCopied() {
        byte[] in = {1, 2, 3, (byte) 0x80};
        assertEquals(0x0201, shorts(PcmConverter.toPcm16(in, 0, 2, PcmConverter.Encoding.PCM16))[0]);
        assertEquals((short) 0x8003, shorts(PcmConverter.toPcm16(in, 0, 2, PcmConverter.Encoding.PCM16))[1]);
    }

    @Test
    public void twentyFourBitKeepsTheTopSixteenBitsRounded() {
        short[] out = shorts(PcmConverter.toPcm16(ints24(0x123456, 0x000080, 0x00007f, -0x000080, -0x000081, 0x7fffff, -0x800000),
                0, 7, PcmConverter.Encoding.PCM24));
        assertEquals(0x1234 + 1 - 1, out[0] + 0); // 0x123456 -> 0x1234 (0x56 < 0x80)
        assertEquals("a half rounds up", 1, out[1]);
        assertEquals(0, out[2]);
        assertEquals("-0.5 rounds up to 0", 0, out[3]);
        assertEquals(-1, out[4]);
        assertEquals("full scale clips", 32767, out[5]);
        assertEquals(-32768, out[6]);
    }

    @Test
    public void thirtyTwoBitKeepsTheTopSixteenBitsRoundedAndClipped() {
        short[] out = shorts(PcmConverter.toPcm16(ints32(0x12345678, 0x00008000, 0x00007fff, Integer.MAX_VALUE, Integer.MIN_VALUE),
                0, 5, PcmConverter.Encoding.PCM32));
        assertEquals(0x1234, out[0]);
        assertEquals(1, out[1]);
        assertEquals(0, out[2]);
        assertEquals(32767, out[3]);
        assertEquals(-32768, out[4]);
    }

    @Test
    public void floatIsScaledRoundedAndClippedAndNotANumberIsSilence() {
        short[] out = shorts(PcmConverter.toPcm16(floats(0f, 0.5f, -0.5f, 1f, -1f, 2f, 1e30f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY), 0, 10, PcmConverter.Encoding.FLOAT32));
        assertEquals(0, out[0]);
        assertEquals(16384, out[1]);
        assertEquals(-16384, out[2]);
        assertEquals(32767, out[3]);
        assertEquals(-32768, out[4]);
        assertEquals(32767, out[5]);
        assertEquals(32767, out[6]);
        assertEquals(0, out[7]);
        assertEquals(32767, out[8]);
        assertEquals(-32768, out[9]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSourceThatIsTooShortIsRefused() {
        PcmConverter.toPcm16(new byte[5], 0, 2, PcmConverter.Encoding.PCM24);
    }

    @Test
    public void onlySupportedBitDepthsHaveAnEncoding() {
        assertEquals(PcmConverter.Encoding.PCM24, PcmConverter.Encoding.ofBits(24));
        assertEquals(null, PcmConverter.Encoding.ofBits(8));
        assertEquals(null, PcmConverter.Encoding.ofBits(0));
    }
}
