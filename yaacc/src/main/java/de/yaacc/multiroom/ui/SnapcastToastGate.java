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
package de.yaacc.multiroom.ui;

import de.yaacc.multiroom.control.MonotonicClock;

/**
 * Lets at most one message through per gap. A slider that is dragged against a server that does not answer makes one
 * failure per coalesced value; the user needs to hear of it once, not twenty times. Thread safe.
 */
public final class SnapcastToastGate {

    private final MonotonicClock clock;
    private final long gapMillis;
    private long lastShown;
    private boolean shownBefore;

    public SnapcastToastGate(MonotonicClock clock, long gapMillis) {
        this.clock = clock;
        this.gapMillis = gapMillis;
    }

    /** @return true if a message may be shown now (and counts as shown) */
    public synchronized boolean allow() {
        long now = clock.nowMillis();
        if (shownBefore && now - lastShown < gapMillis) {
            return false;
        }
        shownBefore = true;
        lastShown = now;
        return true;
    }
}
