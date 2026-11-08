/*
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
package de.yaacc.share;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Waits, bounded, until something becomes true (for example the local HTTP server accepts
 * connections after a cold start). Plain Java with an injectable clock and sleeper. Blocks the
 * calling thread: never call it on the main thread.
 */
public final class ReadinessWait {

    /** How the waiting thread sleeps. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private ReadinessWait() {
    }

    /** Same with the system clock and {@link Thread#sleep}. */
    public static boolean await(BooleanSupplier ready, long maxMillis, long pollMillis) {
        return await(ready, maxMillis, pollMillis, () -> System.nanoTime() / 1_000_000L, Thread::sleep);
    }

    /**
     * Checks {@code ready}, then every {@code pollMillis} until it is true or {@code maxMillis}
     * have passed (one last check at the end). A check that throws counts as not ready. An
     * interrupt ends the wait with false and keeps the thread's interrupt flag.
     *
     * @return true if {@code ready} became true in time
     */
    public static boolean await(BooleanSupplier ready, long maxMillis, long pollMillis, LongSupplier clock,
                                Sleeper sleeper) {
        long start = clock.getAsLong();
        long poll = Math.max(1L, pollMillis);
        while (true) {
            if (isReady(ready)) {
                return true;
            }
            long remaining = maxMillis - (clock.getAsLong() - start);
            if (remaining <= 0L) {
                return false;
            }
            try {
                sleeper.sleep(Math.min(poll, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean isReady(BooleanSupplier ready) {
        try {
            return ready.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
