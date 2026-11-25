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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.PairingToken;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SendspinClientPairingsTest {

    private static final class MapStore implements StringSetStore {
        final Map<String, Set<String>> map = new HashMap<>();

        @Override
        public Set<String> get(String key) {
            Set<String> found = map.get(key);
            return found == null ? new HashSet<String>() : new HashSet<>(found);
        }

        @Override
        public void put(String key, Set<String> values) {
            map.put(key, new HashSet<>(values));
        }
    }

    private final MapStore store = new MapStore();
    private final SendspinClientPairings pairings = new SendspinClientPairings(store, new SecureRandom());
    private final Identity identity = Identity.generate(new SecureRandom());

    private static String serverId(int n) {
        return String.format("%043d", n);
    }

    private static byte[] psk(int fill) {
        byte[] psk = new byte[32];
        java.util.Arrays.fill(psk, (byte) fill);
        return psk;
    }

    @Test
    public void theTokenCarriesTheKeyOfThisClientAndAPairingPskThatStaysTheSameUntilItIsUsedUp() {
        assertFalse(pairings.holdsPairingPsk());
        String token = pairings.token(identity);
        assertTrue(pairings.holdsPairingPsk());
        PairingToken decoded = PairingToken.decode(token);
        assertNotNull(decoded);
        assertEquals(identity.id(), decoded.clientId());
        assertEquals("the same token again", token, pairings.token(identity));
        assertNotNull("the handshake finds the Pairing PSK by its id",
                pairings.find(SendspinPsk.pskId(decoded.pairingPsk()), SendspinPsk.Category.PAIRING));
        assertNull(pairings.find(SendspinPsk.pskId(psk(7)), SendspinPsk.Category.PAIRING));
    }

    @Test
    public void aCompletedPairingKeepsTheLongTermPskForThatServerAndUsesUpTheToken() {
        String token = pairings.token(identity);
        assertTrue(pairings.completePairing(serverId(1), psk(9)));
        assertFalse("the token is used up", pairings.holdsPairingPsk());
        assertNull(pairings.find(SendspinPsk.pskId(PairingToken.decode(token).pairingPsk()), SendspinPsk.Category.PAIRING));
        assertTrue(pairings.isPairedWith(serverId(1)));
        assertFalse(pairings.isPairedWith(serverId(2)));
        assertNotNull(pairings.find(SendspinPsk.pskId(psk(9)), SendspinPsk.Category.LONG_TERM));
        assertEquals("a long-term PSK is not found as a pairing PSK", null,
                pairings.find(SendspinPsk.pskId(psk(9)), SendspinPsk.Category.PAIRING));
        assertNull(pairings.find(SendspinPsk.pskId(psk(9)), SendspinPsk.Category.SENTINEL));
    }

    @Test
    public void theSentinelAndThePairingPskAreRefusedAsLongTermPsk() {
        PairingToken decoded = PairingToken.decode(pairings.token(identity));
        assertFalse(pairings.completePairing(serverId(1), SendspinPsk.sentinel()));
        assertFalse(pairings.completePairing(serverId(1), decoded.pairingPsk()));
        assertFalse(pairings.completePairing("short", psk(3)));
        assertFalse(pairings.completePairing(serverId(1), new byte[16]));
        assertTrue("nothing was kept, the token stays", pairings.holdsPairingPsk());
        assertTrue(pairings.pairedServerIds().isEmpty());
    }

    @Test
    public void pairingAgainWithTheSameServerReplacesTheRecordAndAtMostEightAreKept() {
        pairings.completePairing(serverId(1), psk(1));
        pairings.completePairing(serverId(1), psk(2));
        assertNull("the old long-term PSK is gone", pairings.find(SendspinPsk.pskId(psk(1)), SendspinPsk.Category.LONG_TERM));
        assertNotNull(pairings.find(SendspinPsk.pskId(psk(2)), SendspinPsk.Category.LONG_TERM));
        for (int i = 2; i <= SendspinClientPairings.MAX_RECORDS + 3; i++) {
            assertTrue(pairings.completePairing(serverId(i), psk(10 + i)));
        }
        assertEquals(SendspinClientPairings.MAX_RECORDS, pairings.pairedServerIds().size());
        assertFalse("the oldest was dropped for the newer ones", pairings.isPairedWith(serverId(1)));
        assertTrue(pairings.isPairedWith(serverId(SendspinClientPairings.MAX_RECORDS + 3)));
    }

    @Test
    public void unpairingForgetsOneServerAndWithdrawingTheTokenForgetsTheToken() {
        pairings.completePairing(serverId(1), psk(1));
        pairings.completePairing(serverId(2), psk(2));
        pairings.unpair(serverId(1));
        assertFalse(pairings.isPairedWith(serverId(1)));
        assertTrue(pairings.isPairedWith(serverId(2)));
        pairings.token(identity);
        pairings.withdrawToken();
        assertFalse(pairings.holdsPairingPsk());
    }

    @Test
    public void anEntryThatEndsRightAfterTheServerIdDoesNotCrashTheRead() {
        store.put(SendspinSettings.KEY_CLIENT_PAIRINGS, new HashSet<>(java.util.Arrays.asList("0:" + serverId(5))));
        assertTrue(pairings.pairedServerIds().isEmpty());
        assertFalse(pairings.isPairedWith(serverId(5)));
        assertNull(pairings.find(SendspinPsk.pskId(psk(1)), SendspinPsk.Category.LONG_TERM));
    }

    @Test
    public void damagedEntriesAreIgnored() {
        store.put(SendspinSettings.KEY_CLIENT_PAIRINGS, new HashSet<>(java.util.Arrays.asList("x", "1:short:" + Base64Url.encode(psk(1)),
                "2:" + serverId(5) + ":AAAA")));
        assertTrue(pairings.pairedServerIds().isEmpty());
        assertNull(pairings.find(SendspinPsk.pskId(psk(1)), SendspinPsk.Category.LONG_TERM));
    }
}
