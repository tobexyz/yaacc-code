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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameCorrectorSmoothTest {

    private static final double AMPLITUDE = 12000;
    private static final double FREQUENCY = 440;
    private static final int RATE = 48000;
    /** The largest step between two samples of the tone itself. */
    private static final double SLOPE = AMPLITUDE * 2 * Math.PI * FREQUENCY / RATE;

    private static byte[] sine(int frames, int channels, int firstFrame) {
        byte[] data = new byte[frames * channels * 2];
        for (int i = 0; i < frames; i++) {
            int v = (int) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * FREQUENCY * (firstFrame + i) / RATE + 0.3));
            for (int c = 0; c < channels; c++) {
                int at = (i * channels + c) * 2;
                data[at] = (byte) v;
                data[at + 1] = (byte) (v >> 8);
            }
        }
        return data;
    }

    private static int left(byte[] data, int channels, int frame) {
        int at = frame * channels * 2;
        return (short) ((data[at] & 0xff) | (data[at + 1] << 8));
    }

    private static double largestStep(byte[] data, int channels) {
        double max = 0;
        for (int i = 1; i < data.length / (channels * 2); i++) {
            max = Math.max(max, Math.abs(left(data, channels, i) - left(data, channels, i - 1)));
        }
        return max;
    }

    @Test
    public void cuttingOneSampleDoublesTheStepOfATone() {
        byte[] block = sine(960, 2, 0);
        byte[] cut = FrameCorrector.apply(block, 0, 960, 4, 1);
        assertTrue(largestStep(cut, 2) > 1.3 * SLOPE); // the click that the smooth version avoids (a sample of a tone near its peak
        // steps by the slope; cutting one there makes it up to twice)
    }

    @Test
    public void aSmoothDropKeepsEveryStepAtTheSlopeOfTheTone() {
        for (int start = 0; start < 120; start += 7) { // whatever the phase of the tone
            byte[] block = sine(960, 2, start * 13);
            byte[] out = FrameCorrector.applySmooth(block, 0, 960, 2, 1);
            assertEquals(959 * 4, out.length);
            assertTrue("start " + start + ": " + largestStep(out, 2) + " vs " + SLOPE, largestStep(out, 2) <= SLOPE * 1.08);
        }
    }

    @Test
    public void aSmoothRepeatKeepsEveryStepAtTheSlopeOfTheTone() {
        for (int start = 0; start < 120; start += 7) {
            byte[] block = sine(960, 2, start * 13);
            byte[] out = FrameCorrector.applySmooth(block, 0, 960, 2, -1);
            assertEquals(961 * 4, out.length);
            assertTrue("start " + start + ": " + largestStep(out, 2) + " vs " + SLOPE, largestStep(out, 2) <= SLOPE * 1.02);
        }
    }

    @Test
    public void twoCorrectionsInABlockKeepTheStepAndTheLength() {
        byte[] block = sine(960, 2, 5);
        byte[] out = FrameCorrector.applySmooth(block, 0, 960, 2, 2);
        assertEquals(958 * 4, out.length);
        assertTrue(largestStep(out, 2) <= SLOPE * 1.08);
        byte[] more = FrameCorrector.applySmooth(block, 0, 960, 2, -2);
        assertEquals(962 * 4, more.length);
        assertTrue(largestStep(more, 2) <= SLOPE * 1.02);
    }

    @Test
    public void theStartAndTheEndOfTheBlockAreUntouchedAndChannelsStayApart() {
        byte[] block = new byte[960 * 4];
        for (int i = 0; i < 960; i++) { // left rises, right falls
            block[i * 4] = (byte) i;
            block[i * 4 + 1] = (byte) (i >> 8);
            int r = 5000 - i;
            block[i * 4 + 2] = (byte) r;
            block[i * 4 + 3] = (byte) (r >> 8);
        }
        byte[] out = FrameCorrector.applySmooth(block, 0, 960, 2, 1);
        assertArrayEquals(java.util.Arrays.copyOfRange(block, 0, 100 * 4), java.util.Arrays.copyOfRange(out, 0, 100 * 4));
        assertArrayEquals(java.util.Arrays.copyOfRange(block, 800 * 4, 960 * 4), java.util.Arrays.copyOfRange(out, 799 * 4, 959 * 4));
        for (int i = 1; i < 959; i++) {
            assertTrue(left(out, 2, i) >= left(out, 2, i - 1));
            int r0 = (short) ((out[(i - 1) * 4 + 2] & 0xff) | (out[(i - 1) * 4 + 3] << 8));
            int r1 = (short) ((out[i * 4 + 2] & 0xff) | (out[i * 4 + 3] << 8));
            assertTrue(r1 <= r0);
        }
    }

    @Test
    public void whatIsTooShortOrTooMuchIsDoneAsBefore() {
        byte[] small = sine(20, 2, 0);
        assertArrayEquals(FrameCorrector.apply(small, 0, 20, 4, 1), FrameCorrector.applySmooth(small, 0, 20, 2, 1));
        byte[] block = sine(960, 2, 0);
        assertArrayEquals(FrameCorrector.apply(block, 0, 960, 4, 5), FrameCorrector.applySmooth(block, 0, 960, 2, 5));
        assertArrayEquals(block, FrameCorrector.applySmooth(block, 0, 960, 2, 0));
    }

    @Test
    public void monoWorksToo() {
        byte[] block = sine(960, 1, 3);
        byte[] out = FrameCorrector.applySmooth(block, 0, 960, 1, 1);
        assertEquals(959 * 2, out.length);
        assertTrue(largestStep(out, 1) <= SLOPE * 1.08);
    }
}
