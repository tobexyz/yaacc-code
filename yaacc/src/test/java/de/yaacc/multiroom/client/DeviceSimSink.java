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

import de.yaacc.multiroom.protocol.MicroClock;

import java.util.ArrayDeque;

/**
 * The production {@link StreamingSink} on a simulated device ({@link DeviceModel}) with a manual clock. It records what the
 * output <i>plays</i> (not what was written): the tests write a marker into every frame (see {@link ClientFixtures}) so
 * audio can be told from silence, and the metrics say whether a listener would hear one tone or "peep....peep".
 */
final class DeviceSimSink extends StreamingSink {

    private final Device device;

    /** The client time at which a media frame (the marker in the audio) is meant to be heard; set by the rig. */
    interface Intent {
        long targetMicros(long mediaFrame);
    }

    /** The stream is a sine whose neighbouring samples differ by at most this much. */
    void setSineSlope(double slope) {
        device.sineSlope = slope;
    }

    void setIntent(Intent intent) {
        device.intent = intent;
    }

    DeviceSimSink(MicroClock clock, DeviceModel model) {
        this(clock, new Device(clock, model));
    }

    private DeviceSimSink(MicroClock clock, Device device) {
        super(clock, device);
        this.device = device;
    }

    /** The simulated platform output (all tracks of one sink share the counters). */
    private static final class Device implements StreamingSink.Opener {
        final MicroClock clock;
        final DeviceModel model;
        final Counters counters = new Counters();
        Intent intent;
        double sineSlope;
        Track current;
        long firstPlayMicros = Long.MIN_VALUE / 2;
        long startedMicros = -1;

        Device(MicroClock clock, DeviceModel model) {
            this.clock = clock;
            this.model = model;
        }

        @Override
        public TrackPort open(SinkFormat format) {
            counters.opens++;
            current = new Track(this, format);
            return current;
        }
    }

    /** What happened, for the assertions. */
    static final class Counters {
        int opens;
        int plays;
        int flushes;
        int underruns;
        int realUnderruns;
        int mixerGlitches;
        long playedAudibleFrames;
        long playedSilentFramesAfterAudio;
        long playedSilentFramesBeforeAudio;
        long audibleRuns;
        long longestSilenceAfterAudio;
        long timestampCalls;
        long timestampOk;
        long writtenFrames;
        long refusedWrites;
        long firstPlayedAudioMicros = -1;
        long silenceRun;
        /** Sine stream: the largest step between neighbouring samples written, and steps above 1.1 x the tone's slope. */
        double sineMaxStep;
        long sineStepsOverSlope;
        /** Marker stream: neighbouring audible frames whose media numbers are not consecutive, and the largest gap. */
        long mediaJumps;
        long largestMediaJump;
        /** Largest |played time - intended time| in micros over audible runs that began after the first 8 s (settled). */
        long maxSyncErrorAfterSettleMicros;
        long syncSamples;
        long lastSyncErrorMicros;
        boolean audioSeen;
        boolean lastWasAudible;
    }

    Counters counters() {
        return device.counters;
    }

    /** Frames of the stream that were played as audio, silence after the first audio, and how many separate audible runs. */
    String outcome() {
        Counters c = device.counters;
        return "audible " + c.playedAudibleFrames + " frames in " + c.audibleRuns + " runs, silence after audio "
                + c.playedSilentFramesAfterAudio + " (longest " + c.longestSilenceAfterAudio + "), opens " + c.opens
                + ", plays " + c.plays + ", flushes " + c.flushes + ", underruns " + c.underruns;
    }

    /** The output stops consuming for this long; what is queued stays queued (a route hiccup, not a starvation). */
    void freezeOutput(long micros) {
        device.current.freeze(micros);
    }

    void forceUnderrun() {
        device.counters.underruns++;
    }

