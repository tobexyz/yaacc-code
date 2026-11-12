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

/**
 * Removes or repeats single frames of a block of interleaved PCM, spread evenly over the block, to move the playback
 * a few frames earlier or later without changing the pitch (no resampling: every other frame is copied unchanged). A
 * dropped frame is skipped, a repeated frame is the frame before the insertion point written twice. Pure.
 */
public final class FrameCorrector {

    private FrameCorrector() {
    }

    /**
     * @param frames     frames in the block, starting at {@code offset}
     * @param correction positive: drop that many frames; negative: repeat that many; 0: copy. Dropping is limited to
     *                   half the block, repeating to the length of the block.
     * @return a new array of {@code frames - dropped + repeated} frames
     */
    public static byte[] apply(byte[] data, int offset, int frames, int frameBytes, int correction) {
        if (frames < 0 || offset < 0 || frameBytes < 1 || (long) offset + (long) frames * frameBytes > data.length) {
            throw new IllegalArgumentException("block outside the data");
        }
        int drop = correction > 0 ? Math.min(correction, frames / 2) : 0;
        int repeat = correction < 0 ? Math.min(-correction, frames) : 0;
        if (drop == 0 && repeat == 0) {
            byte[] copy = new byte[frames * frameBytes];
            System.arraycopy(data, offset, copy, 0, copy.length);
            return copy;
        }
        int n = drop + repeat;
        byte[] out = new byte[(frames - drop + repeat) * frameBytes];
        int written = 0;
        int index = 0; // frames of the source handled so far
        for (int k = 0; k < n; k++) {
            // the k-th correction happens at the middle of the k-th of n equal parts of the block
            int target = (int) (((2L * k + 1) * frames) / (2L * n));
            int run = target - index;
            System.arraycopy(data, offset + index * frameBytes, out, written * frameBytes, run * frameBytes);
            written += run;
            index = target;
            if (drop > 0) {
                index++; // skip one frame
            } else {
                // repeat the frame before the insertion point (or the first frame at the very start)
                int from = Math.max(0, index - 1);
                System.arraycopy(data, offset + from * frameBytes, out, written * frameBytes, frameBytes);
                written++;
            }
        }
        int rest = frames - index;
        System.arraycopy(data, offset + index * frameBytes, out, written * frameBytes, rest * frameBytes);
        return out;
    }
}
