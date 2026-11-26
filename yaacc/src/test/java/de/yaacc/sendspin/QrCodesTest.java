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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.PairingToken;

import org.junit.Test;

import java.security.SecureRandom;

public class QrCodesTest {

    private static String decode(boolean[][] modules, int scale) throws Exception {
        int size = QrCodes.sizeOf(modules.length, scale);
        LuminanceSource source = new RGBLuminanceSource(size, size, QrCodes.pixels(modules, scale));
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    @Test
    public void aPairingTokenIsReadBackFromItsCodeVerbatim() throws Exception {
        byte[] psk = new byte[32];
        new SecureRandom().nextBytes(psk);
        String token = PairingToken.encode(Identity.generate(new SecureRandom()).publicKey(), psk);
        assertTrue(token.startsWith("SP:0"));
        boolean[][] modules = QrCodes.modules(token);
        assertEquals(modules.length, modules[0].length);
        assertTrue("a token is alphanumeric text: a small code", modules.length <= 61);
        assertEquals(token, decode(modules, 6));
        assertEquals("the same code at another scale", token, decode(modules, 3));
        assertTrue("what the scanner reads is a token", PairingToken.decode(decode(modules, 4)) != null);
    }

    @Test
    public void pixelsHaveAQuietZoneAndTheRightSize() {
        boolean[][] modules = QrCodes.modules("SP:0ABC");
        int scale = 5;
        int[] pixels = QrCodes.pixels(modules, scale);
        int size = QrCodes.sizeOf(modules.length, scale);
        assertEquals(size * size, pixels.length);
        for (int i = 0; i < size; i++) {
            assertEquals("top edge is white", 0xFFFFFFFF, pixels[i]);
            assertEquals("left edge is white", 0xFFFFFFFF, pixels[i * size]);
        }
        // the finder pattern starts at the corner of the code: the first dark module
        int corner = QrCodes.QUIET_ZONE * scale * size + QrCodes.QUIET_ZONE * scale;
        assertEquals(0xFF000000, pixels[corner]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void textThatDoesNotFitIsRefused() {
        QrCodes.modules(new String(new char[9000]).replace('\0', 'x'));
    }
}
