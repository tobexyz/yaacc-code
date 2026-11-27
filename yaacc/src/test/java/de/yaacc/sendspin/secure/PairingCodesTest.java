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
package de.yaacc.sendspin.secure;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** The values around the code-based pairing of the Sendspin specification: derivation, commitment, session id, wrapping, tokens. */
public class PairingCodesTest {

    private static byte[] bytes(int from, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }

    // ------------------------------------------------------------------ the token of version 1

    @Test
    public void theReferenceVectorOfTheSpecificationIsAVersionOneToken() {
        byte[] code = bytes(0xe0, 24); // 0xe0 0xe1 ... 0xf7
        assertEquals("SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y", PairingCodes.codeToken(code));
        assertArrayEquals(code, PairingCodes.decodeCodeToken("SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y"));
    }

    @Test
    public void aTokenIsReadLeniently() {
        byte[] code = bytes(0xe0, 24);
        assertArrayEquals(code, PairingCodes.decodeCodeToken("  sp:14dq6fy7e4xtop9hj5lv6z3po57ypd4xt6t97n5y \n"));
        assertArrayEquals("without the prefix", code, PairingCodes.decodeCodeToken("14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y"));
    }

    @Test
    public void aVersionZeroTokenIsNotACodeAndBadInputIsRefused() {
        assertNull(PairingCodes.decodeCodeToken(PairingToken.encode(new byte[32], new byte[32])));
        assertNull(PairingCodes.decodeCodeToken("SP:1"));
        assertNull(PairingCodes.decodeCodeToken("SP:1AAAA")); // shorter than the payload
        assertNull(PairingCodes.decodeCodeToken("SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5!"));
        assertNull(PairingCodes.decodeCodeToken(null));
        assertNull(PairingCodes.decodeCodeToken("SP:2" + "A".repeat(39)));
    }

    // ------------------------------------------------------------------ the derivation

    @Test
    public void theDigestAndBothCodesFollowTheSpecification() throws Exception {
        byte[] h = bytes(0, 32);
        byte[] nonceA = bytes(32, 32);
        byte[] nonceB = bytes(64, 32);
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        sha.update("sendspin-pairing-code-derive-v1".getBytes(StandardCharsets.UTF_8));
        sha.update(h);
        sha.update(nonceA);
        sha.update(nonceB);
        byte[] expected = sha.digest();
        byte[] digest = PairingCodes.codeDigest(h, nonceA, nonceB);
        assertArrayEquals(expected, digest);
        String digits = PairingCodes.digitsCode(digest);
        assertEquals(6, digits.length());
        assertTrue(digits.matches("[0-9]{6}"));
        assertEquals(new java.math.BigInteger(1, expected).mod(java.math.BigInteger.valueOf(1_000_000)).intValue(), Integer.parseInt(digits));
        assertArrayEquals(Arrays.copyOf(expected, 24), PairingCodes.qrCode(digest));
    }

    @Test
    public void theDigitsAreZeroPaddedOnTheLeft() {
        // a digest whose value mod 10^6 is small: 5 -> "000005"
        byte[] digest = new byte[32];
        digest[31] = 5;
        assertEquals("000005", PairingCodes.digitsCode(digest));
    }

    @Test
    public void theCommitmentIsTheHashOfTheLabelAndTheNonce() throws Exception {
        byte[] nonceB = bytes(1, 32);
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        sha.update("sendspin-pair-commit-v1".getBytes(StandardCharsets.UTF_8));
        sha.update(nonceB);
        assertArrayEquals(sha.digest(), PairingCodes.commit(nonceB));
    }

    @Test
    public void theSessionIdOfThePakeIsTheLabelTheHashTheIndexAndTheRound() {
        byte[] h = bytes(9, 32);
        byte[] sid = PairingCodes.pakeSid(h, 3, 2);
        byte[] label = "sendspin-pair-pake-v1".getBytes(StandardCharsets.UTF_8);
        assertEquals(label.length + 32 + 4 + 4, sid.length);
        assertArrayEquals(label, Arrays.copyOfRange(sid, 0, label.length));
        assertArrayEquals(h, Arrays.copyOfRange(sid, label.length, label.length + 32));
        assertArrayEquals(new byte[]{0, 0, 0, 3, 0, 0, 0, 2}, Arrays.copyOfRange(sid, label.length + 32, sid.length));
    }

    // ------------------------------------------------------------------ the entry of a code

    @Test
    public void aTypedCodeLosesItsSeparatorsAndMustHaveTheRightLength() {
        assertEquals("123456", PairingCodes.normalizeDigits("123-456", 6));
        assertEquals("12345678", PairingCodes.normalizeDigits(" 1234 5678 ", 8));
        assertEquals("12345678", PairingCodes.normalizeDigits("1234-5678", 8));
        assertNull(PairingCodes.normalizeDigits("12345", 6));
        assertNull(PairingCodes.normalizeDigits("1234567", 6));
        assertNull(PairingCodes.normalizeDigits("12a456", 6));
        assertNull(PairingCodes.normalizeDigits(null, 6));
        assertEquals("grouped for the eye", "123-456", PairingCodes.group("123456"));
        assertEquals("1234-5678", PairingCodes.group("12345678"));
    }

    // ------------------------------------------------------------------ the wrapping

    @Test
    public void aValueIsWrappedUnderTheKeyOfTheRunInBothSuites() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] sid = bytes(5, 40);
        byte[] isk = new byte[64];
        random.nextBytes(isk);
        byte[] psk = new byte[32];
        random.nextBytes(psk);
        for (NoiseSuite suite : NoiseSuite.values()) {
            byte[] wrapped = PairingCodes.wrapPsk(suite, sid, isk, psk);
            assertEquals(48, wrapped.length);
            assertArrayEquals(psk, PairingCodes.unwrapPsk(suite, sid, isk, wrapped));
            byte[] nonceWrapped = PairingCodes.wrapNonce(suite, sid, isk, psk);
            assertEquals(48, nonceWrapped.length);
            assertArrayEquals(psk, PairingCodes.unwrapNonce(suite, sid, isk, nonceWrapped));
            try {
                PairingCodes.unwrapNonce(suite, sid, isk, wrapped); // another label: another key
                fail("the labels of the two fields give different keys");
            } catch (NoiseException expected) {
                // not decryptable
            }
            byte[] otherIsk = isk.clone();
            otherIsk[0] ^= 1;
            try {
                PairingCodes.unwrapPsk(suite, sid, otherIsk, wrapped);
                fail("another key does not open it");
            } catch (NoiseException expected) {
                // not decryptable
            }
        }
    }
}