    /** One simulated AudioTrack. */
    private static final class Track implements TrackPort {
        private final Device device;
        private final DeviceModel m;
        private final SinkFormat format;
        private final ArrayDeque<long[]> runs = new ArrayDeque<>(); // {frames, audible}
        private long written;
        private double played;
        private boolean playing;
        private boolean filling;
        private boolean starving;
        private long consumeStart = Long.MAX_VALUE;
        private long lastUpdate;
        private long counterBase;
        private long refreshedAt = Long.MIN_VALUE / 2;
        private long reportedFrames;
        private boolean haveReport;
        private int threshold;
        private long refreshes;
        private long reportNoise;
        private final java.util.Random random = new java.util.Random(7);
        private long reportedAt;
        private long consumedFrames;

        Track(Device device, SinkFormat format) {
            this.device = device;
            this.m = device.model;
            this.format = format;
            this.threshold = Math.max(1, Math.min(m.startThresholdFrames, m.bufferFrames));
        }

        /** The calling thread is blocked: the manual clock goes on. */
        private void block(long micros) {
            if (micros > 0 && device.clock instanceof ManualMicroClock) {
                ((ManualMicroClock) device.clock).advanceMicros(micros);
            }
        }

        private long now() {
            return device.clock.nowMicros();
        }

        @Override
        public int bufferFrames() {
            return m.bufferFrames;
        }

        @Override
        public int startThresholdFrames() {
            return m.reportedThresholdFrames > 0 ? m.reportedThresholdFrames : threshold;
        }

        @Override
        public int requestStartThresholdFrames(int frames) {
            if (m.thresholdSettable) {
                threshold = Math.max(1, Math.min(frames, m.bufferFrames));
            }
            return threshold;
        }

        private void begin(long t) {
            filling = false;
            starving = false;
            consumeStart = t + m.outputLatencyMicros;
            lastUpdate = t;
        }

        private long frozenUntil = Long.MIN_VALUE;

        /** The output stops consuming for this long (a route hiccup): data stays queued, the position stands still. */
        void freeze(long micros) {
            advance(now());
            frozenUntil = now() + micros;
        }

        private void advance(long t) {
            if (playing && !filling && t > frozenUntil) {
                long from = Math.max(Math.max(lastUpdate, consumeStart), frozenUntil);
                if (t > from) {
                    double potential = (t - from) * format.getSampleRate() * m.speed / 1e6;
                    double available = written - played;
                    if (potential <= available) {
                        mixer(potential, available, t);
                        consume(potential, from, t);
                        starving = false;
                    } else {
                        consume(available, from, t);
                        if (!starving) {
                            starving = true;
                            device.counters.realUnderruns++;
                            if (m.countUnderruns) {
                                device.counters.underruns++;
                            }
                            if (m.stallAfterUnderrun) {
                                filling = true;
                            }
                        }
                    }
                }
            }
            lastUpdate = t;
        }

        private double sinceBoundary;

        /** The deep-buffer mixer: at every period boundary fewer queued frames than it needs make a real glitch. */
        private void mixer(double frames, double available, long t) {
            if (m.mixerMinQueuedFrames <= 0 || frames <= 0) {
                return;
            }
            sinceBoundary += frames;
            while (sinceBoundary >= m.mixerPeriodFrames) {
                sinceBoundary -= m.mixerPeriodFrames;
                double queued = available - frames + sinceBoundary;
                if (queued < m.mixerMinQueuedFrames) {
                    Counters c = device.counters;
                    c.mixerGlitches++;
                    c.underruns++;
                    long period = (long) (m.mixerPeriodFrames * 1e6 / (format.getSampleRate() * m.speed));
                    frozenUntil = Math.max(frozenUntil, t + period);
                    if (c.audioSeen) {
                        c.playedSilentFramesAfterAudio += m.mixerPeriodFrames;
                        c.silenceRun += m.mixerPeriodFrames;
                        c.longestSilenceAfterAudio = Math.max(c.longestSilenceAfterAudio, c.silenceRun);
                    }
                }
            }
        }

