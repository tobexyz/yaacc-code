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

import de.yaacc.sendspin.client.ClientCredentials;
import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.PairingToken;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ClientHandshake;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The credentials of this app as a Sendspin <b>client</b> (the controller): the one Pairing PSK behind the token it shows (made when the
 * user asks for the token, used up by the pairing it leads to) and the long-term PSKs of the servers it is paired with, by the id of
 * the server, at most {@link #MAX_RECORDS} (the oldest is dropped for a new one; the specification asks for room for at least five).
 * A record is {@code <sequence>:<server id>:<PSK>}. Credentials: never logged; kept in the app's private preferences like the other
 * keys (the Keystore wrapping is still to do). Pure.
 */
public final class SendspinClientPairings implements ClientCredentials {

    public static final int MAX_RECORDS = 8;
    private static final int ID_LENGTH = 43;
    private static final int PSK_LENGTH = 32;

    private final StringSetStore store;
    private final SecureRandom random;

    public SendspinClientPairings(StringSetStore store, SecureRandom random) {
        this.store = store;
        this.random = random;
    }

    // ------------------------------------------------------------------ the Pairing PSK

    private synchronized byte[] pairingPsk() {
        for (String entry : store.get(SendspinSettings.KEY_CLIENT_PAIRING_PSK)) {
            byte[] psk = Base64Url.decode(entry);
            if (psk != null && psk.length == PSK_LENGTH) {
                return psk;
            }
        }
        return null;
    }

    @Override
    public synchronized boolean holdsPairingPsk() {
        byte[] psk = pairingPsk();
        if (psk != null) {
            Arrays.fill(psk, (byte) 0);
            return true;
        }
        return false;
    }

    /** The token of this client for the user to give to a server; makes the Pairing PSK if there is none yet. */
    public synchronized String token(Identity identity) {
        byte[] psk = pairingPsk();
        if (psk == null) {
            psk = new byte[PSK_LENGTH];
            random.nextBytes(psk);
            store.put(SendspinSettings.KEY_CLIENT_PAIRING_PSK, Collections.singleton(Base64Url.encode(psk)));
        }
        try {
            return PairingToken.encode(identity.publicKey(), psk);
        } finally {
            Arrays.fill(psk, (byte) 0);
        }
    }

    /** Drops the Pairing PSK: the token that was shown no longer works. */
    public synchronized void withdrawToken() {
        store.put(SendspinSettings.KEY_CLIENT_PAIRING_PSK, new HashSet<String>());
    }

    // ------------------------------------------------------------------ the records

    private static long sequenceOf(String entry) {
        int colon = entry.indexOf(':');
        try {
            return colon <= 0 ? -1 : Long.parseLong(entry.substring(0, colon));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String serverOf(String entry) {
        int first = entry.indexOf(':');
        return first < 0 || entry.length() < first + 1 + ID_LENGTH || entry.charAt(first + 1 + ID_LENGTH) != ':' ? null
                : entry.substring(first + 1, first + 1 + ID_LENGTH);
    }

    private static byte[] pskOf(String entry) {
        String server = serverOf(entry);
        if (server == null) {
            return null;
        }
        byte[] psk = Base64Url.decode(entry.substring(entry.indexOf(':') + 1 + ID_LENGTH + 1));
        return psk != null && psk.length == PSK_LENGTH ? psk : null;
    }

    @Override
    public synchronized boolean completePairing(String serverId, byte[] longTermPsk) {
        if (serverId == null || serverId.length() != ID_LENGTH || serverId.indexOf(':') >= 0 || longTermPsk == null
                || longTermPsk.length != PSK_LENGTH || SendspinPsk.isSentinel(longTermPsk)) {
            return false;
        }
        byte[] pairing = pairingPsk();
        if (pairing != null && Arrays.equals(pairing, longTermPsk)) {
            Arrays.fill(pairing, (byte) 0);
            return false; // the shared secret is not handed back as the long-term one
        }
        long next = 0;
        Set<String> kept = new HashSet<>();
        for (String entry : store.get(SendspinSettings.KEY_CLIENT_PAIRINGS)) {
            if (serverOf(entry) == null || pskOf(entry) == null) {
                continue; // damaged entries are dropped on the way
            }
            next = Math.max(next, sequenceOf(entry) + 1);
            if (!serverId.equals(serverOf(entry))) {
                kept.add(entry);
            }
        }
        kept.add(next + ":" + serverId + ":" + Base64Url.encode(longTermPsk));
        while (kept.size() > MAX_RECORDS) {
            String oldest = null;
            for (String entry : kept) {
                if (oldest == null || sequenceOf(entry) < sequenceOf(oldest)) {
                    oldest = entry;
                }
            }
            kept.remove(oldest);
        }
        store.put(SendspinSettings.KEY_CLIENT_PAIRINGS, kept);
        store.put(SendspinSettings.KEY_CLIENT_PAIRING_PSK, new HashSet<String>()); // the token is used up
        if (pairing != null) {
            Arrays.fill(pairing, (byte) 0);
        }
        return true;
    }

    public synchronized boolean isPairedWith(String serverId) {
        for (String entry : store.get(SendspinSettings.KEY_CLIENT_PAIRINGS)) {
            if (serverId != null && serverId.equals(serverOf(entry)) && pskOf(entry) != null) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<String> pairedServerIds() {
        List<String> ids = new ArrayList<>();
        for (String entry : store.get(SendspinSettings.KEY_CLIENT_PAIRINGS)) {
            if (serverOf(entry) != null && pskOf(entry) != null) {
                ids.add(serverOf(entry));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    public synchronized void unpair(String serverId) {
        Set<String> kept = new HashSet<>();
        for (String entry : store.get(SendspinSettings.KEY_CLIENT_PAIRINGS)) {
            if (serverId == null || !serverId.equals(serverOf(entry))) {
                kept.add(entry);
            }
        }
        store.put(SendspinSettings.KEY_CLIENT_PAIRINGS, kept);
    }

    // ------------------------------------------------------------------ what the handshake asks for

    @Override
    public synchronized ClientHandshake.Held find(String pskId, SendspinPsk.Category category) {
        if (pskId == null) {
            return null;
        }
        if (category == SendspinPsk.Category.PAIRING) {
            byte[] psk = pairingPsk();
            if (psk != null && pskId.equals(SendspinPsk.pskId(psk))) {
                ClientHandshake.Held held = new ClientHandshake.Held(psk, null);
                Arrays.fill(psk, (byte) 0);
                return held;
            }
            return null;
        }
        if (category == SendspinPsk.Category.LONG_TERM) {
            for (String entry : store.get(SendspinSettings.KEY_CLIENT_PAIRINGS)) {
                byte[] psk = pskOf(entry);
                if (psk != null && pskId.equals(SendspinPsk.pskId(psk))) {
                    ClientHandshake.Held held = new ClientHandshake.Held(psk, serverOf(entry));
                    Arrays.fill(psk, (byte) 0);
                    return held;
                }
            }
        }
        return null;
    }
}
