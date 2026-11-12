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
 * The audio output as the engine sees it (an {@code AudioTrack} in streaming mode on Android, a simulation in tests).
 * It is used from the engine's thread only. Frames are 16 bit interleaved PCM in the byte order of the platform.
 */
public interface AudioSink {

    /** Creates the output. Not started yet. */
    void open(SinkFormat format) throws AudioSinkException;

    /**
     * Queues whole frames without blocking.
     *
     * @return the number of bytes taken, a multiple of the frame size; 0 when the output is full
     */
    int write(byte[] data, int offset, int length) throws AudioSinkException;

    /** Starts playback of what was written; does nothing when it already runs. */
    void start() throws AudioSinkException;

    /**
     * Throws away everything queued and stops. The counting of {@link PlaybackPosition#getFrames()} starts again at 0 and
     * {@link #start()} is needed again. The output itself is kept (it is not re-created).
     */
    void flush();

    /** The latest position the platform knows, or null when there is none (yet). */
    PlaybackPosition position();

    /** How often the output ran empty since it was opened. */
    int underrunCount();

    /** A linear gain, 0 to 1. */
    void setGain(float gain);

    /**
     * How many frames the engine must have written before the output really starts playing (the streaming start threshold
     * of the platform); 0 when the output starts at once.
     */
    default int startThresholdFrames() {
        return 0;
    }

    /** Lets the output note its own events (position source changes) in the engine's trace. */
    default void attachTrace(EventTrace trace) {
    }

    /** One line about the output (buffer, threshold, timestamp health) for the status and the trace. */
    default String describe() {
        return "";
    }

    /** A label of where {@link #position()} comes from, for the status line. */
    String positionSource();

    /** Frees the output. Safe to call twice. */
    void release();
}
