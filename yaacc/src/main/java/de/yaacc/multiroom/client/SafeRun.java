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
package de.yaacc.multiroom.client;

/**
 * Runs a step of the service that must never take the process down (the watchdog and the power tick run on the main
 * thread): whatever it throws ends the step and calls the handler, which ends the session with a typed ending. Pure.
 */
public final class SafeRun {

    private SafeRun() {
    }

    /**
     * @param onFailure what to do when {@code body} threw (it may throw itself; that is swallowed too)
     * @return true when {@code body} ran to its end
     */
    public static boolean run(Runnable body, Runnable onFailure) {
        try {
            body.run();
            return true;
        } catch (Throwable failure) {
            try {
                onFailure.run();
            } catch (Throwable ignored) {
                // nothing more can be done; the process must not end because of a lock call
            }
            return false;
        }
    }
}
