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
    public enum Action { START, STOP, ASK_DURATION }

    private ShutdownTimerToggle() {
    }

    /**
     * @param running          a timer is counting down
     * @param configuredMillis the stored duration, 0 if none was set
     */
    public static Action decide(boolean running, long configuredMillis) {
        if (running) {
            return Action.STOP;
        }
        return configuredMillis > 0L ? Action.START : Action.ASK_DURATION;
    }
}
