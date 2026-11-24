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

import java.util.Arrays;

/**
 * A pairing token of version 0 (spec, "Pairing Token"): {@code "SP:" "0"} and the base32 text of the client's public key and its
 * Pairing PSK, 32 bytes each. The operator copies it out of the player and pastes it into the server. It is a credential: whoever
 * holds it can pair any server with that player, so it is never logged and its {@link #toString()} shows nothing of it.
 * <p>
 * The text is the base32 of RFC 4648 (letters and 2 to 7) without padding, with every {@code 2} written as {@code 9} (so it fits
 * the alphanumeric mode of a QR code). Decoding is lenient with what a person pastes (spaces, line breaks, any case, a missing
 * {@code SP:}) and strict about everything else. Pure.
 */
public final class PairingToken {

    private static final String PREFIX = "SP:";
    private static final char VERSION_PSK = '0';
    private static final int KEY_LENGTH = 32;
    private static final int PAYLOAD_LENGTH = 2 * KEY_LENGTH;
    private static final int MAX_TEXT = 512;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final byte[] clientKey;
    private final byte[] pairingPsk;

    private PairingToken(byte[] clientKey, byte[] pairingPsk) {
        this.clientKey = clientKey;
        this.pairingPsk = pairingPsk;
    }

    /** The player's public key, 32 bytes (a copy). */
    public byte[] clientKey() {
        return clientKey.clone();
    }

    /** The player's Pairing PSK, 32 bytes (a copy). */
    public byte[] pairingPsk() {
        return pairingPsk.clone();
    }

    /** The player's {@code client_id}: base64url of its public key. */
    public String clientId() {
        return Base64Url.encode(clientKey);
    }

    @Override
    public String toString() {
        return "PairingToken[" + clientId() + "]"; // the id is public; the PSK never shows
    }

    public static String encode(byte[] clientKey, byte[] pairingPsk) {
        if (clientKey == null || pairingPsk == null || clientKey.length != KEY_LENGTH || pairingPsk.length != KEY_LENGTH) {
            throw new IllegalArgumentException("a token carries two keys of 32 bytes");
        }
        byte[] payload = new byte[PAYLOAD_LENGTH];
        System.arraycopy(clientKey, 0, payload, 0, KEY_LENGTH);
        System.arraycopy(pairingPsk, 0, payload, KEY_LENGTH, KEY_LENGTH);
        return encodeRaw(VERSION_PSK, payload);
    }

    /** The token text for any version and payload (tests build tokens with extra bytes with it). */
    static String encodeRaw(char version, byte[] payload) {
        StringBuilder out = new StringBuilder(PREFIX).append(version);
        int buffer = 0;
        int bits = 0;
        for (byte b : payload) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(symbol(ALPHABET.charAt((buffer >> (bits - 5)) & 31)));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(symbol(ALPHABET.charAt((buffer << (5 - bits)) & 31)));
        }
        return out.toString();
    }

    private static char symbol(char c) {
        return c == '2' ? '9' : c;
    }

    /** @return the token, or null if the text is not a version 0 token (wrong version, bad characters, too short) */
    public static PairingToken decode(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < text.length() && clean.length() <= MAX_TEXT; i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                clean.append(Character.toUpperCase(c));
            }
        }
        String s = clean.toString();
        if (s.length() > MAX_TEXT) {
            return null;
        }
        if (s.startsWith(PREFIX)) {
            s = s.substring(PREFIX.length());
        }
        if (s.isEmpty() || s.charAt(0) != VERSION_PSK) {
            return null;
        }
        byte[] payload = decodeBody(s.substring(1));
        if (payload == null || payload.length < PAYLOAD_LENGTH) {
            return null;
        }
        return new PairingToken(Arrays.copyOfRange(payload, 0, KEY_LENGTH), Arrays.copyOfRange(payload, KEY_LENGTH, PAYLOAD_LENGTH));
    }

    private static byte[] decodeBody(String body) {
        int total = body.length() * 5 / 8;
        byte[] out = new byte[total];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '9') {
                c = '2';
            }
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out;
    }
}
