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

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * What may be removed from a Snapcast server with {@code Server.DeleteClient} (task 12.8). Pure.
 *
 * <p>The server keeps an entry for every client it ever saw, also for one that is gone for good (a phone that was reinstalled, a
 * client that reached the server under another address and so under another id), so such an <b>offline</b> entry stays in
 * its group for ever. Removing it only forgets the entry; a client that is still around comes back by itself the next time it
 * connects. The rules, in this order:
 * <ul>
 *   <li>the id must be in the <b>fresh</b> status the caller read right before ({@link Verdict#NOT_FOUND} otherwise: it is
 *   gone already);</li>
 *   <li>while a session of this phone's own client runs, or starts and its id is not known yet, the phone's own client is never
 *   removed, also not when it looks offline ({@link Verdict#OWN_CLIENT}, {@link Verdict#OWN_CLIENT_UNKNOWN}); the protection
 *   ends with the session. The own id is the one id the phone gives every server (task 12.11), known without a session, so an
 *   <b>offline</b> entry of this install is a ghost: it may be removed while no session runs (it returns by itself with the next
 *   session) and never while one does;</li>
 *   <li>a client that the fresh status shows as connected is never removed ({@link Verdict#CONNECTED}). This is our rule: the
 *   notes of the server say that the call only forgets the configuration of a disconnected client, but a server that refused
 *   would only tell us after the fact, and an older one may not.</li>
 * </ul>
 * Ids are never part of a text; {@link Own#toString} and the verdicts carry none.
 */
public final class SnapcastClientRemoval {

    private SnapcastClientRemoval() {
    }

    /** What the decision says. */
    public enum Verdict {
        /** The fresh status has the client and it is offline: it may be removed. */
        REMOVABLE,
        /** The client is not in the fresh status (any more). */
        NOT_FOUND,
        /** The client is connected. */
        CONNECTED,
        /** The client is the one of this phone's running session. */
        OWN_CLIENT,
        /** A session of this phone is starting and its client id is not known yet: no id can be told apart from it. */
        OWN_CLIENT_UNKNOWN
    }

    /** The client of this phone's own session, if one runs. Immutable. */
    public static final class Own {
        private static final Own NONE = new Own(false, null);

        private final boolean sessionActive;
        private final String clientId;

        private Own(boolean sessionActive, String clientId) {
            this.sessionActive = sessionActive;
            this.clientId = clientId;
        }

        /** No session of this phone's client runs: nothing is protected. */
        public static Own none() {
            return NONE;
        }

        /**
         * A session runs or starts.
         *
         * @param clientId the id of its client in its Hello; null while it is not known yet
         */
        public static Own session(String clientId) {
            return new Own(true, clientId == null || clientId.isEmpty() ? null : clientId);
        }

        /** Never the id. */
        @Override
        public String toString() {
            return sessionActive ? "Own{session}" : "Own{none}";
        }
    }

    /**
     * The phone's own session as the app's runtime reports it.
     *
     * @param sessionActive a session runs or starts
     * @param clientId      the id of its Hello, null while it is not known
     */
    public static Own ownOf(boolean sessionActive, String clientId) {
        return sessionActive ? Own.session(clientId) : Own.none();
    }

    /** Where the app says whether its own session runs and with which id. */
    public interface OwnSource {
        Own own();
    }

    /**
     * @param fresh    the status read from the server right before; null counts as "not found"
     * @param clientId the exact id from that status
     * @param own      the phone's own session, from the app
     */
    public static Verdict decide(SnapcastServerStatus fresh, String clientId, Own own) {
        if (fresh == null || clientId == null || clientId.isEmpty()) {
            return Verdict.NOT_FOUND;
        }
        SnapcastClient client = fresh.findClient(clientId);
        if (client == null) {
            return Verdict.NOT_FOUND;
        }
        Own current = own == null ? Own.none() : own;
        if (current.sessionActive) {
            if (current.clientId == null) {
                return Verdict.OWN_CLIENT_UNKNOWN;
            }
            if (current.clientId.equals(clientId)) {
                return Verdict.OWN_CLIENT;
            }
        }
        return client.isConnected() ? Verdict.CONNECTED : Verdict.REMOVABLE;
    }

    /**
     * Whether a row offers "Remove from server": an offline client that is not the phone's own. The row is drawn from the model,
     * which may be behind; the call decides again with a fresh status.
     */
    public static boolean offered(boolean connected, String clientId, Own own) {
        if (connected || clientId == null || clientId.isEmpty()) {
            return false;
        }
        Own current = own == null ? Own.none() : own;
        if (current.sessionActive) {
            return current.clientId != null && !current.clientId.equals(clientId);
        }
        return true;
    }
}
