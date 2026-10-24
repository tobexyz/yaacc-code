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
 * What a tap on the toolbar shutdown-timer item does. Plain Java so the rule is testable.
 * <p>
 * Starting a timer with a duration of 0 would exit the app at once, so without a configured
 * duration the tap asks for one instead.
 */
public final class ShutdownTimerToggle {

    /** The step to take. */
    public enum Action { STOP, ASK_DURATION }

    private ShutdownTimerToggle() {
    }

    /**
     * One toolbar icon does it all: a running timer is stopped by a tap, otherwise the duration
     * picker opens and confirming it starts the timer. Starting straight from a stored duration
     * is not offered, so a tap can never start (or, with 0, immediately fire) a timer unseen.
     *
     * @param running a timer is counting down
     */
    public static Action decide(boolean running) {
        return running ? Action.STOP : Action.ASK_DURATION;
    }
}
