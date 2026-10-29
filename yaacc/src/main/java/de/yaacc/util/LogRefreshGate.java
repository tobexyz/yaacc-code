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

/**
 * When the log viewer may change its text. Reading the log while the user touches or flings the view feeds
 * itself: the scrolling makes the system write lines about the app's own scroll handling, those lines
 * change the text, and the view jumped while the finger was still on it. The text is therefore only
 * changed when the user has not touched or scrolled the view for a moment.
 */
public final class LogRefreshGate {

    /** How long after the last touch or scroll the view counts as at rest. */
    public static final long IDLE_MILLIS = 1500L;

    private LogRefreshGate() {
    }

    /**
     * @param nowMillis             the current time (a monotonic clock)
     * @param lastInteractionMillis when the user last touched or scrolled the view, or a negative value if never
     */
    public static boolean mayChangeText(long nowMillis, long lastInteractionMillis) {
        return lastInteractionMillis < 0 || nowMillis - lastInteractionMillis >= IDLE_MILLIS;
    }
}
