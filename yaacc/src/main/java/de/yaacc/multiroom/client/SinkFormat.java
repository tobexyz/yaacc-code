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
 * The format the audio sink is opened with: always 16 bit PCM, stereo or mono. Anything else the stream carries is
 * converted to this before it reaches the sink (see {@link PcmConverter}).
 */
public final class SinkFormat {

    public static final int BITS = 16;

    private final int sampleRate;
    private final int channels;

    public SinkFormat(int sampleRate, int channels) {
        if (sampleRate < 1 || (channels != 1 && channels != 2)) {
            throw new IllegalArgumentException("sample rate or channel count not supported");
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    /** Bytes of one frame (one sample of every channel). */
    public int getFrameBytes() {
        return channels * BITS / 8;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SinkFormat && sampleRate == ((SinkFormat) other).sampleRate
                && channels == ((SinkFormat) other).channels;
    }

    @Override
    public int hashCode() {
        return sampleRate * 31 + channels;
    }

    @Override
    public String toString() {
        return sampleRate + " Hz, " + channels + " ch, " + BITS + " bit";
    }
}
