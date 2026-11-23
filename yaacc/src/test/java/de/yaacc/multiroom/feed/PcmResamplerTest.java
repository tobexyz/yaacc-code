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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** The rate converter of the tap: 44100 to 48000 Hz and back, in pieces of any size. */
public class PcmResamplerTest {

    private static byte[] sine(int frames, int rate, double hz) {
        byte[] data = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            int left = (int) (Math.sin(2 * Math.PI * hz * i / rate) * 10000);
            int right = -left;
            data[i * 4] = (byte) left;
            data[i * 4 + 1] = (byte) (left >> 8);
            data[i * 4 + 2] = (byte) right;
            data[i * 4 + 3] = (byte) (right >> 8);
        }
        return data;
    }

    private static byte[] run(PcmResampler resampler, byte[] input, int pieceFrames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < input.length; offset += pieceFrames * 4) {
            byte[] piece = Arrays.copyOfRange(input, offset, Math.min(input.length, offset + pieceFrames * 4));
            byte[] result = resampler.convert(piece, piece.length);
            out.write(result, 0, resampler.lastLength());
        }
        return out.toByteArray();
    }

    @Test
    public void oneSecondAt44100BecomesOneSecondAt48000() {
        byte[] out = run(new PcmResampler(44100, 48000), sine(44100, 44100, 440), 44100);
        assertTrue("about 48000 frames, was " + out.length / 4, Math.abs(out.length / 4 - 48000) <= 2);
    }

    @Test
    public void theResultDoesNotDependOnHowTheInputIsCut() {
        byte[] input = sine(44100, 44100, 440);
        byte[] whole = run(new PcmResampler(44100, 48000), input, 44100);
        for (int piece : new int[]{1, 7, 441, 1000, 4096}) {
            assertArrayEquals("pieces of " + piece, whole, run(new PcmResampler(44100, 48000), input, piece));
        }
    }

    @Test
    public void aSineKeepsItsFrequencyAndLevel() {
        byte[] out = run(new PcmResampler(44100, 48000), sine(44100, 44100, 440), 4410);
        int crossings = 0;
        int peak = 0;
        int previous = 0;
        for (int i = 0; i < out.length / 4; i++) {
            int left = (short) ((out[i * 4] & 0xff) | (out[i * 4 + 1] << 8));
            int right = (short) ((out[i * 4 + 2] & 0xff) | (out[i * 4 + 3] << 8));
            assertEquals("the channels stay apart", -left, right, 1);
            if (previous < 0 && left >= 0) {
                crossings++;
            }
            previous = left;
            peak = Math.max(peak, Math.abs(left));
        }
        assertTrue("440 Hz for one second: " + crossings, Math.abs(crossings - 440) <= 1);
        assertTrue("level " + peak, peak > 9900 && peak <= 10000);
    }

    @Test
    public void downsamplingAndTheSameRateWork() {
        byte[] input = sine(48000, 48000, 1000);
        assertEquals(44100, run(new PcmResampler(48000, 44100), input, 960).length / 4, 2);
        // the same rate: every frame but the last, which waits for its successor
        assertArrayEquals(Arrays.copyOf(input, input.length - 4),
                Arrays.copyOf(run(new PcmResampler(48000, 48000), input, 960), input.length - 4));
    }

    @Test
    public void aResetStartsOver() {
        PcmResampler resampler = new PcmResampler(44100, 48000);
        byte[] input = sine(4410, 44100, 440);
        byte[] first = run(resampler, input, 4410).clone();
        resampler.reset();
        assertArrayEquals(first, run(resampler, input, 4410));
    }
}
