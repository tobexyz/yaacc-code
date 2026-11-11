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
package de.yaacc.multiroom.feed;

import java.util.concurrent.locks.LockSupport;

/**
 * The time source of the feed: a monotonic nanosecond clock and a sleep that goes with it. The real one is
 * {@link #SYSTEM}; a test passes a clock that it moves by hand, so that pacing needs no real waiting.
 */
public interface FeedClock {

    /** The real clock: {@link System#nanoTime()} and a sleep that ends early on an interrupt. */
    FeedClock SYSTEM = new FeedClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleepNanos(long nanos) throws InterruptedException {
            final long end = System.nanoTime() + nanos;
            long remaining = nanos;
            while (remaining > 0) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                LockSupport.parkNanos(remaining);
                remaining = end - System.nanoTime();
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    };

    /** A monotonic time in nanoseconds; only differences mean something. */
    long nanoTime();

    /**
     * Waits until this clock has advanced by {@code nanos}; returns at once for zero or less.
     *
     * @throws InterruptedException when the thread is interrupted (the feeder does that to stop its writer)
     */
    void sleepNanos(long nanos) throws InterruptedException;
}
