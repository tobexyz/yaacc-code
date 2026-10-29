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
package de.yaacc.util;

import java.util.function.LongSupplier;

/**
 * When the changes of the SAF cache that were collected in memory are written to disk: after {@code maxChanges}
 * changes, or when the oldest unwritten change is {@code maxAgeMillis} old, whichever comes first. Every write
 * rewrites the whole preferences file, so writing less often is the point. Not thread safe (the cache manager
 * guards it with its lock). No Android classes; the clock is passed in.
 */
final class SafCacheFlushPolicy {

    private final int maxChanges;
    private final long maxAgeMillis;
    private final LongSupplier clockMillis;
    private int changes;
    private long firstChangeAt;

    SafCacheFlushPolicy(int maxChanges, long maxAgeMillis, LongSupplier clockMillis) {
        if (maxChanges < 1 || maxAgeMillis < 0) {
            throw new IllegalArgumentException("maxChanges=" + maxChanges + " maxAgeMillis=" + maxAgeMillis);
        }
        this.maxChanges = maxChanges;
        this.maxAgeMillis = maxAgeMillis;
        this.clockMillis = clockMillis;
    }

    /** One more change waits to be written. */
    void recordChange() {
        if (changes == 0) {
            firstChangeAt = clockMillis.getAsLong();
        }
        changes++;
    }

    /** The collected changes were written (or handed over to be written). */
    void flushed() {
        changes = 0;
    }

    boolean hasChanges() {
        return changes > 0;
    }

    int changes() {
        return changes;
    }

    boolean isDue() {
        return changes > 0 && (changes >= maxChanges || clockMillis.getAsLong() - firstChangeAt >= maxAgeMillis);
    }

    /**
     * @return how long from now until a write is due: 0 if it is due now, -1 if there is nothing to write
     */
    long millisUntilDue() {
        if (changes == 0) {
            return -1;
        }
        if (changes >= maxChanges) {
            return 0;
        }
        return Math.max(0, firstChangeAt + maxAgeMillis - clockMillis.getAsLong());
    }
}
