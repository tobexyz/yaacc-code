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
 * The tunable numbers of the engine, with defaults. Setters validate and return this. Times in milliseconds unless the
 * name says otherwise.
 */
public final class ClientEngineConfig {

    int blockMs = 20;
    int maxAheadMs = 120;
    int prefillMs = 60;
    int maxChunkMs = 2_000;
    int maxFifoMs = 3_000;
    int releaseLeadMs = 250;
    int lateToleranceMs = 20;
    int continuityToleranceMs = 10;
    int maxGapFillMs = 3_000;
    int maxPadMs = 15_000;
    int alignToleranceMicros = 1_000;
    int deadBandMicros = 2_000;
    int gradualLimitMs = 50;
    int hardResyncMs = 300;
    int hardResyncReports = 3;
    int positionOutlierMs = 150;
    int primeAfterMs = 200;
    boolean smoothCorrections;
    int flacGapMs = 3_000;
    boolean flacTimeGuard = true;
    int maxSkipMs = 1_000;
    int underrunConfirmMs = 250;
    int underrunAheadMs = 10;
    boolean adaptiveWriteAhead = true;
    int writeAheadStepMs = 40;
    int writeAheadMaxMs = 350;
    int writeAheadCooldownMs = 3_000;
    int correctionIntervalFrames = 400;
    int minFlushIntervalMs = 500;
    int maxDecoderRestartsPerMinute = 12;
    int maxConsecutiveDecodeFailures = 8;
    int maxDecoderFailuresPerMinute = 12;
    int positionPollFastMs = 50;
    int positionPollSlowMs = 250;
    int positionTimeoutMs = 5_000;
    int underrunSlackMs = 20;
    int maxBlocksPerTick = 8;
    int tickMs = 10;
    long rebaseFrames = 1L << 34;
    long maxAnchorDifferenceMicros = 15_000_000_000_000L;

