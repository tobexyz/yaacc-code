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

import java.util.function.LongSupplier;

/**
 * Lets only ONE picked action be between its confirm and its end. The receiver selection that a
 * picked action stores ({@code UpnpClient#setReceiverDeviceIds}) is global and read back later by
 * the player service; a second action that confirmed meanwhile would replace it, or the first
 * action's clearing would wipe it. While a flight is active no other action may begin, so the
 * selection is only ever the active action's. Plain Java, thread-safe.
 * <p>
 * A flight that is never ended (a lost callback) expires after {@code maxMillis}, so one bug cannot
 * block picking for good; a stale {@link Ticket} of such a flight cannot end the next one.
 */
public final class ActionFlight {

    /** One begun action. {@link #end()} is idempotent and only ends its own flight. */
    public final class Ticket {
        private final long id;

        private Ticket(long id) {
            this.id = id;
        }

        /** The action is done (started, failed or refused): the next one may begin. */
        public void end() {
            endFlight(id);
        }
    }

    private final LongSupplier nowMillis;
    private final long maxMillis;

    // all guarded by "this"
    private long activeId;
    private long startedAtMillis;
    private long lastId;

    /**
     * @param nowMillis a monotonic clock in milliseconds
     * @param maxMillis how long a flight may last before it counts as lost, greater than zero
     */
    public ActionFlight(LongSupplier nowMillis, long maxMillis) {
        if (nowMillis == null || maxMillis <= 0L) {
            throw new IllegalArgumentException("a clock and a positive maximum are required");
        }
        this.nowMillis = nowMillis;
        this.maxMillis = maxMillis;
    }

    /** The process-wide flight on the monotonic system clock, lasting at most {@link ShareLimits#ACTION_FLIGHT_MAX_MILLIS}. */
    public static ActionFlight createDefault() {
        return new ActionFlight(() -> System.nanoTime() / 1_000_000L, ShareLimits.ACTION_FLIGHT_MAX_MILLIS);
    }

    /** @return the ticket of the new flight, or null (nothing changes) if another action is in flight */
    public synchronized Ticket tryBegin() {
        if (isActiveLocked()) {
            return null;
        }
        lastId++;
        activeId = lastId;
        startedAtMillis = nowMillis.getAsLong();
        return new Ticket(activeId);
    }

    /** @return true if an action is in flight and has not expired */
    public synchronized boolean isActive() {
        return isActiveLocked();
    }

    private boolean isActiveLocked() {
        if (activeId == 0L) {
            return false;
        }
        long elapsed = nowMillis.getAsLong() - startedAtMillis;
        if (elapsed < 0L || elapsed >= maxMillis) {
            activeId = 0L; // lost: do not block for good
            return false;
        }
        return true;
    }

    private synchronized void endFlight(long id) {
        if (activeId == id) {
            activeId = 0L;
        }
    }
}
