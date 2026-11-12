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
 * The platform output as {@link StreamingSink} needs it: an {@code AudioTrack} on Android, a simulated device in tests.
 * Every method is a thin call; the decisions (what a position means, when to fall back, how a 32 bit counter is unwrapped)
 * are in {@link StreamingSink}, which is pure and tested.
 */
public interface TrackPort {

    /** The size of the output buffer in frames. */
    int bufferFrames();

    /**
     * The number of frames that must be queued before a started track really plays (the streaming start threshold).
     * Before Android 12 this is the whole buffer or a device specific level the app can not see, so the buffer is assumed.
     */
    int startThresholdFrames();

    /** Asks for a smaller start threshold where the platform allows it (Android 12 and later); returns the effective one. */
    int requestStartThresholdFrames(int frames);

    /** Queues frames without blocking; returns the bytes taken. */
    int write(byte[] data, int offset, int length) throws AudioSinkException;

    void play() throws AudioSinkException;

    /** Pauses, then discards everything queued. */
    void pauseAndFlush();

    /**
     * The newest timestamp of the platform: {@code out[0]} is the frame position (the low 32 bits count, wrapping),
     * {@code out[1]} the time it is presented in nanoseconds on the clock of {@link #nanoTime()}.
     *
     * @return false when the platform has none
     */
    boolean timestamp(long[] out);

    /** The monotonic clock (nanoseconds) the platform's timestamps are in. */
    long nanoTime();

    /** The playback head position as an unsigned 32 bit number (wraps). */
    long headPosition();

    int underrunCount();

    void setGain(float gain);

    /** Facts about the output for the status line (performance mode, buffer sizes); empty when there are none. */
    default String describe() {
        return "";
    }

    void release();
}
