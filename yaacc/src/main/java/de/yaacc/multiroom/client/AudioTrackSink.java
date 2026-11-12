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
 * The audio output on Android: an {@link AudioTrack} in streaming mode, 16 bit PCM, mono or stereo, written without
 * blocking. Thin glue, not unit tested; what it reports is checked by the engine ({@link PlaybackTracker}).
 *
 * <p><b>Positions.</b> {@code AudioTrack.getTimestamp()} gives a frame position (the low 32 bits, wrapping) and the time it
 * is presented, on {@code CLOCK_MONOTONIC} ({@code System.nanoTime()}). The engine's clock is the boot-time clock
 * ({@code elapsedRealtimeNanos}, which also counts while the device sleeps), so the time is converted by its age:
 * the engine time is "now on the engine clock minus (nanoTime now minus the timestamp's nanoTime)". When the platform
 * gives no timestamp for a while (some routes never do) the head position is used with the current time instead; that is
 * coarse (it moves in steps of a buffer period) and is reported as such ({@link #positionSource()}).
 *
 * <p><b>Guesses that need a device</b> (see {@code docs/tech.md}): that {@code flush()} resets the frame position to 0 (the
 * documentation says so for the head position; the position right after a flush is read and subtracted in case a device
 * does not), that a timestamp taken before the flush is recognised by its time, and that the track runs without an
 * underrun at the buffer size chosen here (400 ms).
 */
public final class AudioTrackSink implements AudioSink {

    private static final int BUFFER_MS = 400;
    /** Failed timestamp polls (a poll is at least {@code MIN_POLL_NANOS} apart) after which the head position is used. */
    private static final int TIMESTAMP_FAILURES_BEFORE_FALLBACK = 40;
    private static final long MIN_POLL_NANOS = 20_000_000L;

    private final MicroClock clock;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    /** Volatile: the watchdog may release the output from another thread while the engine thread still uses it. */
    private volatile AudioTrack track;
    private SinkFormat format;
    private boolean started;
    private int failures;
    private boolean usingHead;
    private long lastPollNanos = Long.MIN_VALUE / 2; // System.nanoTime() has an arbitrary origin: never compare with 0
    private long flushedAtNanos = Long.MIN_VALUE / 2;
    private long wrapBase;
    private long lastLow;
    private long baseline;
    private PlaybackPosition lastPosition;

    /** @param clock the clock of the engine (the one the connection's time sync uses) */
    public AudioTrackSink(MicroClock clock) {
        this.clock = clock;
    }

    @Override
    public void open(SinkFormat wanted) throws AudioSinkException {
        release();
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
        track = created;
        if (created.getState() != AudioTrack.STATE_INITIALIZED) {
            release();
            throw new AudioSinkException(AudioSinkException.Reason.OPEN_FAILED, "the output did not initialise");
        }
        format = wanted;
        resetPositionState();
    }

    private void resetPositionState() {
        started = false;
        failures = 0;
        usingHead = false;
        lastPollNanos = Long.MIN_VALUE / 2;
        wrapBase = 0;
        lastLow = 0;
        lastPosition = null;
    }

    @Override
    public int write(byte[] data, int offset, int length) throws AudioSinkException {
        AudioTrack current = track;
        if (current == null) {
            throw new AudioSinkException(AudioSinkException.Reason.WRITE_FAILED, "no output");
        }
        int took = current.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING);
        if (took < 0) {
            throw new AudioSinkException(AudioSinkException.Reason.WRITE_FAILED, "write failed (" + took + ")");
        }
        return took;
    }

    @Override
    public void start() throws AudioSinkException {
        AudioTrack current = track;
        if (current == null) {
            throw new AudioSinkException(AudioSinkException.Reason.START_FAILED, "no output");
        }
        if (started) {
            return;
        }
        try {
            current.play();
        } catch (IllegalStateException e) {
            throw new AudioSinkException(AudioSinkException.Reason.START_FAILED, "the output could not be started");
        }
        started = true;
    }

    @Override
    public void flush() {
        AudioTrack current = track;
        if (current == null) {
            return;
        }
        try {
            current.pause(); // flush() only works on a paused or stopped track
            current.flush();
        } catch (IllegalStateException ignored) {
            // a dead track fails the next write, which the engine reports
        }
        flushedAtNanos = System.nanoTime();
        resetPositionState();
        baseline = unsignedHead();
    }

    @Override
    public PlaybackPosition position() {
        AudioTrack current = track;
        if (current == null || !started) {
            return null;
        }
        long nowNanos = System.nanoTime();
        if (nowNanos - lastPollNanos < MIN_POLL_NANOS) {
            return lastPosition;
        }
        lastPollNanos = nowNanos;
        PlaybackPosition position = null;
        if (!usingHead) {
            boolean ok;
            try {
                ok = current.getTimestamp(timestamp);
            } catch (IllegalStateException e) {
                ok = false;
            }
            if (ok && timestamp.nanoTime >= flushedAtNanos) {
                failures = 0;
                long low = timestamp.framePosition & 0xffffffffL;
                if (low < lastLow && lastLow - low > 0x80000000L) {
                    wrapBase += 1L << 32;
                }
                lastLow = low;
                long frames = wrapBase + low - baseline;
                long ageMicros = (nowNanos - timestamp.nanoTime) / 1000L;
                position = new PlaybackPosition(Math.max(0, frames), clock.nowMicros() - ageMicros);
            } else if (!ok && ++failures >= TIMESTAMP_FAILURES_BEFORE_FALLBACK) {
                usingHead = true;
            }
        }
        if (usingHead) {
            position = new PlaybackPosition(Math.max(0, unsignedHead(current) - baseline), clock.nowMicros());
        }
        if (position != null) {
            lastPosition = position;
        }
        return lastPosition;
    }

    private long unsignedHead() {
        return unsignedHead(track);
    }

    private static long unsignedHead(AudioTrack current) {
        return current == null ? 0 : current.getPlaybackHeadPosition() & 0xffffffffL;
    }

    @Override
    public int underrunCount() {
        AudioTrack current = track;
        return current == null ? 0 : current.getUnderrunCount();
    }

    @Override
    public void setGain(float gain) {
        AudioTrack current = track;
        if (current != null) {
            current.setVolume(gain);
        }
    }

    @Override
    public String positionSource() {
        return usingHead ? "head position (coarse)" : "timestamp";
    }

    @Override
    public void release() {
        AudioTrack old = track;
        track = null;
        started = false;
        if (old != null) {
            try {
                old.pause();
            } catch (IllegalStateException ignored) {
                // already dead
            }
            old.release();
        }
    }
}
