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

/**
 * The audio output of the engine on top of a {@link TrackPort}: everything that is logic and not a platform call. It
 * keeps the position bookkeeping (the 32 bit counters are unwrapped, a timestamp older than the last flush is ignored, the
 * position after a flush is taken from zero), converts the platform's monotonic time to the engine clock by the age of a
 * report, and falls back to the head position when the platform gives no timestamp.
 */
public class StreamingSink implements AudioSink {

    /** Failed timestamp polls (a poll is at least {@code MIN_POLL_NANOS} apart) after which the head position is used. */
    static final int TIMESTAMP_FAILURES_BEFORE_FALLBACK = 40;
    /** While on the head position, the timestamp is tried again this often. */
    static final long TIMESTAMP_RETRY_NANOS = 5_000_000_000L;
    static final long MIN_POLL_NANOS = 20_000_000L;
    /** A timestamp that is older than this, or from the future by more than {@code MAX_FUTURE_NANOS}, is on another clock base. */
    static final long MAX_PLAUSIBLE_AGE_NANOS = 2_000_000_000L;
    static final long MAX_FUTURE_NANOS = 200_000_000L;
    /** The streaming start threshold the engine asks for: it wants the output to start early, with this much audio queued. */
    static final int WANTED_START_THRESHOLD_MS = 40;

    /** Opens the platform output. */
    public interface Opener {
        TrackPort open(SinkFormat format) throws AudioSinkException;
    }

    private final MicroClock clock;
    private final Opener opener;
    private final long[] timestamp = new long[2];
    private volatile TrackPort port;
    private SinkFormat format;
    private boolean started;
    private int failures;
    private boolean usingHead;
    private long lastRetryNanos;
    private boolean headNoted;
    private long lastPollNanos = Long.MIN_VALUE / 2; // nanoTime has an arbitrary origin: never compare with 0
    private long flushedAtNanos = Long.MIN_VALUE / 2;
    private long wrapBase;
    private long lastLow;
    private long baseline;
    private PlaybackPosition lastPosition;
    private volatile EventTrace trace;
    private boolean timestampEverSucceeded;
    private long timestampPolls;
    private long timestampPollFailures;
    private long implausibleTimestamps;
    private long lastTimestampAgeMicros = Long.MIN_VALUE;

    /** @param clock the clock of the engine (the one the connection's time sync uses) */
    public StreamingSink(MicroClock clock, Opener opener) {
        this.clock = clock;
        this.opener = opener;
    }

    @Override
    public void open(SinkFormat wanted) throws AudioSinkException {
        release();
        port = opener.open(wanted);
        format = wanted;
        resetPositionState();
        failures = 0;
        headNoted = false;
        usingHead = false; // a property of the device: kept across flushes, tried again only for a new output
        timestampEverSucceeded = false;
        timestampPolls = 0;
        timestampPollFailures = 0;
        implausibleTimestamps = 0;
        lastTimestampAgeMicros = Long.MIN_VALUE;
        // An output that waits for a full buffer before it plays (the default of newer Android versions) would not play
        // the short prefill; ask for an early start where the platform allows it.
        port.requestStartThresholdFrames((int) Math.max(1, (long) wanted.getSampleRate() * WANTED_START_THRESHOLD_MS / 1000L));
    }

    @Override
    public void attachTrace(EventTrace trace) {
        this.trace = trace;
    }

    private void note(TraceKind kind, long a, long b) {
        EventTrace t = trace;
        if (t != null) {
            t.add(clock.nowMicros(), kind, a, b);
        }
    }

    @Override
    public int startThresholdFrames() {
        TrackPort current = port;
        return current == null ? 0 : current.startThresholdFrames();
    }

    /** One line about the output for the status and the trace: buffer, start threshold, underruns, timestamp health. */
    @Override
    public String describe() {
        TrackPort current = port;
        if (current == null) {
            return "no output";
        }
        return "track buffer " + current.bufferFrames() + " frames, start threshold " + current.startThresholdFrames()
                + ", underruns " + current.underrunCount() + ", timestamp ever ok " + (timestampEverSucceeded ? "yes" : "no")
                + " (polls " + timestampPolls + ", failed " + timestampPollFailures + ", implausible " + implausibleTimestamps
                + ", last age " + (lastTimestampAgeMicros == Long.MIN_VALUE ? "none" : (lastTimestampAgeMicros / 1000) + " ms")
                + "), position source " + positionSource();
    }

