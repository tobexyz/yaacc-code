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

import java.util.Collection;

/**
 * Which Snapcast group row the hardware volume keys go to (11.4): the row that has the input focus (D-pad, keyboard), else the
 * row the user touched last, and only a row that is on screen. Nothing is guessed: with no row touched and no focus the keys
 * are left to the system, even when exactly one group is listed. Pure; the Android code reports the touches and the focus.
 * Thread safe.
 */
public final class SnapcastKeyFocus {

    private final Object lock = new Object();
    private String touchedId;

    /** The user did something to the row of this receiver (a tap, a drag, a button). */
    public void touched(String receiverId) {
        if (receiverId == null || receiverId.isEmpty()) {
            return;
        }
        synchronized (lock) {
            touchedId = receiverId;
        }
    }

    /** The host (the tab, the sheet) is left: the touched row is forgotten. */
    public void forget() {
        synchronized (lock) {
            touchedId = null;
        }
    }

    /**
     * @param visibleIds the receiver ids of the Snapcast rows that are on screen
     * @param focusedId  the receiver id of the row that has the input focus, or null
     * @return the receiver id the keys go to, or null
     */
    public String target(Collection<String> visibleIds, String focusedId) {
        if (focusedId != null && visibleIds.contains(focusedId)) {
            return focusedId;
        }
        synchronized (lock) {
            return touchedId != null && visibleIds.contains(touchedId) ? touchedId : null;
        }
    }
}
