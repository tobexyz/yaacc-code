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

/**
 * Decides, when the network is back, whether the content list reloads its folder: only when the
 * last load of that folder failed and none is running, and not twice within
 * {@link #DEBOUNCE_MILLIS} (the system reports a new network in bursts). No Android classes:
 * unit-testable on the JVM.
 */
final class NetworkRecoveryReloadGate {

    /** Callbacks closer than this to the last counted one are ignored. */
    static final long DEBOUNCE_MILLIS = 2000L;

    private boolean counted;
    private long lastCountedAtMillis;

    /**
     * @param loadFailed the last load of the shown folder failed
     * @param loading    a load is running right now (its own retries may still succeed)
     * @param nowMillis  a monotonic clock reading
     * @return true if the folder should be reloaded now
     */
    synchronized boolean shouldReload(boolean loadFailed, boolean loading, long nowMillis) {
        if (counted && nowMillis - lastCountedAtMillis < DEBOUNCE_MILLIS) {
            return false;
        }
        counted = true;
        lastCountedAtMillis = nowMillis;
        return loadFailed && !loading;
    }
}
