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
package de.yaacc.multiroom.protocol;

import java.util.Arrays;

/**
 * Turns the round trips of the Time messages into an estimate of the offset between the server clock and ours.
 *
 * <p><b>The samples.</b> A request is sent at client time {@code t_cs}, the server stamps its arrival with its own
 * clock and answers with {@code c2s = t_server_received - t_cs} (the latency field of the Time message), and the answer
 * carries the server send time {@code t_ss}; the client receives it at {@code t_cr} and computes
 * {@code s2c = t_cr - t_ss}. With a one-way delay of {@code d_up} and {@code d_down},
 * {@code c2s = offset + d_up} and {@code s2c = -offset + d_down}, so {@code (c2s - s2c) / 2 = offset + (d_up - d_down) / 2}
 * is the offset when the network is symmetric, and wrong by half the asymmetry when it is not. Nothing can see a
 * constant asymmetry; the error of any single sample is at most half the round trip {@code c2s + s2c}.
 *
 * <p><b>The filter.</b> The last {@value #WINDOW} samples are kept. The estimate is the median of the offsets of the
 * half of them with the shortest round trips (a slow round trip is the one that is most likely asymmetric, so it is
 * the first to go; ties go to the newer sample so that a drifting clock is followed more closely). {@link
 * #jitterMicros()} is the median absolute deviation of those samples around the estimate. With three samples the filter
 * calls itself synchronised.
 *
 * <p><b>Rejected samples.</b> A round trip below zero or above {@value #MAX_ROUND_TRIP_MICROS} microseconds is
 * refused (and counted), as is a value so large that arithmetic on it could overflow: the server is not trusted.
 *
 * <p><b>The jump clamp.</b> Two accepted samples can differ by at most the larger of their round trips divided by two,
 * so by less than {@value #MAX_ROUND_TRIP_MICROS} / 2 + the real change of the offset. A sample that is more than
 * {@value #JUMP_LIMIT_MICROS} microseconds away from the estimate is therefore not network noise: it is held back and
 * not applied. When {@value #RESYNC_SAMPLES} such samples in a row agree with each other (within
 * {@value #RESYNC_AGREEMENT_MICROS} microseconds), the server clock has jumped (a restart of the server): the window is
 * emptied, the three samples start the new one, and {@link #epoch()} grows so that chunks stamped with the old clock
 * are dropped. A sample that fits the old estimate ends the run of strange ones. Steps below the limit are followed by
 * the median as the window fills up with new samples (about half the window, 8 samples).
 *
 * <p>Thread safe: the time sync writes, the scheduler and the UI read.
 */
public final class ClockOffsetFilter implements OffsetSource {

    /** Samples kept. */
    public static final int WINDOW = 16;
    /** Samples needed before the estimate counts as synchronised. */
    public static final int MIN_SAMPLES = 3;
    /** The longest round trip that is taken into account. */
    public static final long MAX_ROUND_TRIP_MICROS = 500_000L;
    /** A sample further than this from the estimate is a jump candidate. */
    public static final long JUMP_LIMIT_MICROS = 1_000_000L;
    /** Candidates in a row that start a resynchronisation. */
    public static final int RESYNC_SAMPLES = 3;
    /** How closely the candidates must agree. */
    public static final long RESYNC_AGREEMENT_MICROS = 100_000L;
    /** One side of a sample above this (about 127 years of microseconds) can only be garbage. */
    private static final long MAX_SIDE_MICROS = 4_000_000_000_000_000L;

    private final long[] offsets = new long[WINDOW];
    private final long[] roundTrips = new long[WINDOW];
    private int next; // where the next sample goes
    private int count;
    private long estimate;
    private long jitter;
    private long minRoundTrip;
    private final long[] strange = new long[RESYNC_SAMPLES];
    private final long[] strangeRoundTrips = new long[RESYNC_SAMPLES];
    private int strangeCount;
    private int epoch;
    private int resyncs;
    private int rejected;
    private int jumpSamples;

    /**
     * Adds a sample of the time sync.
     *
     * @param c2sMicros the latency field of the answer: server receive time minus our send time
     * @param s2cMicros our receive time minus the server send time of the answer
     * @return true when the sample went into the estimate; false when it was refused or held back as a jump candidate
     */
    public synchronized boolean addSample(long c2sMicros, long s2cMicros) {
        if (c2sMicros > MAX_SIDE_MICROS || c2sMicros < -MAX_SIDE_MICROS || s2cMicros > MAX_SIDE_MICROS
                || s2cMicros < -MAX_SIDE_MICROS) {
            rejected++;
            return false;
        }
        long roundTrip = c2sMicros + s2cMicros;
        if (roundTrip < 0 || roundTrip > MAX_ROUND_TRIP_MICROS) {
            rejected++;
            return false;
        }
        long sample = (c2sMicros - s2cMicros) / 2;
        if (count >= MIN_SAMPLES && Math.abs(sample - estimate) > JUMP_LIMIT_MICROS) {
            return holdBack(sample, roundTrip);
        }
        strangeCount = 0;
        push(sample, roundTrip);
        recompute();
        return true;
    }

