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

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.PairingToken;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ServerHandshake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The credentials of the Pairing PSK flow that the server keeps: the pairing tokens the operator pasted (a token is used up by a
 * successful pairing: its player now has a long-term PSK with this server) and the long-term PSKs of paired players, each by
 * {@code client_id}. Both are credentials: they are never logged. They are kept in the app's private preferences (backup is off),
 * like the server's identity key; wrapping them with a key of the Android Keystore is still to do (spec, task 3.5). Pure.
 */
public final class SendspinPairings {

    private static final int ID_LENGTH = 43;
    private static final int PSK_LENGTH = 32;

    private final StringSetStore store;

    public SendspinPairings(StringSetStore store) {
        this.store = store;
    }

    // ---------------------------------------------------------------- entries

    private static String entry(String clientId, byte[] psk) {
        return clientId + ":" + Base64Url.encode(psk);
    }

    private static String idOf(String entry) {
        int colon = entry.indexOf(':');
        return colon != ID_LENGTH ? null : entry.substring(0, colon);
    }

    /** The PSK of a well-formed entry, else null. */
    private static byte[] pskOf(String entry) {
        int colon = entry.indexOf(':');
        if (colon != ID_LENGTH) {
            return null;
        }
        byte[] psk = Base64Url.decode(entry.substring(colon + 1));
        return psk != null && psk.length == PSK_LENGTH ? psk : null;
    }

    private byte[] find(String key, String clientId) {
        if (clientId == null) {
            return null;
        }
        for (String entry : store.get(key)) {
            if (clientId.equals(idOf(entry))) {
                byte[] psk = pskOf(entry);
                if (psk != null) {
                    return psk;
                }
            }
        }
        return null;
    }

    private void remove(String key, String clientId) {
        Set<String> all = store.get(key);
        Set<String> kept = new HashSet<>();
        for (String entry : all) {
            if (!clientId.equals(idOf(entry))) {
                kept.add(entry);
            }
        }
        if (kept.size() != all.size()) {
            store.put(key, kept);
        }
    }

    /** Puts the entry in, replacing one of the same player; false if the set is full. */
    private boolean put(String key, int max, String clientId, byte[] psk) {
        Set<String> kept = new HashSet<>();
        for (String entry : store.get(key)) {
            if (idOf(entry) != null && pskOf(entry) != null && !clientId.equals(idOf(entry))) {
                kept.add(entry); // damaged entries are dropped on the way
            }
        }
        if (kept.size() >= max) {
            return false;
        }
        kept.add(entry(clientId, psk));
        store.put(key, kept);
        return true;
    }

    // ---------------------------------------------------------------- tokens

    /** Keeps the token's PSK for its player. @return false if the list of tokens is full */
    public synchronized boolean addToken(PairingToken token) {
        byte[] psk = token.pairingPsk();
        try {
            return put(SendspinSettings.KEY_TOKENS, SendspinSettings.MAX_TOKENS, token.clientId(), psk);
        } finally {
            Arrays.fill(psk, (byte) 0);
        }
    }

    public synchronized byte[] pairingPsk(String clientId) {
        return find(SendspinSettings.KEY_TOKENS, clientId);
    }

    public synchronized void removeToken(String clientId) {
        remove(SendspinSettings.KEY_TOKENS, clientId);
    }

    // ---------------------------------------------------------------- pairings

    public synchronized boolean isPaired(String clientId) {
        return find(SendspinSettings.KEY_PAIRINGS, clientId) != null;
    }

    public synchronized List<String> pairedIds() {
        List<String> ids = new ArrayList<>();
        for (String entry : store.get(SendspinSettings.KEY_PAIRINGS)) {
            String id = idOf(entry);
            if (id != null && pskOf(entry) != null) {
                ids.add(id);
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /** @return false if the PSK is not fit to be kept as a long-term PSK, or there is no room for another player */
    public synchronized boolean completePairing(String clientId, byte[] longTermPsk) {
        if (clientId == null || clientId.length() != ID_LENGTH || clientId.indexOf(':') >= 0 || longTermPsk == null
                || longTermPsk.length != PSK_LENGTH || SendspinPsk.isSentinel(longTermPsk)) {
            return false;
        }
        byte[] pairingPsk = find(SendspinSettings.KEY_TOKENS, clientId);
        if (pairingPsk != null && Arrays.equals(pairingPsk, longTermPsk)) {
            return false; // the player must not hand back the shared secret as the new one
        }
        if (!put(SendspinSettings.KEY_PAIRINGS, SendspinSettings.MAX_PAIRINGS, clientId, longTermPsk)) {
            return false;
        }
        remove(SendspinSettings.KEY_TOKENS, clientId);
        return true;
    }

    public synchronized void unpair(String clientId) {
        if (clientId == null) {
            return;
        }
        remove(SendspinSettings.KEY_PAIRINGS, clientId);
        remove(SendspinSettings.KEY_TOKENS, clientId);
    }

    // ---------------------------------------------------------------- the choice for the handshake

    public synchronized ServerHandshake.PskChoice pskFor(String clientId) {
        byte[] pairing = find(SendspinSettings.KEY_TOKENS, clientId);
        if (pairing != null) {
            return choice(pairing, SendspinPsk.Category.PAIRING);
        }
        byte[] longTerm = find(SendspinSettings.KEY_PAIRINGS, clientId);
        if (longTerm != null) {
            return choice(longTerm, SendspinPsk.Category.LONG_TERM);
        }
        return ServerHandshake.PskChoice.sentinel();
    }

    private static ServerHandshake.PskChoice choice(byte[] psk, SendspinPsk.Category category) {
        ServerHandshake.PskChoice choice = new ServerHandshake.PskChoice(psk, category);
        Arrays.fill(psk, (byte) 0);
        return choice;
    }
}
