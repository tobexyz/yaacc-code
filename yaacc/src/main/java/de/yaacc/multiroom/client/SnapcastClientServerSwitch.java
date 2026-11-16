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

import de.yaacc.multiroom.ui.SnapcastClientServerChoice;

/**
 * What choosing another server for the phone's client does (task 12.10), without Android. Off: it only changes the next start. On with
 * a session on another server: the session ends and starts again on the new one, once, with the state staying on. The same server:
 * nothing is restarted. On without a session (it gave up, a start was refused): the choice starts the client on the new server. Also
 * the bounded wait between the end of the old session and the new start. Pure.
 */
public final class SnapcastClientServerSwitch {

    /** What to do with the chosen server. */
    public enum Action {
        /** Remember it for the next start; nothing else. */
        STORE_ONLY,
        /** End the session, wait until it is gone, start it on the chosen server (one start). */
        RESTART,
        /** The client is on but not connected: start it on the chosen server now. */
        START
    }

    /** One step of the wait for the end of the old session. */
    public enum Wait {
        WAIT, START, GIVE_UP
    }

    private SnapcastClientServerSwitch() {
    }

    /**
     * @param switchedOn     the stored state of the client
     * @param sessionActive  a session starts, runs or reconnects
     * @param restartPending an earlier choice is still waiting for the end of the old session
     * @param currentHost    the server the client uses now (null or empty when none)
     * @param chosenHost     the server the user chose
     */
    public static Action decide(boolean switchedOn, boolean sessionActive, boolean restartPending, String currentHost, String chosenHost) {
        if (!switchedOn) {
            return Action.STORE_ONLY;
        }
        if (!sessionActive && !restartPending) {
            return Action.START;
        }
        return SnapcastClientServerChoice.sameServer(currentHost, chosenHost) ? Action.STORE_ONLY : Action.RESTART;
    }

    /**
     * @param sessionGone the session and the service instance of the old session are gone
     * @param waitedMs    how long the restart has waited
     * @param maxMs       the longest wait: a teardown that never ends must not hold the choice for ever
     */
    public static Wait waitStep(boolean sessionGone, long waitedMs, long maxMs) {
        if (sessionGone) {
            return Wait.START;
        }
        return waitedMs >= maxMs ? Wait.GIVE_UP : Wait.WAIT;
    }
}