    private boolean holdBack(long sample, long roundTrip) {
        jumpSamples++;
        if (strangeCount == RESYNC_SAMPLES) { // keep the newest candidates
            System.arraycopy(strange, 1, strange, 0, RESYNC_SAMPLES - 1);
            System.arraycopy(strangeRoundTrips, 1, strangeRoundTrips, 0, RESYNC_SAMPLES - 1);
            strangeCount--;
        }
        strange[strangeCount] = sample;
        strangeRoundTrips[strangeCount++] = roundTrip;
        if (strangeCount == RESYNC_SAMPLES && spread(strange) <= RESYNC_AGREEMENT_MICROS) {
            resynchronise();
            return true; // the three samples are the start of the new window
        }
        return false;
    }

    private static long spread(long[] values) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    private void resynchronise() {
        long[] kept = strange.clone();
        long[] keptRoundTrips = strangeRoundTrips.clone();
        clearWindow();
        for (int i = 0; i < kept.length; i++) {
            push(kept[i], keptRoundTrips[i]);
        }
        strangeCount = 0;
        epoch++;
        resyncs++;
        recompute();
    }

    private void clearWindow() {
        Arrays.fill(offsets, 0);
        Arrays.fill(roundTrips, 0);
        next = 0;
        count = 0;
        estimate = 0;
        jitter = 0;
        minRoundTrip = 0;
        strangeCount = 0;
    }

    private void push(long sample, long roundTrip) {
        offsets[next] = sample;
        roundTrips[next] = roundTrip;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
    }

    private void recompute() {
        // indexes of the samples, the newest first, then stably sorted by round trip: the shortest come first and of
        // equal ones the newest
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = (next - 1 - i + 2 * WINDOW) % WINDOW;
        }
        Arrays.sort(order, (a, b) -> Long.compare(roundTrips[a], roundTrips[b]));
        int used = (count + 1) / 2;
        long[] chosen = new long[used];
        for (int i = 0; i < used; i++) {
            chosen[i] = offsets[order[i]];
        }
        estimate = median(chosen);
        long[] deviations = new long[used];
        for (int i = 0; i < used; i++) {
            deviations[i] = Math.abs(chosen[i] - estimate);
        }
        jitter = median(deviations);
        minRoundTrip = roundTrips[order[0]];
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[middle];
        }
        return Math.floorDiv(sorted[middle - 1] + sorted[middle], 2L);
    }

    /** Forgets all samples (a new connection) and starts a new epoch. */
    public synchronized void reset() {
        clearWindow();
        epoch++;
        resyncs = 0;
        rejected = 0;
        jumpSamples = 0;
    }

    @Override
    public synchronized boolean isSynchronized() {
        return count >= MIN_SAMPLES;
    }

    /** Half of the window: the median is then made of samples that all came after the first few. */
    @Override
    public synchronized boolean isSettled() {
        return count >= WINDOW / 2;
    }

    @Override
    public synchronized long offsetMicros() {
        return estimate;
    }

    @Override
    public synchronized int epoch() {
        return epoch;
    }

    /** The spread of the samples around the estimate (median absolute deviation), in microseconds. */
    public synchronized long jitterMicros() {
        return jitter;
    }

    /** The shortest round trip in the window; 0 when there is none. */
    public synchronized long minRoundTripMicros() {
        return minRoundTrip;
    }

    /**
     * The most the estimate can be off because of an asymmetric network: half the shortest round trip. It holds for
     * any single sample and, as the estimate is built from the samples with the shortest round trips, for the estimate
     * when the asymmetry is the same for all of them.
     */
    public synchronized long errorBoundMicros() {
        return minRoundTrip / 2;
    }

    /** Samples in the window (at most {@value #WINDOW}). */
    public synchronized int sampleCount() {
        return count;
    }

    /** Samples refused because of an impossible or too long round trip. */
    public synchronized int rejectedSamples() {
        return rejected;
    }

    /** Samples held back because they were further than the jump limit from the estimate. */
    public synchronized int jumpSamples() {
        return jumpSamples;
    }

    /** How often the clock was found anew after a jump. */
    public synchronized int resyncCount() {
        return resyncs;
    }

    /** The server clock for a time on ours; meaningful once {@link #isSynchronized()}. */
    public synchronized long serverNowMicros(long clientNowMicros) {
        return clientNowMicros + estimate;
    }
}
