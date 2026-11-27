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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class CodePairingMessagesTest {

    private static JSONObject payload(String message) throws Exception {
        return new JSONObject(message).getJSONObject("payload");
    }

    private static String type(String message) throws Exception {
        return new JSONObject(message).getString("type");
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    @Test
    public void theActivationNamesTheMethodAndTheFormat() throws Exception {
        String dynamic = CodePairingMessages.serverActivate("dynamic_pairing_code", "digits");
        assertEquals("server/activate", type(dynamic));
        JSONObject pairing = payload(dynamic).getJSONObject("pairing");
        assertEquals("dynamic_pairing_code", pairing.getString("method"));
        assertEquals("digits", pairing.getString("format"));
        assertEquals("pairing", payload(dynamic).getJSONArray("activities").getString(0));
        assertEquals(0, payload(dynamic).getJSONArray("active_roles").length());
        JSONObject staticPairing = payload(CodePairingMessages.serverActivate("static_pairing_code", null)).getJSONObject("pairing");
        assertEquals("static_pairing_code", staticPairing.getString("method"));
        assertFalse(staticPairing.has("format"));
    }

    @Test
    public void thePairInitCarriesTheIndexAndTheCommitmentOfTheDynamicFlow() throws Exception {
        byte[] commit = bytes(32, 1);
        String m = CodePairingMessages.clientPairInit(2, commit);
        CodePairingMessages.PairInit init = CodePairingMessages.parsePairInit(payload(m));
        assertEquals(2, init.pairingIndex);
        assertArrayEquals(commit, init.commit);
        CodePairingMessages.PairInit plain = CodePairingMessages.parsePairInit(payload(CodePairingMessages.clientPairInit(1, null)));
        assertNull(plain.commit);
    }

    @Test
    public void aPendingMessageHasTheIndexAndALimitedText() throws Exception {
        CodePairingMessages.Pending p = CodePairingMessages.parsePending(payload(CodePairingMessages.clientPairPending(1, "Press the button")));
        assertEquals(1, p.pairingIndex);
        assertEquals("Press the button", p.message);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('x');
        }
        assertTrue("clipped by the sender to 200 bytes", payload(CodePairingMessages.clientPairPending(1, longText.toString())).getString("message").length() <= 200);
        try {
            CodePairingMessages.parsePending(new JSONObject("{\"pairing_index\":1,\"message\":\"" + longText + "\"}"));
            fail("a message over 200 bytes is refused");
        } catch (ProtocolException expected) {
            // refused
        }
    }

    @Test
    public void theNonceOfTheServerIsOnlyInTheFirstRound() throws Exception {
        byte[] nonce = bytes(32, 7);
        assertArrayEquals(nonce, CodePairingMessages.parseServerPairInit(payload(CodePairingMessages.serverPairInit(nonce))));
        assertNull(CodePairingMessages.parseServerPairInit(payload(CodePairingMessages.serverPairInit(null))));
    }

    @Test
    public void theSharesAndTagsHaveTheLengthsOfTheSpecification() throws Exception {
        byte[] share = bytes(32, 3);
        byte[] tag = bytes(64, 9);
        assertEquals(43, payload(CodePairingMessages.serverPairAuth(share)).getString("pake_msg_1").length());
        assertArrayEquals(share, CodePairingMessages.parseShare(payload(CodePairingMessages.serverPairAuth(share)), "pake_msg_1"));
        assertArrayEquals(share, CodePairingMessages.parseShare(payload(CodePairingMessages.clientPairAuth(share)), "pake_msg_2"));
        assertEquals(86, payload(CodePairingMessages.serverPairConfirm(tag)).getString("server_kc").length());
        assertArrayEquals(tag, CodePairingMessages.parseTag(payload(CodePairingMessages.serverPairConfirm(tag)), "server_kc"));
        CodePairingMessages.ClientConfirm confirm = CodePairingMessages.parseClientConfirm(
                payload(CodePairingMessages.clientPairConfirm(tag, bytes(48, 5))));
        assertArrayEquals(tag, confirm.tag);
        assertEquals(48, confirm.wrappedNonceB.length);
        assertNull(CodePairingMessages.parseClientConfirm(payload(CodePairingMessages.clientPairConfirm(tag, null))).wrappedNonceB);
        assertEquals(64, payload(CodePairingMessages.clientPairFinalizeWrapped(bytes(48, 1))).getString("wrapped_psk").length());
        assertArrayEquals(bytes(48, 1), CodePairingMessages.parseWrappedPsk(payload(CodePairingMessages.clientPairFinalizeWrapped(bytes(48, 1)))));
        assertEquals("client/pair-retry", type(CodePairingMessages.clientPairRetry()));
        assertEquals("server/pair-finalize", type(CodePairingMessages.serverPairFinalize()));
    }

    @Test
    public void badFieldsAreProtocolErrors() throws Exception {
        String[] bad = {
                "{\"pake_msg_1\":\"short\"}",
                "{\"pake_msg_1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}", // 48 chars: wrong length
                "{\"pake_msg_1\":12}",
                "{}",
        };
        for (String p : bad) {
            try {
                CodePairingMessages.parseShare(new JSONObject(p), "pake_msg_1");
                fail("accepted " + p);
            } catch (ProtocolException expected) {
                // refused
            }
        }
        try {
            CodePairingMessages.parseWrappedPsk(new JSONObject("{}"));
            fail();
        } catch (ProtocolException expected) {
            // missing
        }
        try {
            CodePairingMessages.parsePairInit(new JSONObject("{\"pairing_index\":-1}"));
            fail();
        } catch (ProtocolException expected) {
            // negative
        }
    }
}