    private static int positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return value;
    }

    /** Length of a block written to the output (default 20 ms). */
    public ClientEngineConfig blockMs(int value) {
        blockMs = positive("blockMs", value);
        return this;
    }

    /** How far ahead of the output the engine writes (default 120 ms). */
    public ClientEngineConfig maxAheadMs(int value) {
        maxAheadMs = positive("maxAheadMs", value);
        return this;
    }

    /** Silence written before the output reports its first position (default 60 ms). */
    public ClientEngineConfig prefillMs(int value) {
        prefillMs = positive("prefillMs", value);
        return this;
    }

    /** The longest audio a chunk may hold (default 2 s); a longer one is dropped. */
    public ClientEngineConfig maxChunkMs(int value) {
        maxChunkMs = positive("maxChunkMs", value);
        return this;
    }

    /** The most decoded audio that waits for its time (default 3 s). */
    public ClientEngineConfig maxFifoMs(int value) {
        maxFifoMs = positive("maxFifoMs", value);
        return this;
    }

    /** A chunk is decoded and queued this long before its time (default 250 ms). */
    public ClientEngineConfig releaseLeadMs(int value) {
        releaseLeadMs = positive("releaseLeadMs", value);
        return this;
    }

    /** A chunk that starts later than this after its time is dropped (default 20 ms). */
    public ClientEngineConfig lateToleranceMs(int value) {
        lateToleranceMs = Math.max(0, value);
        return this;
    }

    /** The jitter of chunk timestamps that is not treated as a gap or overlap (default 10 ms). */
    public ClientEngineConfig continuityToleranceMs(int value) {
        continuityToleranceMs = positive("continuityToleranceMs", value);
        return this;
    }

    /** A gap in the timestamps up to this is filled with silence; a longer one starts the timeline anew (default 3 s). */
    public ClientEngineConfig maxGapFillMs(int value) {
        maxGapFillMs = positive("maxGapFillMs", value);
        return this;
    }

    /** The most silence inserted at once to wait for a chunk's time (default 15 s). */
    public ClientEngineConfig maxPadMs(int value) {
        maxPadMs = positive("maxPadMs", value);
        return this;
    }

    /** The first sample is placed within this many microseconds of its time (default 1000). */
    public ClientEngineConfig alignToleranceMicros(int value) {
        alignToleranceMicros = positive("alignToleranceMicros", value);
        return this;
    }

    /** Drift below this is left alone (default 2000 microseconds). */
    public ClientEngineConfig deadBandMicros(int value) {
        deadBandMicros = positive("deadBandMicros", value);
        return this;
    }

    /** Drift up to this is corrected frame by frame, more is a step of silence or a skip (default 50 ms). */
    public ClientEngineConfig gradualLimitMs(int value) {
        gradualLimitMs = positive("gradualLimitMs", value);
        return this;
    }

    /** Playback this late (the output holds old audio) is flushed (default 300 ms). */
    public ClientEngineConfig hardResyncMs(int value) {
        hardResyncMs = positive("hardResyncMs", value);
        return this;
    }

    /** The output must be this late in this many consecutive fresh position reports before it is flushed (default 3). */
    public ClientEngineConfig hardResyncReports(int value) {
        hardResyncReports = positive("hardResyncReports", value);
        return this;
    }

    /**
     * A dropped or repeated frame is spread over a short window by interpolation (a local resampling of 32 frames to 31
     * or 33) instead of cutting one sample; the step in a pure tone is then no larger than the signal's own slope
     * (default off: the engine tests read the marker of every frame; the client service switches it on).
     */
    public ClientEngineConfig smoothCorrections(boolean value) {
        smoothCorrections = value;
        return this;
    }

    /**
     * A flac chunk whose timestamp is more than this far from where the chunk before it ended (the source paused for
     * seconds, many chunks were lost, the server clock jumped) starts the timeline anew: flac chunks are placed by counting
     * frames, which cannot see a pause. Shorter pauses are found by {@code FlacTimeGuard} (the skew of the timestamps
     * against the frame count) and filled with silence like the gap of a pcm stream (default 3000 ms, the gap a pcm stream
     * fills).
     */
    public ClientEngineConfig flacGapMs(int value) {
        flacGapMs = positive("flacGapMs", value);
        return this;
    }

    /**
     * Switches the {@code FlacTimeGuard} (the shift of a flac stream against its timestamps, found from the position of
     * each chunk read from its FLAC frame header) off; for tests of what is left. The guard acts <b>only</b> on positions
     * read from frame headers: when they can not be read the guard does nothing (it never works from a count of decoded
     * frames), whatever this says; without the guard a jump of the timestamps of 300 ms or more places the stream anew.
     */
    public ClientEngineConfig flacTimeGuard(boolean on) {
        flacTimeGuard = on;
        return this;
    }

    /**
     * The most audio the engine throws away in one go because it thinks the stream is late; beyond it the timeline is
     * dropped and the next chunk is placed by its own timestamp (default 1000 ms).
     */
    public ClientEngineConfig maxSkipMs(int value) {
        maxSkipMs = positive("maxSkipMs", value);
        return this;
    }

    /**
     * A rise of the platform's underrun counter flushes only when the position confirms it (stalled, or the output holds
     * less than {@link #underrunAheadMs(int)} ms); the position gets this long to confirm (default 250 ms).
     */
    public ClientEngineConfig underrunConfirmMs(int value) {
        underrunConfirmMs = positive("underrunConfirmMs", value);
        return this;
    }

    /** The output holds less than this many ms ahead: confirms an underrun (default 10 ms). */
    public ClientEngineConfig underrunAheadMs(int value) {
        underrunAheadMs = positive("underrunAheadMs", value);
        return this;
    }

    /**
     * When the platform counts an underrun that the position does not confirm (the output holds more than it needs to
     * look dry, but less than its mixer needs) the write-ahead is raised by {@code step} ms, at most once per {@code
     * cooldown} ms, up to {@code max} ms; it is never lowered during a session (defaults 40, 3000, 350; on by default).
     */
    public ClientEngineConfig adaptiveWriteAhead(boolean on) {
        adaptiveWriteAhead = on;
        return this;
    }

    public ClientEngineConfig writeAheadSteps(int stepMs, int cooldownMs, int maxMs) {
        writeAheadStepMs = positive("writeAheadStepMs", stepMs);
        writeAheadCooldownMs = positive("writeAheadCooldownMs", cooldownMs);
        writeAheadMaxMs = positive("writeAheadMaxMs", maxMs);
        return this;
    }

    /**
     * A position report that is off the extrapolation of the previous one by more than this is held back until the next
     * report confirms it (default 150 ms).
     */
    public ClientEngineConfig positionOutlierMs(int value) {
        positionOutlierMs = positive("positionOutlierMs", value);
        return this;
    }

    /**
     * When the output has not given a position this long after the first write, the engine keeps queueing silence until
     * the output is full: an output that waits for a full buffer before it plays would never start otherwise (default 200).
     */
    public ClientEngineConfig primeAfterMs(int value) {
        primeAfterMs = positive("primeAfterMs", value);
        return this;
    }

    /** At most one frame is dropped or repeated per this many frames (default 400, 0.25 percent). */
    public ClientEngineConfig correctionIntervalFrames(int value) {
        correctionIntervalFrames = positive("correctionIntervalFrames", value);
        return this;
    }

    /** The output is flushed at most this often because of lateness or an underrun (default 500 ms). */
    public ClientEngineConfig minFlushIntervalMs(int value) {
        minFlushIntervalMs = positive("minFlushIntervalMs", value);
        return this;
    }

    /** A different codec header restarts the decoder; more restarts than this per minute end the session (default 12). */
    public ClientEngineConfig maxDecoderRestartsPerMinute(int value) {
        maxDecoderRestartsPerMinute = positive("maxDecoderRestartsPerMinute", value);
        return this;
    }

    /** Chunks in a row that may fail to decode before the session ends (default 8). */
    public ClientEngineConfig maxConsecutiveDecodeFailures(int value) {
        maxConsecutiveDecodeFailures = positive("maxConsecutiveDecodeFailures", value);
        return this;
    }

    /**
     * Chunks that fail to decode or are too large for the decoder may happen this often in 60 seconds before the session
     * ends (default 12, the figure of the header restarts); a platform decoder is made anew after each one.
     */
    public ClientEngineConfig maxDecoderFailuresPerMinute(int value) {
        maxDecoderFailuresPerMinute = positive("maxDecoderFailuresPerMinute", value);
        return this;
    }

    /** The output must report a position within this time after it started (default 5 s). */
    public ClientEngineConfig positionTimeoutMs(int value) {
        positionTimeoutMs = positive("positionTimeoutMs", value);
        return this;
    }

    /** How long the engine thread waits between cycles (default 10 ms). */
    public ClientEngineConfig tickMs(int value) {
        tickMs = positive("tickMs", value);
        return this;
    }

    /**
     * The timeline is shifted in place (whole seconds, exactly) when this many frames have run from its anchor, so the
     * frame counts stay small whatever the length of the session (default 2^34, days of playback).
     */
    public ClientEngineConfig rebaseFrames(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("rebaseFrames must be at least 1");
        }
        rebaseFrames = value;
        return this;
    }

    public int getTickMs() {
        return tickMs;
    }
}
