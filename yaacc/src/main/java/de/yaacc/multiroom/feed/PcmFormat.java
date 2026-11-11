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
 * The sample format of a PCM stream: rate, bits per sample and channels, like the {@code sampleformat} of a Snapcast
 * stream ({@code 48000:16:2}). Raw little-endian PCM, no header.
 */
public final class PcmFormat {

    /** The format YAACC feeds a Snapcast server with: 48000 Hz, 16 bit, stereo (192,000 bytes per second). */
    public static final PcmFormat SNAPCAST_DEFAULT = new PcmFormat(48000, 16, 2);

    private final int sampleRate;
    private final int bitsPerSample;
    private final int channels;

    /**
     * @param sampleRate    1 to 384,000 Hz
     * @param bitsPerSample 8, 16, 24 or 32
     * @param channels      1 to 8
     * @throws IllegalArgumentException for a value outside these ranges
     */
    public PcmFormat(int sampleRate, int bitsPerSample, int channels) {
        if (sampleRate < 1 || sampleRate > 384_000) {
            throw new IllegalArgumentException("sample rate out of range");
        }
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
            throw new IllegalArgumentException("unsupported bits per sample");
        }
        if (channels < 1 || channels > 8) {
            throw new IllegalArgumentException("channels out of range");
        }
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.channels = channels;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    public int getChannels() {
        return channels;
    }

    /** Bytes of one frame (one sample of every channel). */
    public int bytesPerFrame() {
        return channels * (bitsPerSample / 8);
    }

    /** Bytes of one second of audio. */
    public int bytesPerSecond() {
        return sampleRate * bytesPerFrame();
    }

    /** The text of the Snapcast {@code sampleformat} parameter, for example {@code 48000:16:2}. */
    public String toSampleFormat() {
        return sampleRate + ":" + bitsPerSample + ":" + channels;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PcmFormat)) {
            return false;
        }
        PcmFormat that = (PcmFormat) other;
        return sampleRate == that.sampleRate && bitsPerSample == that.bitsPerSample && channels == that.channels;
    }

    @Override
    public int hashCode() {
        return (sampleRate * 31 + bitsPerSample) * 31 + channels;
    }

    @Override
    public String toString() {
        return "PcmFormat{" + toSampleFormat() + "}";
    }
}
