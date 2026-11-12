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
    int gradualLimitMs = 30;
    int hardResyncMs = 300;
    int correctionIntervalFrames = 400;
    int minFlushIntervalMs = 500;
    int maxDecoderRestartsPerMinute = 12;
    int maxConsecutiveDecodeFailures = 8;
    int maxDecoderFailuresPerMinute = 12;
    int positionPollFastMs = 50;
    int positionPollSlowMs = 500;
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

    /** Drift up to this is corrected frame by frame, more is a step of silence or a skip (default 30 ms). */
    public ClientEngineConfig gradualLimitMs(int value) {
        gradualLimitMs = positive("gradualLimitMs", value);
        return this;
    }

    /** Playback this late (the output holds old audio) is flushed (default 300 ms). */
    public ClientEngineConfig hardResyncMs(int value) {
        hardResyncMs = positive("hardResyncMs", value);
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
