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
package de.yaacc.sendspin;

import de.yaacc.sendspin.hub.ServerPolicy;
import de.yaacc.sendspin.secure.PairingToken;
import de.yaacc.sendspin.session.ServerHandshake;

import java.util.List;

/** What the server knows about players: the approvals of unpaired ones and the pairing credentials, over one store. */
public final class SendspinPolicy implements ServerPolicy {

    private final SendspinApprovals approvals;
    private final SendspinPairings pairings;

    public SendspinPolicy(StringSetStore store) {
        this.approvals = new SendspinApprovals(store);
        this.pairings = new SendspinPairings(store);
    }

    @Override
    public ServerHandshake.PskChoice pskFor(String clientId) {
        return pairings.pskFor(clientId);
    }

    @Override
    public byte[] pairingPsk(String clientId) {
        return pairings.pairingPsk(clientId);
    }

    @Override
    public boolean isPaired(String clientId) {
        return pairings.isPaired(clientId);
    }

    @Override
    public synchronized boolean completePairing(String clientId, byte[] longTermPsk) {
        if (!pairings.completePairing(clientId, longTermPsk)) {
            return false;
        }
        approvals.revoke(clientId);
        return true;
    }

    @Override
    public void unpair(String clientId) {
        pairings.unpair(clientId);
    }

    @Override
    public boolean isApproved(String clientId) {
        return approvals.isApproved(clientId);
    }

    @Override
    public void approve(String clientId) {
        approvals.approve(clientId);
    }

    @Override
    public void revoke(String clientId) {
        approvals.revoke(clientId);
    }

    /** The operator pastes a token: the next connection to its player references the Pairing PSK. @return false if there is no room */
    public boolean addToken(PairingToken token) {
        return pairings.addToken(token);
    }

    public void removeToken(String clientId) {
        pairings.removeToken(clientId);
    }

    public List<String> pairedIds() {
        return pairings.pairedIds();
    }

    public List<String> approvedIds() {
        return approvals.ids();
    }

    /** What pasting a token did. */
    public enum PairOutcome {
        /** The token is kept; the next connection to the player references its Pairing PSK and pairs. */
        STARTED,
        NOT_A_TOKEN,
        /** The token's key is not the id of the player of this connection (spec: the server MUST check this before pairing). */
        WRONG_PLAYER,
        /** The id of the player is not known yet (not connected). */
        NOT_KNOWN,
        NO_ROOM
    }

    /**
     * The operator pastes a token for a player.
     *
     * @param connectedClientId the id the player presented on its connection, null if there is none yet
     */
    public PairOutcome pairWithToken(String text, String connectedClientId) {
        PairingToken token = PairingToken.decode(text);
        if (token == null) {
            return PairOutcome.NOT_A_TOKEN;
        }
        if (connectedClientId == null) {
            return PairOutcome.NOT_KNOWN;
        }
        if (!connectedClientId.equals(token.clientId())) {
            return PairOutcome.WRONG_PLAYER;
        }
        return pairings.addToken(token) ? PairOutcome.STARTED : PairOutcome.NO_ROOM;
    }
}
