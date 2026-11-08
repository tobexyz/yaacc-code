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
package de.yaacc.browser;

import java.util.function.LongSupplier;

/**
 * Loop guard for the layout relaunch: allows at most one relaunch per time window.
 * <p>
 * Each browser activity relaunches into the other layout when the effective layout differs from
 * its own. If that decision can ever be wrong (a stored "new" that cannot be honored, a preference
 * flipping between two resumes), the two activities would otherwise start each other forever.
 * When the guard denies, the caller stays on its current layout and logs.
 * <p>
 * Plain Java with an injectable clock so it can be unit tested. The clock must be monotonic
 * (the Android side uses {@link System#nanoTime()}); a clock that still goes backwards never
 * wedges the guard: a reading earlier than the last allowed relaunch counts as an elapsed window.
 */
public final class RelaunchGuard {

    /** At most one relaunch per this many milliseconds. */
    public static final long DEFAULT_WINDOW_MILLIS = 2000L;

    private static final RelaunchGuard SHARED = new RelaunchGuard(
            () -> System.nanoTime() / 1_000_000L, DEFAULT_WINDOW_MILLIS);

    private final LongSupplier clockMillis;
    private final long windowMillis;
    private boolean hasRelaunched = false;
    private long lastRelaunchMillis = 0L;

    /**
     * @param clockMillis  monotonic clock in milliseconds
     * @param windowMillis minimum distance between two allowed relaunches
     */
    public RelaunchGuard(LongSupplier clockMillis, long windowMillis) {
        this.clockMillis = clockMillis;
        this.windowMillis = windowMillis;
    }

    /** The process-wide instance used by the browser activities. */
    public static RelaunchGuard shared() {
        return SHARED;
    }

    /**
     * Asks to relaunch now. An allowed call starts a new window; a denied call does not extend it.
     *
     * @return true if the caller may relaunch, false if it must stay on its current layout
     */
    public synchronized boolean tryRelaunch() {
        long now = clockMillis.getAsLong();
        long elapsed = now - lastRelaunchMillis;
        // elapsed < 0: the clock went backwards, treat the window as over instead of blocking
        if (hasRelaunched && elapsed >= 0 && elapsed < windowMillis) {
            return false;
        }
        hasRelaunched = true;
        lastRelaunchMillis = now;
        return true;
    }
}
