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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Base64;

import de.yaacc.fcast.FakeFCastTlsReceiver;

/** Task 7.2: the {@code fp} text of a receiver and what it matches. */
public class FCastPinTest {
    private static final String ONE = "QvrqvvBvKimMvIvJElsiQeiviSXvefqpiZYVxKXZOWc="; // the example of the v4 document (32 bytes)

    @Test
    public void aFingerprintOf32BytesIsAccepted() {
        FCastPin pin = FCastPin.parse(ONE, FCastPin.Source.LINK);
        assertNotNull(pin);
        assertEquals(FCastPin.Source.LINK, pin.getSource());
        assertEquals(ONE, pin.toBase64());
    }

    @Test
    public void paddingIsOptionalAndSurroundingSpacesAreIgnored() {
        assertNotNull(FCastPin.parse(ONE.substring(0, ONE.length() - 1), FCastPin.Source.DISCOVERY));
        assertNotNull(FCastPin.parse("  " + ONE + " \n", FCastPin.Source.DISCOVERY));
    }

    @Test
    public void whatIsNotThirtyTwoBytesOfBase64IsRefused() {
        assertNull(FCastPin.parse(null, FCastPin.Source.LINK));
        assertNull(FCastPin.parse("", FCastPin.Source.LINK));
        assertNull(FCastPin.parse("not base64!", FCastPin.Source.LINK));
        assertNull(FCastPin.parse(Base64.getEncoder().encodeToString(new byte[31]), FCastPin.Source.LINK));
        assertNull(FCastPin.parse(Base64.getEncoder().encodeToString(new byte[33]), FCastPin.Source.LINK));
        assertNull(FCastPin.parse(ONE + ONE, FCastPin.Source.LINK));
        assertNull(FCastPin.parse(ONE, null));
    }

    @Test
    public void thePinOfACertificateIsTheHashOfItsPublicKeyInfo() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FCastPin pin = keys.pin(FCastPin.Source.LINK);
        FCastPin again = FCastPin.parse(pin.toBase64(), FCastPin.Source.DISCOVERY);
        assertEquals(pin, again);
        assertEquals(pin.hashCode(), again.hashCode());
    }

    @Test
    public void aPinMatchesItsOwnCertificateAndNoOther() throws Exception {
        FakeFCastTlsReceiver.Keys first = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Keys second = FakeFCastTlsReceiver.Keys.get(1);
        FCastPin pin = first.pin(FCastPin.Source.LINK);
        assertTrue(pin.matches(first.certificate()));
        assertFalse(pin.matches(second.certificate()));
        assertFalse(pin.matches(null));
        assertFalse(pin.equals(second.pin(FCastPin.Source.LINK)));
    }

    @Test
    public void theHashIsTheStandardSpkiHashNotAHashOfTheWholeCertificate() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        byte[] expected = java.security.MessageDigest.getInstance("SHA-256").digest(keys.certificate().getPublicKey().getEncoded());
        assertEquals(Base64.getEncoder().encodeToString(expected), keys.pin(FCastPin.Source.LINK).toBase64());
        byte[] wholeCertificate = java.security.MessageDigest.getInstance("SHA-256").digest(keys.certificate().getEncoded());
        assertFalse(Base64.getEncoder().encodeToString(wholeCertificate).equals(keys.pin(FCastPin.Source.LINK).toBase64()));
    }
}
