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

/**
 * The client's clock in microseconds. It never goes back and has no meaning of its own (the time sync measures the
 * server clock against it), so tests inject a clock they move by hand.
 */
public interface MicroClock {

    long nowMicros();

    /**
     * The real clock: {@code System.nanoTime()} counted from the first use of this class, so the seconds of a timestamp
     * always fit into the 32 bits of the protocol (a raw {@code nanoTime} has an arbitrary origin). On Android that
     * source does not run while the CPU sleeps; the audio engine builds its clock with
     * {@link #fromNanoSource} and {@code SystemClock.elapsedRealtimeNanos()} instead (Group 8).
     */
    static MicroClock monotonic() {
        return Monotonic.INSTANCE;
    }

    /**
     * A clock on any source of nanoseconds that never goes back (the source of a test, or Android's
     * {@code SystemClock.elapsedRealtimeNanos()}, which keeps counting while the device sleeps). Time is counted from the
     * moment of the call.
     */
    static MicroClock fromNanoSource(java.util.function.LongSupplier nanos) {
        return new Monotonic(nanos);
    }

    /** The clock on a source of nanoseconds. */
    final class Monotonic implements MicroClock {
        static final Monotonic INSTANCE = new Monotonic(System::nanoTime);
        private final java.util.function.LongSupplier nanos;
        private final long originNanos;

        private Monotonic(java.util.function.LongSupplier nanos) {
            this.nanos = nanos;
            this.originNanos = nanos.getAsLong();
        }

        @Override
        public long nowMicros() {
            return (nanos.getAsLong() - originNanos) / 1000L + 1_000_000L;
        }
    }
}
