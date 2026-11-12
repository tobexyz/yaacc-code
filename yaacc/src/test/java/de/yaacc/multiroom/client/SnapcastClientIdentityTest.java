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
    public void theIdIsStablePerServerAndDiffersBetweenServers() {
        String secret = SnapcastClientIdentity.newSecret(new SecureRandom());
        String a = SnapcastClientIdentity.deriveId(secret, "192.168.1.5:1704");
        assertEquals(a, SnapcastClientIdentity.deriveId(secret, "192.168.1.5:1704"));
        assertNotEquals(a, SnapcastClientIdentity.deriveId(secret, "192.168.1.6:1704"));
    }

    @Test
    public void theIdDiffersBetweenInstallations() {
        SecureRandom random = new SecureRandom();
        assertNotEquals(SnapcastClientIdentity.deriveId(SnapcastClientIdentity.newSecret(random), "10.0.0.1:1704"),
                SnapcastClientIdentity.deriveId(SnapcastClientIdentity.newSecret(random), "10.0.0.1:1704"));
    }

    @Test
    public void theIdHasAFixedShapeAndRevealsNeitherInput() {
        String secret = "00112233445566778899aabbccddeeff";
        String id = SnapcastClientIdentity.deriveId(secret, "192.168.1.5:1704");
        assertTrue(id, id.matches("yaacc-[0-9a-f]{16}"));
        assertFalse(id.contains("192"));
        assertFalse(id.contains("0011"));
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