        /** Takes frames out of the queue in order and measures what was played. */
        private void consume(double frames, long from, long t) {
            double target = played + frames;
            long whole = (long) Math.floor(target) - (long) Math.floor(played);
            played = target;
            Counters c = device.counters;
            long done = 0;
            while (whole > 0 && !runs.isEmpty()) {
                long[] run = runs.peekFirst();
                long take = Math.min(whole, run[0]);
                boolean audible = run[1] == 1;
                if (audible) {
                    long frameTime = from + (long) (done * 1e6 / (format.getSampleRate() * m.speed));
                    if (device.startedMicros < 0) {
                        device.startedMicros = frameTime;
                    }
                    if (device.intent != null) {
                        long error = frameTime - device.intent.targetMicros(run[2]);
                        c.lastSyncErrorMicros = error;
                        if (frameTime - device.startedMicros > 8_000_000L) {
                            c.syncSamples++;
                            c.maxSyncErrorAfterSettleMicros = Math.max(c.maxSyncErrorAfterSettleMicros, Math.abs(error));
                        }
                    }
                    run[2] += take;
                    if (!c.lastWasAudible) {
                        c.audibleRuns++;
                        if (c.firstPlayedAudioMicros < 0) {
                            c.firstPlayedAudioMicros = t;
                        }
                    }
                    c.playedAudibleFrames += take;
                    c.audioSeen = true;
                    c.silenceRun = 0;
                    c.lastWasAudible = true;
                } else {
                    if (c.audioSeen) {
                        c.playedSilentFramesAfterAudio += take;
                        c.silenceRun += take;
                        c.longestSilenceAfterAudio = Math.max(c.longestSilenceAfterAudio, c.silenceRun);
                    } else {
                        c.playedSilentFramesBeforeAudio += take;
                    }
                    c.lastWasAudible = false;
                }
                consumedFrames += take;
                done += take;
                run[0] -= take;
                whole -= take;
                if (run[0] == 0) {
                    runs.pollFirst();
                }
            }
        }

        private int prevLeft;
        private long prevMedia = Long.MIN_VALUE;

        /** What the engine wrote: steps of a sine, jumps of the frame markers (only within one run of audio). */
        private void analyse(int left, boolean audible, long media) {
            Counters c = device.counters;
            if (!audible) {
                prevLeft = 0;
                prevMedia = Long.MIN_VALUE;
                return;
            }
            if (device.sineSlope > 0) {
                if (prevLeft != 0) {
                    double step = Math.abs(left - prevLeft);
                    c.sineMaxStep = Math.max(c.sineMaxStep, step);
                    if (step > device.sineSlope * 1.1) {
                        c.sineStepsOverSlope++;
                    }
                }
                prevLeft = left;
            } else {
                if (prevMedia != Long.MIN_VALUE && media != prevMedia + 1) {
                    c.mediaJumps++;
                    c.largestMediaJump = Math.max(c.largestMediaJump, Math.abs(media - prevMedia - 1));
                }
                prevMedia = media;
            }
        }

        @Override
        public int write(byte[] data, int offset, int length) throws AudioSinkException {
            block(m.writeCostMicros);
            long t = now();
            advance(t);
            long queued = written - (long) Math.floor(played);
            int fb = format.getFrameBytes();
            int frames = (int) Math.max(0, Math.min(length / fb, m.bufferFrames - queued));
            if (frames < length / fb) {
                device.counters.refusedWrites++;
                frames = frames / m.writeGranularityFrames * m.writeGranularityFrames;
            }
            if (frames > 0) {
                boolean current = false;
                long runFrames = 0;
                long runMedia = 0;
                long previousMedia = Long.MIN_VALUE;
                for (int i = 0; i < frames; i++) {
                    int at = offset + i * fb;
                    int left = (short) ((data[at] & 0xff) | (data[at + 1] << 8));
                    // a mono stream carries the left marker only: its media number wraps (counters of media jumps and the sync
                    // error mean nothing there; what is counted in frames and silence does)
                    int right = fb >= 4 ? (short) ((data[at + 2] & 0xff) | (data[at + 3] << 8)) : 1;
                    boolean audible = left != 0;
                    long media = audible ? (long) (right - 1) * 20000L + (left - 1) : 0;
                    analyse(left, audible, media);
                    boolean split = i > 0 && (audible != current || (audible && media != previousMedia + 1));
                    if (split) {
                        runs.addLast(new long[]{runFrames, current ? 1 : 0, runMedia});
                        runFrames = 0;
                    }
                    if (runFrames == 0) {
                        runMedia = media;
                    }
                    current = audible;
                    previousMedia = media;
                    runFrames++;
                }
                runs.addLast(new long[]{runFrames, current ? 1 : 0, runMedia});
                written += frames;
                device.counters.writtenFrames += frames;
                if (playing && filling && written - Math.floor(played) >= threshold) {
                    begin(t);
                }
                starving = false;
            }
            return frames * fb;
        }

