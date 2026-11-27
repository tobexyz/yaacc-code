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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;

import de.yaacc.sendspin.player.ClientCodePairing;

public class ClientCodePairingTest {

    @Test
    public void staticCodeHasEightDigitsAndIsRecognised() {
        String code = ClientCodePairing.newStaticCode(new SecureRandom());
        assertEquals(8, code.length());
        assertTrue(ClientCodePairing.isStaticCode(code));
        assertFalse(ClientCodePairing.isStaticCode("1234567"));
        assertFalse(ClientCodePairing.isStaticCode("1234567a"));
        assertFalse(ClientCodePairing.isStaticCode(null));
    }

    @Test
    public void offersAllThreeMethods() {
        ClientCodePairing pairing = new ClientCodePairing("12345678", () -> 0L);
        assertTrue(pairing.offer().offersDigits());
        assertTrue(pairing.offer().offersQrCode());
        assertEquals("12345678", pairing.offer().staticCode());
    }

    @Test
    public void showsAndHidesTheCodeOfAnAttempt() {
        ClientCodePairing pairing = new ClientCodePairing("12345678", () -> 0L);
        assertNull(pairing.shown());
        pairing.show("123456", null);
        assertNotNull(pairing.shown());
        assertEquals("123-456", pairing.shown().digits);
        pairing.hide();
        assertNull(pairing.shown());
    }

    @Test
    public void windowIsClosedUntilTheOperatorOpensIt() {
        ClientCodePairing pairing = new ClientCodePairing("12345678", () -> 0L);
        assertFalse(pairing.window().isOpen());
        pairing.window().open();
        assertTrue(pairing.window().isOpen());
    }
}
