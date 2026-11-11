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
     * The real clock: the monotonic clock counted from the first use of this class, so the seconds of a timestamp always
     * fit into the 32 bits of the protocol (a raw {@code System.nanoTime()} has an arbitrary origin).
     */
    static MicroClock monotonic() {
        return Monotonic.INSTANCE;
    }

    /** Holder of the real clock. */
    final class Monotonic implements MicroClock {
        static final Monotonic INSTANCE = new Monotonic();
        private final long originNanos = System.nanoTime();

        private Monotonic() {
        }

        @Override
        public long nowMicros() {
            return (System.nanoTime() - originNanos) / 1000L + 1_000_000L;
        }
    }
}