        @Override
        public void play() {
            long t = now();
            advance(t);
            playing = true;
            device.counters.plays++;
            if (device.counters.plays == 1) {
                device.firstPlayMicros = t;
            }
            if (written - Math.floor(played) >= threshold) {
                begin(t);
            } else {
                filling = true;
            }
        }

        @Override
        public void pauseAndFlush() {
            long t = now();
            advance(t);
            device.counters.flushes++;
            if (!m.resetOnFlush) {
                counterBase += (long) Math.floor(played);
            }
            written = 0;
            played = 0;
            runs.clear();
            playing = false;
            filling = false;
            starving = false;
            haveReport = false;
            refreshedAt = Long.MIN_VALUE / 2;
            consumeStart = Long.MAX_VALUE;
        }

        private boolean presenting(long t) {
            return playing && !filling && t >= consumeStart;
        }

        @Override
        public boolean timestamp(long[] out) {
            device.counters.timestampCalls++;
            block(m.timestampCostMicros);
            long t = now();
            advance(t);
            long since = m.timestampAvailableOnlyAfterRun ? t - device.firstPlayMicros : t - consumeStart;
            if (m.timestampsNever || !presenting(t) || since < m.timestampFirstAfterMicros) {
                return false;
            }
            if (!haveReport || t - refreshedAt >= m.timestampPeriodMicros) {
                refreshes++;
                reportNoise = m.timestampNoiseMicros > 0 ? random.nextInt((int) m.timestampNoiseMicros + 1) : 0;
                reportedFrames = (long) Math.floor(played);
                reportedAt = t;
                if ((m.glitchEveryRefreshes > 0 && refreshes % m.glitchEveryRefreshes == 0) || refreshes == m.glitchAtRefresh) {
                    reportedFrames = Math.max(0, reportedFrames - (long) (m.glitchLagMicros * format.getSampleRate() / 1e6));
                }
                refreshedAt = t;
                haveReport = true;
            }
            out[0] = (reportedFrames + counterBase) & 0xffffffffL;
            long reportTime = (m.timestampTimeIsPollTime ? t : refreshedAt) + (m.noiseLate ? reportNoise : -reportNoise);
            if (m.timestampBiasMicros != 0 && t - device.firstPlayMicros >= m.biasAfterMicros) {
                reportTime += m.timestampBiasMicros;
            }
            long nano = reportTime * 1000L + m.timestampBaseDeltaNanos;
            out[1] = nano;
            device.counters.timestampOk++;
            return true;
        }

        @Override
        public long nanoTime() {
            return now() * 1000L;
        }

        @Override
        public long headPosition() {
            if (m.headUnavailable) {
                return 0;
            }
            advance(now());
            long head = (long) Math.floor(played) + counterBase;
            return (head / m.headGranularityFrames * m.headGranularityFrames) & 0xffffffffL;
        }

        @Override
        public int underrunCount() {
            advance(now());
            return device.counters.underruns;
        }

        @Override
        public void setGain(float gain) {
        }

        @Override
        public void release() {
        }
    }
}
