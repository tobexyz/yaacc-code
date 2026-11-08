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
package de.yaacc.upnp;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * How long a play action waits for a receiver the user selected that is not in the registry right
 * now. After a suspend the registry may have dropped the renderer (no ssdp:alive could be received)
 * and it is back a moment after a new search, so giving up at once would refuse the play and, worse,
 * drop the receiver from the stored selection.
 * <p>
 * At most {@link #MAX_ATTEMPTS} looks in total, {@link #WAIT_MILLIS} apart (about 5 s), with a
 * chance to start a search before each wait. No Android classes: unit-testable on the JVM. Meant for
 * a worker thread only: {@link #await} sleeps.
 */
public final class ReceiverAvailabilityPolicy {

    /** Total number of looks into the registry, the first one included. */
    public static final int MAX_ATTEMPTS = 3;

    /** Wait before look 2 and before look 3. */
    public static final long WAIT_MILLIS = 2500L;

    /** Sleeps; replaceable so tests do not wait. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Sleeper sleeper;

    public ReceiverAvailabilityPolicy() {
        this(Thread::sleep);
    }

    ReceiverAvailabilityPolicy(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * Looks until no selected receiver is missing, the looks are used up or the thread is
     * interrupted while waiting.
     *
     * @param missingCount  how many selected receivers are not in the registry now
     * @param beforeWaiting called with the number of the look that found receivers missing, right
     *                      before the wait (start a search here); not called after the last look
     * @return the number of receivers still missing after the last look; 0 means all are there
     */
    public int await(IntSupplier missingCount, IntConsumer beforeWaiting) {
        int missing = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            missing = missingCount.getAsInt();
            if (missing == 0 || attempt == MAX_ATTEMPTS) {
                return missing;
            }
            beforeWaiting.accept(attempt);
            try {
                sleeper.sleep(WAIT_MILLIS);
            } catch (InterruptedException e) {
                // keep the interrupt for the caller
                Thread.currentThread().interrupt();
                return missing;
            }
        }
        return missing;
    }
}
