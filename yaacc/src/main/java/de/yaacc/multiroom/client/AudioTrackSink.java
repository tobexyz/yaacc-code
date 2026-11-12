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

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import de.yaacc.multiroom.protocol.MicroClock;

/**
 * The audio output on Android: a {@link StreamingSink} (all the logic, pure and tested) on an {@link AudioTrack} in
 * streaming mode, 16 bit PCM, mono or stereo, written without blocking. This class is only the thin platform adapter.
 *
 * <p><b>Positions.</b> {@code AudioTrack.getTimestamp()} gives a frame position (the low 32 bits, wrapping) and the time it
 * is presented, on {@code CLOCK_MONOTONIC} ({@code System.nanoTime()}); {@link StreamingSink} converts it to the engine's
 * clock by the age of the report.
 *
 * <p><b>Start threshold.</b> A streaming track does not play after {@code play()} until the data path is filled to a
 * minimum level (the whole buffer by default; Android 12 can lower it, {@code setStartThresholdInFrames}). The adapter
 * lowers it where it can and tells the engine the level ({@link #startThresholdFrames()}), which writes that much silence
 * before it expects a position.
 */
public final class AudioTrackSink extends StreamingSink {

    private static final int BUFFER_MS = 400;

    /** @param clock the clock of the engine (the one the connection's time sync uses) */
    public AudioTrackSink(MicroClock clock) {
        super(clock, AndroidTrack::open);
    }

    /** The {@link TrackPort} on an {@link AudioTrack}. */
    private static final class AndroidTrack implements TrackPort {
        private final AudioTimestamp timestamp = new AudioTimestamp();
        private final AudioTrack track;

        private AndroidTrack(AudioTrack track) {
            this.track = track;
        }

        static TrackPort open(SinkFormat wanted) throws AudioSinkException {
            int mask = wanted.getChannels() == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBytes = AudioTrack.getMinBufferSize(wanted.getSampleRate(), mask, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) {
                throw new AudioSinkException(AudioSinkException.Reason.FORMAT_REFUSED, "the device refuses this format");
            }
            int wantedBytes = (int) ((long) wanted.getSampleRate() * wanted.getFrameBytes() * BUFFER_MS / 1000L);
            AudioTrack created;
            try {
                created = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(wanted.getSampleRate()).setChannelMask(mask).build())
                        .setBufferSizeInBytes(Math.max(minBytes, wantedBytes))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                throw new AudioSinkException(AudioSinkException.Reason.OPEN_FAILED, "the output could not be created");
            }
            if (created.getState() != AudioTrack.STATE_INITIALIZED) {
                created.release();
                throw new AudioSinkException(AudioSinkException.Reason.OPEN_FAILED, "the output did not initialise");
            }
            return new AndroidTrack(created);
        }

        @Override
        public int bufferFrames() {
            return track.getBufferSizeInFrames();
        }

        @Override
        public int startThresholdFrames() {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                return track.getStartThresholdInFrames();
            }
            return track.getBufferSizeInFrames(); // device specific below Android 12: assume the whole buffer
        }

        @Override
        public int requestStartThresholdFrames(int frames) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    return track.setStartThresholdInFrames(Math.max(1, Math.min(frames, track.getBufferCapacityInFrames())));
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    return track.getStartThresholdInFrames();
                }
            }
            return startThresholdFrames();
        }

        @Override
        public int write(byte[] data, int offset, int length) throws AudioSinkException {
            int took = track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING);
            if (took < 0) {
                throw new AudioSinkException(AudioSinkException.Reason.WRITE_FAILED, "write failed (" + took + ")");
            }
            return took;
        }

        @Override
        public void play() throws AudioSinkException {
            try {
                track.play();
            } catch (IllegalStateException e) {
                throw new AudioSinkException(AudioSinkException.Reason.START_FAILED, "the output could not be started");
            }
        }

        @Override
        public void pauseAndFlush() {
            try {
                track.pause(); // flush() only works on a paused or stopped track
                track.flush();
            } catch (IllegalStateException ignored) {
                // a dead track fails the next write, which the engine reports
            }
        }

        @Override
        public boolean timestamp(long[] out) {
            boolean ok;
            try {
                ok = track.getTimestamp(timestamp);
            } catch (IllegalStateException e) {
                return false;
            }
            if (ok) {
                out[0] = timestamp.framePosition;
                out[1] = timestamp.nanoTime;
            }
            return ok;
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long headPosition() {
            return track.getPlaybackHeadPosition() & 0xffffffffL;
        }

        @Override
        public int underrunCount() {
            return track.getUnderrunCount();
        }

        @Override
        public void setGain(float gain) {
            track.setVolume(gain);
        }

        @Override
        public void release() {
            try {
                track.pause();
            } catch (IllegalStateException ignored) {
                // already dead
            }
            track.release();
        }
    }
}
