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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;

public class SnapcastClientIdentityTest {

    @Test
    public void theIdIsStableAndDependsOnTheInstallSecretOnly() {
        String secret = SnapcastClientIdentity.newSecret(new SecureRandom());
        String id = SnapcastClientIdentity.deriveId(secret);
        // one id for every server, also a public one: nothing but the secret goes in (task 12.11)
        assertEquals(id, SnapcastClientIdentity.deriveId(secret));
        assertEquals(id, SnapcastClientIdentity.deriveId(new String(secret)));
    }

    @Test
    public void theIdHasNoParameterForTheServer() throws Exception {
        for (java.lang.reflect.Method method : SnapcastClientIdentity.class.getDeclaredMethods()) {
            if (method.getName().equals("deriveId")) {
                assertEquals("deriveId takes the secret and nothing else", 1, method.getParameterCount());
            }
        }
    }

    @Test
    public void theIdDiffersBetweenInstallations() {
        SecureRandom random = new SecureRandom();
        assertNotEquals(SnapcastClientIdentity.deriveId(SnapcastClientIdentity.newSecret(random)),
                SnapcastClientIdentity.deriveId(SnapcastClientIdentity.newSecret(random)));
    }

    @Test
    public void theIdHasAFixedShapeAndRevealsNothingOfTheSecret() {
        String secret = "00112233445566778899aabbccddeeff";
        String id = SnapcastClientIdentity.deriveId(secret);
        assertTrue(id, id.matches("yaacc-[0-9a-f]{16}"));
        assertFalse(id.contains("0011"));
        assertFalse(id.contains(secret.substring(16)));
    }

    @Test
    public void theIdOfAKnownSecretIsPinned() {
        // pinned: a change of the derivation would make every client entry on every server a ghost again
        assertEquals("yaacc-" + expectedHex("00112233445566778899aabbccddeeff"),
                SnapcastClientIdentity.deriveId("00112233445566778899aabbccddeeff"));
    }

    private static String expectedHex(String secret) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update("snapcast-client-id".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void withoutAValidSecretThereIsNoOwnId() {
        assertEquals(null, SnapcastClientIdentity.idOfSecret(null));
        assertEquals(null, SnapcastClientIdentity.idOfSecret(""));
        assertEquals(null, SnapcastClientIdentity.idOfSecret("not a secret"));
        String secret = SnapcastClientIdentity.newSecret(new SecureRandom());
        assertEquals(SnapcastClientIdentity.deriveId(secret), SnapcastClientIdentity.idOfSecret(secret));
    }

    @Test
    public void aSecretHasTheShapeThatIsChecked() {
        assertTrue(SnapcastClientIdentity.isSecret(SnapcastClientIdentity.newSecret(new SecureRandom())));
        assertFalse(SnapcastClientIdentity.isSecret(null));
        assertFalse(SnapcastClientIdentity.isSecret("short"));
        assertFalse(SnapcastClientIdentity.isSecret("zz112233445566778899aabbccddeeff"));
        assertFalse(SnapcastClientIdentity.isSecret("00112233445566778899AABBCCDDEEFF"));
    }
}
