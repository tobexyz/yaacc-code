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

/**
 * A streaming sample rate converter for 16 bit little endian stereo, by linear interpolation. It is the safety net of
 * {@link PcmTapAudioProcessor}: the Media3 {@code SonicAudioProcessor} in front of the tap normally converts the rate,
 * but on a device it was seen not to (the tap got 44100 Hz while the stream is 48000 Hz and the play failed), so the tap
 * converts by itself whatever rate it is given. Input of any size can be fed in pieces: the result does not depend on how
 * the input is cut.
 */
final class PcmResampler {

    private static final int FRAME_BYTES = PcmFormatConverter.OUTPUT_FRAME_BYTES;

    private final long inputRate;
    private final long outputRate;
    /**
     * Position of the next output frame in 1/outputRate input frames, counted from the first frame of the next input
     * (exact, so that the result does not depend on how the input is cut); -outputRate to -1 is the frame before it.
     */
    private long next;
    private int prevLeft;
    private int prevRight;
    private byte[] out = new byte[0];

    PcmResampler(int inputRate, int outputRate) {
        if (inputRate <= 0 || outputRate <= 0) {
            throw new IllegalArgumentException("sample rate");
        }
        this.inputRate = inputRate;
        this.outputRate = outputRate;
    }

    /** Forget the position and the last frame (after a seek or a flush). */
    void reset() {
        next = 0;
        prevLeft = 0;
        prevRight = 0;
    }

    /**
     * @param in     whole frames of 16 bit stereo
     * @param length the number of bytes of {@code in}, a multiple of 4
     * @return a buffer that holds the converted frames; its size is in {@link #lastLength()}; it is reused by the next call
     */
    byte[] convert(byte[] in, int length) {
        int frames = length / FRAME_BYTES;
        int capacity = (int) ((((long) frames + 1) * outputRate) / inputRate + 2) * FRAME_BYTES;
        if (out.length < capacity) {
            out = new byte[capacity];
        }
        int written = 0;
        while (true) {
            int i = (int) Math.floorDiv(next, outputRate);
            if (i + 1 > frames - 1) {
                break; // needs the frame after the end: wait for the next input
            }
            double frac = (double) Math.floorMod(next, outputRate) / outputRate;
            int aLeft;
            int aRight;
            if (i < 0) {
                aLeft = prevLeft;
                aRight = prevRight;
            } else {
                aLeft = sample(in, i * FRAME_BYTES);
                aRight = sample(in, i * FRAME_BYTES + 2);
            }
            int bLeft = sample(in, (i + 1) * FRAME_BYTES);
            int bRight = sample(in, (i + 1) * FRAME_BYTES + 2);
            put(out, written, (int) Math.round(aLeft + (bLeft - aLeft) * frac));
            put(out, written + 2, (int) Math.round(aRight + (bRight - aRight) * frac));
            written += FRAME_BYTES;
            next += inputRate;
        }
        if (frames > 0) {
            prevLeft = sample(in, (frames - 1) * FRAME_BYTES);
            prevRight = sample(in, (frames - 1) * FRAME_BYTES + 2);
            next -= (long) frames * outputRate;
        }
        lastLength = written;
        return out;
    }

    private int lastLength;

    /** @return the number of valid bytes the last {@link #convert} produced */
    int lastLength() {
        return lastLength;
    }

    private static int sample(byte[] b, int p) {
        return (short) ((b[p] & 0xff) | (b[p + 1] << 8));
    }

    private static void put(byte[] b, int p, int value) {
        int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
    }
}
