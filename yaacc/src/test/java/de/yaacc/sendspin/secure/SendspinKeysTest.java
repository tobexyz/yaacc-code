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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.SecureRandom;

/** X25519 against RFC 7748, the Sentinel constants of the specification, identities and the base64url form. */
public class SendspinKeysTest {

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    public void x25519MatchesRfc7748Section6() throws Exception {
        X25519Keys.KeyPair alice = X25519Keys.fromPrivate(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"));
        X25519Keys.KeyPair bob = X25519Keys.fromPrivate(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"));
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(alice.publicKey()));
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hex(bob.publicKey()));
        String shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";
        assertEquals(shared, hex(X25519Keys.dh(alice.privateKeyBytes(), bob.publicKey())));
        assertEquals(shared, hex(X25519Keys.dh(bob.privateKeyBytes(), alice.publicKey())));
    }

    @Test
    public void aPointOfSmallOrderIsRefused() {
        try {
            X25519Keys.dh(X25519Keys.generate(new SecureRandom()).privateKeyBytes(), new byte[32]); // the all-zero point
            fail("expected a refusal");
        } catch (NoiseException expected) {
            // fine
        }
    }

    @Test
    public void theSentinelConstantsAreThoseOfTheSpecification() {
        assertEquals("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3", hex(SendspinPsk.sentinel()));
        assertEquals("GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo", SendspinPsk.sentinelId());
        assertEquals("185b15f6d2da4909bd1dc156a4ab206103abef0153bcd52d926170b95cf7ce8a", hex(Base64Url.decode(SendspinPsk.sentinelId())));
        assertTrue(SendspinPsk.isSentinel(SendspinPsk.sentinel()));
    }

    @Test
    public void pskCategoriesHaveTheirCodes() {
        assertEquals(SendspinPsk.Category.LONG_TERM, SendspinPsk.Category.fromCode("lt"));
        assertEquals(SendspinPsk.Category.PAIRING, SendspinPsk.Category.fromCode("pr"));
        assertEquals(SendspinPsk.Category.SENTINEL, SendspinPsk.Category.fromCode("sn"));
        assertNull(SendspinPsk.Category.fromCode("xx"));
        assertNull(SendspinPsk.Category.fromCode(null));
    }

    @Test
    public void anIdentityIdIsThe43CharactersOfItsPublicKeyAndSurvivesStorage() {
        Identity identity = Identity.generate(new SecureRandom());
        assertEquals(43, identity.id().length());
        Identity restored = Identity.fromPrivateKey(identity.exportPrivateKey());
        assertEquals(identity.id(), restored.id());
        assertArrayEquals(identity.publicKey(), Base64Url.decode32(identity.id()));
    }

    @Test
    public void twoIdentitiesDiffer() {
        assertTrue(!Identity.generate(new SecureRandom()).id().equals(Identity.generate(new SecureRandom()).id()));
    }

    @Test
    public void base64UrlIsStrict() {
        assertNotNull(Base64Url.decode("AAAA"));
        assertNull("padding", Base64Url.decode("AA=="));
        assertNull("standard alphabet", Base64Url.decode("A+A/"));
        assertNull("trailing bits that are not zero", Base64Url.decode("AB"));
        assertNull("a length no byte count gives", Base64Url.decode("A"));
        assertNull(Base64Url.decode(null));
        assertNull("not 32 bytes", Base64Url.decode32("AAAA"));
        assertEquals(32, Base64Url.decode32(Base64Url.encode(new byte[32])).length);
    }
}
