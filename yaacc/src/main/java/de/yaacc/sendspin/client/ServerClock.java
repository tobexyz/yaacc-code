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
package de.yaacc.sendspin.client;

/**
 * An estimate of the server clock for a client that only shows progress (a controller): the offset {@code server - client}, from
 * bursts of {@code client/time} and {@code server/time} (spec, "Clock Synchronization"). Of each burst only the measurement with the
 * lowest {@code max_error} above zero is used; successive bursts are smoothed, and a residual larger than three times the error
 * starts a new baseline (the specification's adaptive cutoff). This is not the Kalman filter of the specification, which an audio
 * player needs for sample accurate sync: a display is right to a few milliseconds with it. Pure; one thread.
 */
public final class ServerClock {

    /** How far one burst moves the estimate. */
    private static final double SMOOTHING = 0.3;
    private static final double ADAPTIVE_CUTOFF = 3.0;

    private boolean hasEstimate;
    private double offset;

    private boolean burstHasMeasurement;
    private long burstMeasurement;
    private long burstError;

    /** {@code ((T2 - T1) + (T3 - T4)) / 2}, integer division toward zero. */
    public static long measurement(long t1, long t2, long t3, long t4) {
        return ((t2 - t1) + (t3 - t4)) / 2;
    }

    /** {@code ((T4 - T1) - (T3 - T2)) / 2}: half the round trip, the worst error of the offset. */
    public static long maxError(long t1, long t2, long t3, long t4) {
        return ((t4 - t1) - (t3 - t2)) / 2;
    }

    /** One {@code server/time} reply of the running burst: T1 sent, T2 and T3 from the server, T4 received. */
    public void add(long t1, long t2, long t3, long t4) {
        long measurement = measurement(t1, t2, t3, t4);
        long error = maxError(t1, t2, t3, t4);
        if (!burstHasMeasurement || better(error, burstError)) {
            burstHasMeasurement = true;
            burstMeasurement = measurement;
            burstError = error;
        }
    }

    /** The error counts only above zero; one at or below zero is trusted only if nothing else came. */
    private static boolean better(long error, long than) {
        if (than <= 0) {
            return error > 0;
        }
        return error > 0 && error < than;
    }

    /** The burst is over: its best measurement feeds the estimate. */
    public void endBurst() {
        if (!burstHasMeasurement) {
            return;
        }
        if (!hasEstimate) {
            offset = burstMeasurement;
            hasEstimate = true;
        } else {
            double residual = burstMeasurement - offset;
            double cutoff = ADAPTIVE_CUTOFF * Math.max(1, burstError);
            if (Math.abs(residual) > cutoff) {
                offset = burstMeasurement;
            } else {
                offset += SMOOTHING * residual;
            }
        }
        burstHasMeasurement = false;
    }

    public boolean hasEstimate() {
        return hasEstimate;
    }

    public long offsetMicros() {
        return Math.round(offset);
    }

    /** The server clock at a time of the client clock; the client time itself before there is an estimate. */
    public long serverTimeAt(long clientMicros) {
        return clientMicros + (hasEstimate ? offsetMicros() : 0);
    }
}
