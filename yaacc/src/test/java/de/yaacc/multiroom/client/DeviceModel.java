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
 * How a real phone's audio output may differ from the ideal one of {@link SimulatedSink}: the knobs of {@link DeviceSimSink}.
 * The defaults are a benign device; the tests turn one or more knobs to the behaviours the documentation describes or the
 * owner's device is suspected of, and look at what is <i>played</i>.
 */
final class DeviceModel {
    /** Size of the output buffer. */
    int bufferFrames = 19200;
    /** Frames that must be queued before a started track plays (and again after it ran dry, see stallAfterUnderrun). */
    int startThresholdFrames = 1;
    /** True where the app may lower the threshold (Android 12 and later). */
    boolean thresholdSettable;
    /** What the output says its threshold is, when that is not the truth (an older Android version cannot say). 0: the truth. */
    int reportedThresholdFrames;
    long outputLatencyMicros = 40_000;
    /** The output plays at this speed (1.0003: 300 ppm fast). */
    double speed = 1.0;
    /** The timestamp is refreshed this often; between refreshes the same (old) pair is returned. */
    long timestampPeriodMicros = 100_000;
    /** No timestamp until this long after the first frame was presented. */
    long timestampFirstAfterMicros;
    /** The "first after" delay counts from the start of the run, not from every start after a flush (a late-starting service). */
    boolean timestampAvailableOnlyAfterRun;
    /** The platform never gives a timestamp. */
    boolean timestampsNever;
    /** The nanoTime of a timestamp is on another base than System.nanoTime by this much. */
    long timestampBaseDeltaNanos;
    /** The timestamp pairs the frame position of the last refresh with the time of the poll (position lags by up to a period). */
    boolean timestampTimeIsPollTime;
    /** Every this many refreshes one report is odd: its frame count lags by {@code glitchLagMicros} but it carries the poll time. */
    int glitchEveryRefreshes;
    long glitchLagMicros = 400_000;
    /** Only the refresh with this number (counted from 1) is odd (lags by {@code glitchLagMicros}); 0: none. */
    int glitchAtRefresh;
    /** From this long after the first play() the reported times are late by {@code timestampBiasMicros} (a latency the HAL adds). */
    long biasAfterMicros;
    long timestampBiasMicros;
    /** Each report's time is early by a random 0..this (a jittery age), the frames are those of the true moment. */
    long timestampNoiseMicros;
    /** The noise makes the reported time late (the lateness then reads +0..noise, mean noise/2) instead of early. */
    boolean noiseLate;
    /** A write takes whole multiples of this many frames only (when it cannot take all); 1: any number. */
    int writeGranularityFrames = 1;
    /** A call of getTimestamp / write blocks the calling (engine) thread for this long. */
    long timestampCostMicros;
    long writeCostMicros;
    /** The position counters restart at 0 after a flush; false: they go on. */
    boolean resetOnFlush = true;
    /** A track that ran dry plays again only after the buffer was filled to the threshold again. */
    boolean stallAfterUnderrun = true;
    /** The underrun counter counts a starved moment (otherwise only the first moment of a stall is not counted: never). */
    boolean countUnderruns = true;
    /**
     * A deep-buffer output (AudioFlinger latency 128 ms, notification period 2048 frames, seen on a Nothing A142 with
     * Android 16: mFlags 0x8, mLatency 528, mAfLatency 128): at every mixer period (this many frames played) fewer than
     * {@code mixerMinQueuedFrames} queued frames make the mixer run short. That is a real underrun: the counter rises,
     * the period is played as silence and the position stands still for that long. The position does not stall for long,
     * so the position evidence shows nothing dramatic. 0: no such output.
     */
    int mixerMinQueuedFrames;
    int mixerPeriodFrames = 2048;
    /** The head position moves in steps of this many frames. */
    int headGranularityFrames = 1;
    /** The head position is not available (always 0). */
    boolean headUnavailable;
}
