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
package de.yaacc.sendspin.client;

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.SecureChannel;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The Pairing PSK flow, client side, shared by the controller session and the player session (issue #325): on the server's pairing
 * activation the client sends {@code client/pair-init} and then {@code client/pair-finalize} with a long-term PSK of its own; on
 * {@code server/pair-finalize} it keeps that PSK for the server. The pending key is zeroed as soon as it is used or given up. Used from
 * the connection's thread only.
 */
final class ClientPairing {

    private final ClientCredentials credentials;
    private final SecureRandom random;
    private int activations;
    private byte[] pending;

    ClientPairing(ClientCredentials credentials, SecureRandom random) {
        this.credentials = credentials;
        this.random = random;
    }

    /** A pairing activation of the server: {@code client/pair-init}, then {@code client/pair-finalize} with a long-term PSK of our own. */
    void start(SecureChannel c) throws IOException {
        activations++;
        clear();
        byte[] longTerm = new byte[32];
        do {
            random.nextBytes(longTerm);
        } while (SendspinPsk.isSentinel(longTerm));
        pending = longTerm;
        c.sendJson(ControllerMessages.clientPairInit(activations));
        c.sendJson(ControllerMessages.clientPairFinalize(Base64Url.encode(longTerm)));
    }

    /** {@code server/pair-finalize}: the server stored the long-term PSK; so does this client (the re-handshake follows). */
    void finish(SecureChannel c, String serverId) throws IOException {
        byte[] longTerm = pending;
        pending = null;
        if (longTerm == null || credentials == null || serverId == null) {
            return; // not a pairing of ours: ignored
        }
        boolean kept = credentials.completePairing(serverId, longTerm);
        Arrays.fill(longTerm, (byte) 0);
        if (!kept) {
            c.sendJson(ControllerMessages.pairAbort("failed"));
        }
    }

    /** The server gave the attempt up (or the connection ends): the pending key is zeroed. */
    void clear() {
        if (pending != null) {
            Arrays.fill(pending, (byte) 0);
            pending = null;
        }
    }

    /** A pairing activation of the server that a code-based flow answers: the next pairing index (the count is shared with the PSK flow). */
    int nextActivation() {
        return ++activations;
    }

    /** A new handshake starts the count of the activations again. */
    void resetCount() {
        activations = 0;
    }

    /** True while a pairing is under way on this connection. */
    boolean isPending() {
        return pending != null;
    }
}
