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

import org.junit.Test;

public class FrameCorrectorTest {

    /** Frames of 4 bytes whose first byte is the frame number. */
    private static byte[] frames(int count) {
        byte[] data = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            data[i * 4] = (byte) i;
            data[i * 4 + 1] = (byte) (100 + i);
        }
        return data;
    }

    private static int[] numbers(byte[] data) {
        int[] n = new int[data.length / 4];
        for (int i = 0; i < n.length; i++) {
            n[i] = data[i * 4];
        }
        return n;
    }

    @Test
    public void noCorrectionIsACopy() {
        byte[] data = frames(10);
        byte[] out = FrameCorrector.apply(data, 0, 10, 4, 0);
        assertArrayEquals(data, out);
        out[0] = 99;
        assertEquals("a copy, not the input", 0, data[0]);
    }

    @Test
    public void droppingRemovesExactlyThatManyWholeFramesAndKeepsTheOthersUnchanged() {
        int[] out = numbers(FrameCorrector.apply(frames(20), 0, 20, 4, 3));
        assertEquals(17, out.length);
        int previous = -1;
        int skipped = 0;
        for (int n : out) {
            skipped += n - previous - 1;
            assertEquals("order kept, nothing repeated", true, n > previous);
            previous = n;
        }
        assertEquals(3, skipped - (19 - previous)); // frames skipped inside the block
    }

    @Test
    public void repeatingAddsExactlyThatManyCopiesOfTheFrameBefore() {
        int[] out = numbers(FrameCorrector.apply(frames(20), 0, 20, 4, -2));
        assertEquals(22, out.length);
        int repeats = 0;
        for (int i = 1; i < out.length; i++) {
            int step = out[i] - out[i - 1];
            if (step == 0) {
                repeats++;
            } else {
                assertEquals("everything else moves by one frame", 1, step);
            }
        }
        assertEquals(2, repeats);
    }

    @Test
    public void theCorrectionsAreSpreadOverTheBlock() {
        int[] out = numbers(FrameCorrector.apply(frames(100), 0, 100, 4, 4));
        int firstGap = -1;
        for (int i = 1; i < out.length; i++) {
            if (out[i] - out[i - 1] == 2) {
                firstGap = i;
                break;
            }
        }
        assertEquals("not all at the start", true, firstGap > 5);
    }

    @Test
    public void aBlockWithAnOffsetIsReadFromThatOffset() {
        byte[] data = frames(10);
        int[] out = numbers(FrameCorrector.apply(data, 8, 4, 4, 0));
        assertEquals(2, out[0]);
        assertEquals(5, out[3]);
    }

    @Test
    public void droppingIsLimitedToHalfTheBlockAndNeverGoesOutside() {
        assertEquals(2, FrameCorrector.apply(frames(4), 0, 4, 4, 100).length / 4);
        assertEquals(1, FrameCorrector.apply(frames(1), 0, 1, 4, 5).length / 4);
        assertEquals(0, FrameCorrector.apply(frames(0), 0, 0, 4, 5).length);
        assertEquals(8, FrameCorrector.apply(frames(4), 0, 4, 4, -100).length / 4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aBlockBeyondTheDataIsRefused() {
        FrameCorrector.apply(frames(4), 0, 5, 4, 0);
    }

    @Test
    public void stereoFramesStayTogether() {
        byte[] data = new byte[40]; // 10 frames of 4 bytes: left = n, right = -n
        for (int i = 0; i < 10; i++) {
            data[i * 4] = (byte) i;
            data[i * 4 + 2] = (byte) -i;
        }
        byte[] out = FrameCorrector.apply(data, 0, 10, 4, -3);
        for (int i = 0; i < out.length / 4; i++) {
            assertEquals(-out[i * 4], out[i * 4 + 2]);
        }
    }
}
