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

import org.junit.Test;

public class PairingTokenTest {

    private static final String VECTOR = "SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y";

    private static byte[] range(int from) {
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) {
            b[i] = (byte) (from + i);
        }
        return b;
    }

    @Test
    public void theSpecificationsReferenceVectorEncodes() {
        assertEquals(VECTOR, PairingToken.encode(range(0), range(0xe0)));
    }

    @Test
    public void theSpecificationsReferenceVectorDecodes() {
        PairingToken token = PairingToken.decode(VECTOR);
        assertNotNull(token);
        assertArrayEquals(range(0), token.clientKey());
        assertArrayEquals(range(0xe0), token.pairingPsk());
        assertEquals(Base64Url.encode(range(0)), token.clientId());
    }

    @Test
    public void decodingIsLenientWithWhatAnOperatorPastes() {
        assertNotNull(PairingToken.decode("  " + VECTOR.toLowerCase() + "\n"));
        assertNotNull(PairingToken.decode(VECTOR.substring(3)));
        assertNotNull(PairingToken.decode(VECTOR.substring(0, 20) + "\n" + VECTOR.substring(20)));
        assertArrayEquals(range(0xe0), PairingToken.decode(VECTOR.substring(3).toLowerCase()).pairingPsk());
    }

    @Test
    public void bytesBeyondTheDefinedPayloadAreIgnored() {
        byte[] longer = new byte[70];
        System.arraycopy(range(0), 0, longer, 0, 32);
        System.arraycopy(range(0xe0), 0, longer, 32, 32);
        String token = PairingToken.encodeRaw('0', longer);
        PairingToken decoded = PairingToken.decode(token);
        assertNotNull(decoded);
        assertArrayEquals(range(0xe0), decoded.pairingPsk());
    }

    @Test
    public void malformedInputIsRejected() {
        assertNull(PairingToken.decode(null));
        assertNull(PairingToken.decode(""));
        assertNull(PairingToken.decode("SP:"));
        assertNull(PairingToken.decode("SP:0"));
        assertNull("too short", PairingToken.decode(VECTOR.substring(0, VECTOR.length() - 10)));
        assertNull("bad character", PairingToken.decode(VECTOR.substring(0, 30) + "!" + VECTOR.substring(31)));
        assertNull("a digit that base32 does not have (1)", PairingToken.decode(VECTOR.substring(0, 30) + "1" + VECTOR.substring(31)));
        assertNull("padding inside", PairingToken.decode(VECTOR.substring(0, 30) + "=" + VECTOR.substring(31)));
    }

    @Test
    public void otherVersionsAreRejected() {
        assertNull("unknown version", PairingToken.decode("SP:2" + VECTOR.substring(4)));
        // version 1 (a dynamic pairing code) is defined by the specification but is not a pairing PSK token
        assertNull(PairingToken.decode("SP:1" + VECTOR.substring(4)));
    }

    @Test
    public void aTokenOfOthersFoldsToTheSameBytesWhateverTheCase() {
        assertEquals(VECTOR, PairingToken.encode(PairingToken.decode(VECTOR.toLowerCase()).clientKey(), PairingToken.decode(VECTOR).pairingPsk()));
    }

    @Test
    public void wrongKeyLengthsAreRefusedByTheEncoder() {
        try {
            PairingToken.encode(new byte[31], new byte[32]);
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }
}
