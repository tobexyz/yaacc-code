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

import java.util.ArrayList;
import java.util.List;

/**
 * An audio output that is simulated on a manual clock: it takes frames into a buffer of limited size and plays them at
 * the speed of its own (slightly wrong) clock after a fixed output latency. Positions are reported like the platform does:
 * sparsely, and only after the first frame has been presented. It keeps everything that was written so tests can look
 * at the audio: the tests write a marker into every frame (see {@link ClientFixtures}) and read it back.
 */
final class SimulatedSink implements AudioSink {

    /** One call of write(). */
    static final class Write {
        final long sinkFrameStart;
        final byte[] data;
        final int generation;

        Write(long sinkFrameStart, byte[] data, int generation) {
            this.sinkFrameStart = sinkFrameStart;
            this.data = data;
            this.generation = generation;
        }
    }

    private final de.yaacc.multiroom.protocol.MicroClock clock;
    final int capacityFrames;
    final long outputLatencyMicros;
    final double speed; // 1.0003: the output plays 300 ppm fast
    final long positionPeriodMicros;

    SinkFormat format;
    int opens;
    int releases;
    int flushes;
    int starts;
    boolean open;
    boolean started;
    float gain = 1f;
    int underruns;
    boolean failWrites;
    boolean refuseFormat;
    boolean noPositions;

    private long written;
    private double played;
    private long lastUpdate;
    private long startedAt;
    private boolean underrunActive;
    private PlaybackPosition reported;
    private long reportedAt = Long.MIN_VALUE / 2;
    private int generation;
    final List<Write> writes = new ArrayList<>();
    /** False for the long runs: the audio is analysed on the way in and not kept (hours of audio would not fit). */
    boolean keepWrites = true;
    /** Frames counted before the first write, to run across the 2^31 and 2^32 frame marks quickly. */
    long initialFrames;

    // ---- online analysis of what was written (markers as in ClientFixtures)
    long audibleFrames;
    /** The longest run of silent frames after audio had started (a hole), in the current generation. */
    long longestHole;
    /** Neighbouring audible frames whose media numbers differ by something other than 0, 1 or 2. */
    long badSteps;
    private boolean audibleSeen;
    private long silentRun;
    private long lastMedia = -1;
    private long lastAudibleSinkFrame = -2;

    SimulatedSink(de.yaacc.multiroom.protocol.MicroClock clock, int capacityFrames, long outputLatencyMicros, double speed, long positionPeriodMicros) {
        this.clock = clock;
        this.capacityFrames = capacityFrames;
        this.outputLatencyMicros = outputLatencyMicros;
        this.speed = speed;
        this.positionPeriodMicros = positionPeriodMicros;
    }

    static SimulatedSink typical(de.yaacc.multiroom.protocol.MicroClock clock) {
        return new SimulatedSink(clock, 24000, 40_000, 1.0, 100_000);
    }

    @Override
    public void open(SinkFormat f) throws AudioSinkException {
        if (refuseFormat) {
            throw new AudioSinkException(AudioSinkException.Reason.FORMAT_REFUSED, "no");
        }
        format = f;
        open = true;
        opens++;
        written = initialFrames;
        played = initialFrames;
        started = false;
        generation++;
    }

    private void advance(long now) {
        if (started) {
            long from = Math.max(lastUpdate, startedAt + outputLatencyMicros);
            if (now > from) {
                double potential = (now - from) * format.getSampleRate() * speed / 1e6;
                double available = written - played;
                if (potential <= available) {
                    played += potential;
                    underrunActive = false;
                } else {
                    played = written;
                    if (!underrunActive) {
                        underruns++;
                        underrunActive = true;
                    }
                }
            }
        }
        lastUpdate = now;
    }

    @Override
    public int write(byte[] data, int offset, int length) throws AudioSinkException {
        if (failWrites) {
            throw new AudioSinkException(AudioSinkException.Reason.WRITE_FAILED, "dead");
        }
        long now = clock.nowMicros();
        advance(now);
        long queued = written - (long) Math.floor(played);
        long room = capacityFrames - queued;
        int frames = (int) Math.max(0, Math.min(length / format.getFrameBytes(), room));
        if (frames > 0) {
            analyse(data, offset, frames, written);
            if (keepWrites) {
                byte[] copy = new byte[frames * format.getFrameBytes()];
                System.arraycopy(data, offset, copy, 0, copy.length);
                writes.add(new Write(written, copy, generation));
            }
            written += frames;
            underrunActive = false;
        }
        return frames * format.getFrameBytes();
    }

    private void analyse(byte[] data, int offset, int frames, long firstSinkFrame) {
        int fb = format.getFrameBytes();
        boolean stereo = format.getChannels() == 2;
        for (int i = 0; i < frames; i++) {
            int at = offset + i * fb;
            int left = (short) ((data[at] & 0xff) | (data[at + 1] << 8));
            if (left == 0) {
                if (audibleSeen) {
                    silentRun++;
                    longestHole = Math.max(longestHole, silentRun);
                }
                continue;
            }
            int high = stereo ? (short) ((data[at + 2] & 0xff) | (data[at + 3] << 8)) : 1;
            long media = (long) (high - 1) * 20000L + (left - 1);
            long sinkFrame = firstSinkFrame + i;
            if (audibleSeen && sinkFrame == lastAudibleSinkFrame + 1) {
                long step = media - lastMedia;
                if (step < 0 || step > 2) {
                    badSteps++;
                }
            }
            audibleSeen = true;
            silentRun = 0;
            lastMedia = media;
            lastAudibleSinkFrame = sinkFrame;
            audibleFrames++;
        }
    }

    @Override
    public void start() {
        if (!started) {
            started = true;
            starts++;
            startedAt = clock.nowMicros();
            lastUpdate = startedAt;
        }
    }

    @Override
    public void flush() {
        flushes++;
        advance(clock.nowMicros());
        written = initialFrames;
        played = initialFrames;
        started = false;
        underrunActive = false;
        reported = null;
        generation++;
        audibleSeen = false;
        silentRun = 0;
        lastMedia = -1;
        lastAudibleSinkFrame = -2;
    }

    @Override
    public PlaybackPosition position() {
        long now = clock.nowMicros();
        if (noPositions || !started || now < startedAt + outputLatencyMicros) {
            return null;
        }
        if (reported == null || now - reportedAt >= positionPeriodMicros) {
            advance(now);
            reported = new PlaybackPosition((long) Math.floor(played), now);
            reportedAt = now;
        }
        return reported;
    }

    @Override
    public int underrunCount() {
        advance(clock.nowMicros());
        return underruns;
    }

    @Override
    public void setGain(float g) {
        gain = g;
    }

    @Override
    public String positionSource() {
        return "simulated";
    }

    @Override
    public void release() {
        if (open) {
            releases++;
        }
        open = false;
        started = false;
    }

    /** Counts {@code delta} frames more as if the output had played that long already (the audio is not touched). */
    void shiftFrameCounts(long delta) {
        advance(clock.nowMicros());
        written += delta;
        played += delta;
        lastAudibleSinkFrame += delta;
        reported = null;
    }

    void forceUnderrun() {
        underruns++;
    }

    /** The time at which the frame with this index (of the current output generation) is presented, no underruns assumed. */
    long presentationTimeOf(long sinkFrame) {
        return startedAt + outputLatencyMicros + Math.round(sinkFrame * 1e6 / (format.getSampleRate() * speed));
    }

    long framesWritten() {
        return written;
    }

    int generation() {
        return generation;
    }
}
