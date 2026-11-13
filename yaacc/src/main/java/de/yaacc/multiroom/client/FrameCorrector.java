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

    /** Frames of the window over which one dropped or repeated frame is spread. */
    public static final int SMOOTH_WINDOW = 32;

    /**
     * As {@link #apply} for 16 bit little endian interleaved samples, but each dropped or repeated frame is spread over
     * a window of {@value #SMOOTH_WINDOW} frames by linear interpolation (32 frames are played as 31 or 33, the first and
     * the last frame of the window are untouched). The step between two neighbouring samples then stays at the slope
     * of the signal, where cutting one sample doubles it (a click in a pure tone). Used for at most two corrections in a
     * block that is long enough; anything else is done as {@link #apply} does it.
     *
     * <p>Limits: a tail block shorter than {@code 34 n} frames (the rest of a chunk) falls back to cutting a sample; the
     * interpolation lowers a very high frequency a little (about 2 dB at 10 kHz at 48 kHz over the 0.7 ms of a window), at
     * most 120 corrections a second while a drift is being caught up.
     *
     * @param channels samples per frame
     */
    public static byte[] applySmooth(byte[] data, int offset, int frames, int channels, int correction) {
        int frameBytes = channels * 2;
        int n = Math.abs(correction);
        if (channels < 1 || n == 0 || n > 2 || frames < n * (SMOOTH_WINDOW + 2)) {
            return apply(data, offset, frames, frameBytes, correction);
        }
        if (frames < 0 || offset < 0 || (long) offset + (long) frames * frameBytes > data.length) {
            throw new IllegalArgumentException("block outside the data");
        }
        boolean drop = correction > 0;
        int w = SMOOTH_WINDOW;
        int perWindow = drop ? w - 1 : w + 1;
        byte[] out = new byte[(frames - (drop ? n : -n)) * frameBytes];
        int read = 0;
        int written = 0;
        for (int k = 0; k < n; k++) {
            int middle = (int) (((2L * k + 1) * frames) / (2L * n));
            int start = Math.max(read, Math.min(middle - w / 2, frames - w));
            int run = start - read;
            System.arraycopy(data, offset + read * frameBytes, out, written * frameBytes, run * frameBytes);
            written += run;
            read = start;
            for (int j = 0; j < perWindow; j++) {
                double x = drop ? j * (w - 1.0) / (w - 2.0) : j * (w - 1.0) / w;
                int i0 = (int) Math.floor(x);
                int i1 = Math.min(i0 + 1, w - 1);
                double f = x - i0;
                for (int c = 0; c < channels; c++) {
                    int a = sample(data, offset + ((read + i0) * channels + c) * 2);
                    int b = sample(data, offset + ((read + i1) * channels + c) * 2);
                    int v = (int) Math.round(a + (b - a) * f);
                    int at = (written + j) * frameBytes + c * 2;
                    out[at] = (byte) v;
                    out[at + 1] = (byte) (v >> 8);
                }
            }
            written += perWindow;
            read += w;
        }
        System.arraycopy(data, offset + read * frameBytes, out, written * frameBytes, (frames - read) * frameBytes);
        return out;
    }

    private static int sample(byte[] data, int at) {
        return (short) ((data[at] & 0xff) | (data[at + 1] << 8));
    }
}
