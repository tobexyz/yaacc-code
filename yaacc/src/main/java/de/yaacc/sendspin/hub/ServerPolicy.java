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
package de.yaacc.sendspin.hub;

import de.yaacc.sendspin.session.ServerHandshake;

import java.util.HashSet;
import java.util.Set;

/**
 * What the server knows about the players it has met: which PSK to reference for a client (the long-term PSK of a paired client,
 * else the Sentinel) and which unpaired clients its operator approved. A player that is neither paired nor approved is never given
 * the {@code 'playback'} activity or a role (spec, "Unpaired Access").
 */
public interface ServerPolicy {

    /**
     * The PSK to reference for this player: its Pairing PSK if the operator pasted a token (the operator means to pair), else its
     * long-term PSK if it is paired, else the Sentinel.
     */
    ServerHandshake.PskChoice pskFor(String clientId);

    /** The Pairing PSK from a token the operator pasted for this player, or null. */
    byte[] pairingPsk(String clientId);

    boolean isPaired(String clientId);

    /**
     * Records a pairing the player has just finished: stores its new long-term PSK, drops the token and the approval (spec: an
     * approval is discarded on a successful pairing).
     *
     * @return false if the PSK is not fit to keep (the Sentinel, the pairing PSK, a wrong length) or there is no room
     */
    boolean completePairing(String clientId, byte[] longTermPsk);

    /** Forgets the pairing and any token of this player. */
    void unpair(String clientId);

    boolean isApproved(String clientId);

    void approve(String clientId);

    void revoke(String clientId);

    /** A policy that keeps its approvals in memory only (tests, and the start of the proof of concept). */
    class InMemory implements ServerPolicy {
        private final Set<String> approved = new HashSet<>();

        private final java.util.Map<String, byte[]> longTerm = new java.util.HashMap<>();
        private final java.util.Map<String, byte[]> tokens = new java.util.HashMap<>();

        @Override
        public synchronized ServerHandshake.PskChoice pskFor(String clientId) {
            if (tokens.containsKey(clientId)) {
                return new ServerHandshake.PskChoice(tokens.get(clientId), de.yaacc.sendspin.secure.SendspinPsk.Category.PAIRING);
            }
            if (longTerm.containsKey(clientId)) {
                return new ServerHandshake.PskChoice(longTerm.get(clientId), de.yaacc.sendspin.secure.SendspinPsk.Category.LONG_TERM);
            }
            return ServerHandshake.PskChoice.sentinel();
        }

        /** Test helper: the operator pasted a token. */
        public synchronized void addToken(String clientId, byte[] pairingPsk) {
            tokens.put(clientId, pairingPsk.clone());
        }

        @Override
        public synchronized byte[] pairingPsk(String clientId) {
            byte[] psk = tokens.get(clientId);
            return psk == null ? null : psk.clone();
        }

        @Override
        public synchronized boolean isPaired(String clientId) {
            return longTerm.containsKey(clientId);
        }

        @Override
        public synchronized boolean completePairing(String clientId, byte[] longTermPsk) {
            if (longTermPsk == null || longTermPsk.length != 32 || de.yaacc.sendspin.secure.SendspinPsk.isSentinel(longTermPsk)) {
                return false;
            }
            byte[] token = tokens.get(clientId);
            if (token != null && java.util.Arrays.equals(token, longTermPsk)) {
                return false;
            }
            longTerm.put(clientId, longTermPsk.clone());
            tokens.remove(clientId);
            approved.remove(clientId);
            return true;
        }

        @Override
        public synchronized void unpair(String clientId) {
            longTerm.remove(clientId);
            tokens.remove(clientId);
        }

        @Override
        public synchronized boolean isApproved(String clientId) {
            return approved.contains(clientId);
        }

        @Override
        public synchronized void approve(String clientId) {
            approved.add(clientId);
        }

        @Override
        public synchronized void revoke(String clientId) {
            approved.remove(clientId);
        }
    }
}
