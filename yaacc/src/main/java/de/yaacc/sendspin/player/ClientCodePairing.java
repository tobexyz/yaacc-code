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
package de.yaacc.sendspin.player;

import java.security.SecureRandom;
import java.util.function.LongSupplier;

import de.yaacc.sendspin.pairing.CodePairingClient;
import de.yaacc.sendspin.pairing.PairingWindow;
import de.yaacc.sendspin.secure.PairingCodes;

/**
 * What this phone as a player needs to be paired with a code (issue #325, task 11.1): the pairing window that the operator opens with a
 * gesture, the methods it offers (digits, QR code and its fixed code) and the code of the running attempt for the display. The window
 * lasts 5 minutes and closes after failed attempts, as the specification demands; a server cannot pair with the phone unless the
 * operator opened it on the phone itself.
 */
public final class ClientCodePairing implements CodePairingClient.Emitter {

    /** The code a running attempt shows: the digits and the text of the QR code (the same code twice, in the two formats). */
    public static final class Shown {
        public final String digits;
        public final String qrToken;

        Shown(String digits, String qrToken) {
            this.digits = digits;
            this.qrToken = qrToken;
        }
    }

    private final PairingWindow window;
    private final CodePairingClient.Offer offer;
    private volatile Shown shown;

    public ClientCodePairing(String staticCode, LongSupplier nowMillis) {
        this.window = new PairingWindow(nowMillis);
        this.offer = new CodePairingClient.Offer(true, true, staticCode);
    }

    public PairingWindow window() {
        return window;
    }

    public CodePairingClient.Offer offer() {
        return offer;
    }

    /** @return what the display shows now, or null while no attempt shows a code */
    public Shown shown() {
        return shown;
    }

    @Override
    public void show(String digitsCode, byte[] qrCode) {
        String digits = digitsCode == null ? null : PairingCodes.group(digitsCode);
        String token = qrCode == null ? null : PairingCodes.codeToken(qrCode);
        shown = new Shown(digits, token);
    }

    @Override
    public void hide() {
        shown = null;
    }

    /** A fixed code of 8 decimal digits, drawn once per installation (the static flow of the specification). */
    public static String newStaticCode(SecureRandom random) {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }

    /** @return true if the stored text is a usable fixed code (8 decimal digits) */
    public static boolean isStaticCode(String text) {
        if (text == null || text.length() != 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
