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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * CPACE-X25519-SHA512 against the test vectors of draft-irtf-cfrg-cpace (revision 21, the one the Sendspin specification names;
 * the vectors are the draft's, the repository's {@code testvectors.md}). This is the gate of task 11.1: if a vector does not pass,
 * the code-based pairing does not ship.
 */
public class CpaceTest {

    private static byte[] hex(String text) {
        String clean = text.replaceAll("\\s+", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static final byte[] PRS = "Password".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CI = hex("0b415f696e69746961746f720b425f726573706f6e646572");
    private static final byte[] SID = hex("7e4b4791d6a8ef019b936c79fb7f2c57");

    // ------------------------------------------------------------------ the string functions

    @Test
    public void prependLenIsLeb128() {
        assertEquals("00", hex(Cpace.prependLen(new byte[0])));
        assertEquals("0431323334", hex(Cpace.prependLen("1234".getBytes(StandardCharsets.UTF_8))));
        byte[] r127 = new byte[127];
        byte[] r128 = new byte[128];
        for (int i = 0; i < 128; i++) {
            if (i < 127) {
                r127[i] = (byte) i;
            }
            r128[i] = (byte) i;
        }
        assertEquals("7f000102", hex(Cpace.prependLen(r127)).substring(0, 8));
        assertEquals(128, Cpace.prependLen(r127).length);
        assertEquals("8001000102", hex(Cpace.prependLen(r128)).substring(0, 10));
        assertEquals(130, Cpace.prependLen(r128).length);
    }

    @Test
    public void transcriptIrMatchesTheVector() {
        byte[] t = Cpace.transcriptIr("123".getBytes(StandardCharsets.UTF_8), "PartyA".getBytes(StandardCharsets.UTF_8),
                "234".getBytes(StandardCharsets.UTF_8), "PartyB".getBytes(StandardCharsets.UTF_8));
        assertEquals("03313233065061727479410332333406506172747942", hex(t));
    }

    // ------------------------------------------------------------------ the generator

    @Test
    public void theGeneratorStringHasTheDraftsLayoutAndLength() {
        byte[] s = Cpace.generatorString(PRS, CI, SID);
        assertEquals(170, s.length);
        assertEquals("0843506163653235350850617373776f72646d00", hex(s).substring(0, 40));
        assertTrue("zero padding fills the first block", hex(s).substring(40, 60).matches("0+"));
        assertTrue(hex(s).endsWith("180b415f696e69746961746f720b425f726573706f6e646572107e4b4791d6a8ef019b936c79fb7f2c57"));
    }

    @Test
    public void theGeneratorMatchesTheVector() {
        assertEquals("d04bf6d41f6a289632a2e929fa29bebd51092512a7829fdde7d314b62f05a73f", hex(Cpace.generator(PRS, CI, SID)));
    }

    // ------------------------------------------------------------------ the messages and the key

    private static final byte[] YA = hex("21b4f4bd9e64ed355c3eb676a28ebedaf6d8f17bdc365995b319097153044080");
    private static final byte[] YB = hex("848b0779ff415f0af4ea14df9dd1d3c29ac41d836c7808896c4eba19c51ac40a");

    @Test
    public void thePublicSharesMatchTheVectors() throws Exception {
        byte[] g = Cpace.generator(PRS, CI, SID);
        assertEquals("1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32", hex(Cpace.scalarMult(YA, g)));
        assertEquals("248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea32185623", hex(Cpace.scalarMult(YB, g)));
    }

    @Test
    public void bothSidesDeriveTheSameSecretPointAndTheVectorsIsk() throws Exception {
        byte[] ya = hex("1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32");
        byte[] yb = hex("248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea32185623");
        byte[] ka = Cpace.scalarMultVfy(YA, yb);
        byte[] kb = Cpace.scalarMultVfy(YB, ya);
        assertEquals("5b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee71322c6b", hex(ka));
        assertArrayEquals(ka, kb);
        byte[] ada = "ADa".getBytes(StandardCharsets.UTF_8);
        byte[] adb = "ADb".getBytes(StandardCharsets.UTF_8);
        assertEquals("201d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f320341446120248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea3218562303414462",
                hex(Cpace.transcriptIr(ya, ada, yb, adb)));
        String isk = "6e19b875f7a561d6b3ca3dbb9ef42ac55de3e717881018204b8922b4d5e53bb2aa82c300bea7b65d2b671da71922ddf6472301b79bc270adfa8bf413285f2263";
        assertEquals(isk, hex(Cpace.isk(SID, ka, ya, ada, yb, adb)));
        assertEquals(isk, hex(Cpace.isk(SID, kb, ya, ada, yb, adb)));
    }

    // ------------------------------------------------------------------ the abort cases

    private static final String[] LOW_ORDER = {
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"};

    @Test
    public void aSharethatEncodesALowOrderPointAbortsTheRun() {
        byte[] s = hex("af46e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449aff");
        for (String u : LOW_ORDER) {
            try {
                Cpace.scalarMultVfy(s, hex(u));
                fail("accepted " + u);
            } catch (CpaceException expected) {
                // the abort condition
            }
        }
    }

    @Test
    public void aNonCanonicalCoordinateIsHandledAsTheRfcDemands() throws Exception {
        byte[] s = hex("af46e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449aff");
        assertEquals("d8e2c776bbacd510d09fd9278b7edcd25fc5ae9adfba3b6e040e8d3b71b21806",
                hex(Cpace.scalarMultVfy(s, hex("daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))));
        assertEquals("c85c655ebe8be44ba9c0ffde69f2fe10194458d137f09bbff725ce58803cdb38",
                hex(Cpace.scalarMultVfy(s, hex("dbffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))));
        assertEquals("db64dafa9b8fdd136914e61461935fe92aa372cb056314e1231bc4ec12417456",
                hex(Cpace.scalarMultVfy(s, hex("d9ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))));
        assertEquals("e062dcd5376d58297be2618c7498f55baa07d7e03184e8aada20bca28888bf7a",
                hex(Cpace.scalarMultVfy(s, hex("cdeb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880"))));
        assertEquals("993c6ad11c4c29da9a56f7691fd0ff8d732e49de6250b6c2e80003ff4629a175",
                hex(Cpace.scalarMultVfy(s, hex("4c9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7"))));
    }

    // ------------------------------------------------------------------ the two roles end to end

    @Test
    public void twoPartiesWithTheSameCodeAgreeAndWithAnotherCodeTheirTagsFail() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] code = "123456".getBytes(StandardCharsets.UTF_8);
        byte[] sid = "sendspin-pair-pake-v1".getBytes(StandardCharsets.UTF_8);
        Cpace.Party a = Cpace.initiator(code, sid, random);
        Cpace.Party b = Cpace.responder(code, sid, random);
        byte[] iskA = a.finish(b.share());
        byte[] iskB = b.finish(a.share());
        assertArrayEquals(iskA, iskB);
        byte[] ta = a.tag();
        byte[] tb = b.tag();
        assertTrue(b.verifyPeerTag(ta));
        assertTrue(a.verifyPeerTag(tb));
        assertEquals(64, ta.length);
        assertFalse("the tags are different (own role, own message)", java.util.Arrays.equals(ta, tb));

        Cpace.Party other = Cpace.responder("654321".getBytes(StandardCharsets.UTF_8), sid, random);
        Cpace.Party a2 = Cpace.initiator(code, sid, random);
        byte[] iskA2 = a2.finish(other.share());
        byte[] iskO = other.finish(a2.share());
        assertFalse("another code: another key", java.util.Arrays.equals(iskA2, iskO));
        assertFalse("and the tag of the other does not verify", a2.verifyPeerTag(other.tag()));
        assertFalse(other.verifyPeerTag(a2.tag()));
    }
}
