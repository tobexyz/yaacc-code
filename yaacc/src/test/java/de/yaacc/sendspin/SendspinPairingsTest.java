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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.PairingToken;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ServerHandshake;

import org.junit.Test;

import java.util.Arrays;

/** The pairing tokens and the long-term PSKs the server holds, and the policy that chooses the PSK for a player. */
public class SendspinPairingsTest {

    private static byte[] bytes(int from) {
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) {
            b[i] = (byte) (from + i);
        }
        return b;
    }

    private static PairingToken tokenOf(int keySeed, int pskSeed) {
        return PairingToken.decode(PairingToken.encode(bytes(keySeed), bytes(pskSeed)));
    }

    private static String idOf(int keySeed) {
        return Base64Url.encode(bytes(keySeed));
    }

    @Test
    public void aPlayerWithoutAnyRecordIsReferencedWithTheSentinel() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        ServerHandshake.PskChoice choice = policy.pskFor(idOf(1));
        assertTrue(SendspinPsk.isSentinel(choice.psk()));
        assertEquals(SendspinPsk.Category.SENTINEL, choice.category());
        assertFalse(policy.isPaired(idOf(1)));
    }

    @Test
    public void aTokenMakesTheServerReferenceThePairingPskOfThatPlayer() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        assertTrue(policy.addToken(tokenOf(1, 100)));
        ServerHandshake.PskChoice choice = policy.pskFor(idOf(1));
        assertEquals(SendspinPsk.Category.PAIRING, choice.category());
        assertArrayEquals(bytes(100), choice.psk());
        assertArrayEquals(bytes(100), policy.pairingPsk(idOf(1)));
        assertEquals("other players are not affected", SendspinPsk.Category.SENTINEL, policy.pskFor(idOf(2)).category());
    }

    @Test
    public void completingAPairingStoresTheLongTermPskDropsTheTokenAndTheApproval() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        policy.addToken(tokenOf(1, 100));
        policy.approve(idOf(1));
        assertTrue(policy.completePairing(idOf(1), bytes(200)));
        assertTrue(policy.isPaired(idOf(1)));
        assertNull("a pairing PSK is not consumed on the player, but the server needs it no more", policy.pairingPsk(idOf(1)));
        assertFalse("an approval is discarded on a successful pairing (spec)", policy.isApproved(idOf(1)));
        ServerHandshake.PskChoice choice = policy.pskFor(idOf(1));
        assertEquals(SendspinPsk.Category.LONG_TERM, choice.category());
        assertArrayEquals(bytes(200), choice.psk());
    }

    @Test
    public void aNewTokenForAPairedPlayerWinsSoThatThePlayerCanBePairedAgain() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        policy.completePairing(idOf(1), bytes(200));
        policy.addToken(tokenOf(1, 101));
        assertEquals(SendspinPsk.Category.PAIRING, policy.pskFor(idOf(1)).category());
        assertTrue(policy.completePairing(idOf(1), bytes(201)));
        assertArrayEquals(bytes(201), policy.pskFor(idOf(1)).psk());
    }

    @Test
    public void unpairingForgetsTheLongTermPskAndTheToken() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        policy.completePairing(idOf(1), bytes(200));
        policy.addToken(tokenOf(1, 101));
        policy.unpair(idOf(1));
        assertFalse(policy.isPaired(idOf(1)));
        assertNull(policy.pairingPsk(idOf(1)));
        assertEquals(SendspinPsk.Category.SENTINEL, policy.pskFor(idOf(1)).category());
    }

    @Test
    public void thePolicyRefusesPskThatAreNotFitToBeLongTerm() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        assertFalse("the Sentinel is public", policy.completePairing(idOf(1), SendspinPsk.sentinel()));
        assertFalse("wrong length", policy.completePairing(idOf(1), new byte[31]));
        assertFalse("not a client id", policy.completePairing("short", bytes(5)));
        policy.addToken(tokenOf(1, 100));
        assertFalse("the pairing PSK itself is not a long-term PSK", policy.completePairing(idOf(1), bytes(100)));
        assertFalse(policy.isPaired(idOf(1)));
    }

    @Test
    public void recordsSurviveARestartAndAreNotStoredInTheClear() {
        SendspinStoreTest.MapStore store = new SendspinStoreTest.MapStore();
        SendspinPolicy first = new SendspinPolicy(store);
        first.completePairing(idOf(1), bytes(200));
        first.addToken(tokenOf(2, 100));
        SendspinPolicy second = new SendspinPolicy(store);
        assertTrue(second.isPaired(idOf(1)));
        assertArrayEquals(bytes(200), second.pskFor(idOf(1)).psk());
        assertArrayEquals(bytes(100), second.pairingPsk(idOf(2)));
        assertEquals(Arrays.asList(idOf(1)), second.pairedIds());
    }

    @Test
    public void damagedEntriesAreIgnored() {
        SendspinStoreTest.MapStore store = new SendspinStoreTest.MapStore();
        store.put(SendspinSettings.KEY_PAIRINGS, new java.util.HashSet<>(Arrays.asList("garbage", idOf(1) + ":short", ":::")));
        store.put(SendspinSettings.KEY_TOKENS, new java.util.HashSet<>(Arrays.asList("garbage", idOf(1) + ":short")));
        SendspinPolicy policy = new SendspinPolicy(store);
        assertFalse(policy.isPaired(idOf(1)));
        assertNull(policy.pairingPsk(idOf(1)));
        assertEquals(SendspinPsk.Category.SENTINEL, policy.pskFor(idOf(1)).category());
    }

    @Test
    public void theNumberOfPairingsAndTokensIsLimited() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        for (int i = 0; i < SendspinSettings.MAX_PAIRINGS; i++) {
            assertTrue(policy.completePairing(Base64Url.encode(bytes(i)), bytes(200)));
        }
        assertFalse(policy.completePairing(Base64Url.encode(bytes(SendspinSettings.MAX_PAIRINGS)), bytes(200)));
        assertTrue("an existing one may be replaced", policy.completePairing(Base64Url.encode(bytes(0)), bytes(201)));
        for (int i = 0; i < SendspinSettings.MAX_TOKENS; i++) {
            assertTrue(policy.addToken(tokenOf(i, 100)));
        }
        assertFalse(policy.addToken(tokenOf(SendspinSettings.MAX_TOKENS, 100)));
    }

    private static final String TOKEN_OF_1 = PairingToken.encode(bytes(1), bytes(100));

    @Test
    public void pastingATokenForTheConnectedPlayerStartsThePairing() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        assertEquals(SendspinPolicy.PairOutcome.STARTED, policy.pairWithToken(TOKEN_OF_1, idOf(1)));
        assertEquals(SendspinPsk.Category.PAIRING, policy.pskFor(idOf(1)).category());
    }

    @Test
    public void aTokenThatIsNotOneOrBelongsToAnotherPlayerIsRefusedAndNothingIsStored() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        assertEquals(SendspinPolicy.PairOutcome.NOT_A_TOKEN, policy.pairWithToken("hello", idOf(1)));
        assertEquals(SendspinPolicy.PairOutcome.NOT_A_TOKEN, policy.pairWithToken("", idOf(1)));
        assertEquals(SendspinPolicy.PairOutcome.NOT_A_TOKEN, policy.pairWithToken(null, idOf(1)));
        assertEquals("the spec: the token's key must match the id of the connection",
                SendspinPolicy.PairOutcome.WRONG_PLAYER, policy.pairWithToken(TOKEN_OF_1, idOf(2)));
        assertEquals(SendspinPolicy.PairOutcome.NOT_KNOWN, policy.pairWithToken(TOKEN_OF_1, null));
        assertEquals(SendspinPsk.Category.SENTINEL, policy.pskFor(idOf(1)).category());
        assertNull(policy.pairingPsk(idOf(1)));
        assertNull(policy.pairingPsk(idOf(2)));
    }

    @Test
    public void aFullTokenListIsReportedAndApprovalsAreListed() {
        SendspinPolicy policy = new SendspinPolicy(new SendspinStoreTest.MapStore());
        for (int i = 0; i < SendspinSettings.MAX_TOKENS; i++) {
            assertEquals(SendspinPolicy.PairOutcome.STARTED,
                    policy.pairWithToken(PairingToken.encode(bytes(i), bytes(100)), Base64Url.encode(bytes(i))));
        }
        assertEquals(SendspinPolicy.PairOutcome.NO_ROOM, policy.pairWithToken(
                PairingToken.encode(bytes(50), bytes(100)), Base64Url.encode(bytes(50))));
        policy.approve(idOf(3));
        policy.approve(idOf(1));
        assertEquals(Arrays.asList(idOf(1), idOf(3)).size(), policy.approvedIds().size());
        assertTrue(policy.approvedIds().contains(idOf(3)));
    }
}