    private void resetPositionState() {
        started = false;
        lastPollNanos = Long.MIN_VALUE / 2;
        wrapBase = 0;
        lastLow = 0;
        lastPosition = null;
    }

    @Override
    public int write(byte[] data, int offset, int length) throws AudioSinkException {
        TrackPort current = port;
        if (current == null) {
            throw new AudioSinkException(AudioSinkException.Reason.WRITE_FAILED, "no output");
        }
        return current.write(data, offset, length);
    }

    @Override
    public void start() throws AudioSinkException {
        TrackPort current = port;
        if (current == null) {
            throw new AudioSinkException(AudioSinkException.Reason.START_FAILED, "no output");
        }
        if (started) {
            return;
        }
        current.play();
        started = true;
    }

    @Override
    public void flush() {
        TrackPort current = port;
        if (current == null) {
            return;
        }
        current.pauseAndFlush();
        flushedAtNanos = current.nanoTime();
        resetPositionState();
        baseline = current.headPosition();
    }

    @Override
    public PlaybackPosition position() {
        TrackPort current = port;
        if (current == null || !started) {
            return null;
        }
        long nowNanos = current.nanoTime();
        if (nowNanos - lastPollNanos < MIN_POLL_NANOS) {
            return lastPosition;
        }
        lastPollNanos = nowNanos;
        PlaybackPosition position = null;
        if (usingHead && nowNanos - lastRetryNanos >= TIMESTAMP_RETRY_NANOS) {
            lastRetryNanos = nowNanos;
            // the timestamp may only have been late (it starts after the output has really started): try it again now and then
            usingHead = false;
            failures = TIMESTAMP_FAILURES_BEFORE_FALLBACK - 1; // one more failure and it is back on the head position
        }
        if (!usingHead) {
            timestampPolls++;
            boolean ok = current.timestamp(timestamp);
            boolean fresh = false;
            if (ok) {
                long ageNanos = nowNanos - timestamp[1];
                lastTimestampAgeMicros = ageNanos / 1000L;
                if (ageNanos > MAX_PLAUSIBLE_AGE_NANOS || ageNanos < -MAX_FUTURE_NANOS) {
                    if (implausibleTimestamps++ == 0) {
                        note(TraceKind.TIMESTAMP_ODD, ageNanos / 1_000_000L, 0);
                    }
                    // another clock base: the frame/time pair says nothing about now
                    ok = false;
                } else {
                    fresh = timestamp[1] >= flushedAtNanos; // older: left over from before the flush
                }
            }
            if (ok) {
                timestampEverSucceeded = true;
                failures = 0;
            } else {
                timestampPollFailures++;
                if (++failures >= TIMESTAMP_FAILURES_BEFORE_FALLBACK) {
                    usingHead = true;
                    if (!headNoted) {
                        headNoted = true;
                        note(TraceKind.HEAD_FALLBACK, timestampPollFailures, implausibleTimestamps);
                    }
                }
            }
            if (fresh) {
                long low = timestamp[0] & 0xffffffffL;
                if (low < lastLow && lastLow - low > 0x80000000L) {
                    wrapBase += 1L << 32;
                }
                lastLow = low;
                long frames = wrapBase + low - baseline;
                long ageMicros = (nowNanos - timestamp[1]) / 1000L;
                position = new PlaybackPosition(Math.max(0, frames), clock.nowMicros() - ageMicros);
            }
        }
        if (usingHead) {
            long moved = current.headPosition() - baseline;
            if (moved > 0) { // 0: nothing played yet (or no head position at all); that is no position
                position = new PlaybackPosition(moved, clock.nowMicros());
            }
        }
        if (position != null) {
            lastPosition = position;
        }
        return lastPosition;
    }

    @Override
    public int underrunCount() {
        TrackPort current = port;
        return current == null ? 0 : current.underrunCount();
    }

    @Override
    public void setGain(float gain) {
        TrackPort current = port;
        if (current != null) {
            current.setGain(gain);
        }
    }

    @Override
    public String positionSource() {
        return usingHead ? "head position (coarse)" : "timestamp";
    }

    @Override
    public void release() {
        TrackPort old = port;
        port = null;
        started = false;
        if (old != null) {
            old.release();
        }
    }
}
