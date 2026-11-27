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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The values around the code-based pairing of the Sendspin specification (issue #325, task 11.1; "Dynamic Pairing Code Flow",
 * "Static Pairing Code Flow", "Pairing Token", "PAKE", "Wrapping"): the pairing code derived from the Noise handshake hash and the two
 * nonces, the commitment to the client's nonce, the session id of the PAKE, the wrapping of the long-term PSK and of the nonce under
 * the key of the run, the version 1 token that carries a code as text or QR code, and the entry of a typed code. Pure; the hashes are
 * the platform's, the AEAD is the one of the connection's suite.
 */
public final class PairingCodes {

    public static final int NONCE_BYTES = 32;
    /** The digits of a dynamic code. */
    public static final int DYNAMIC_DIGITS = 6;
    /** The digits of a static code. */
    public static final int STATIC_DIGITS = 8;
    /** The bytes of a code in the {@code qr_code} format. */
    public static final int QR_CODE_BYTES = 24;
    public static final int WRAPPED_BYTES = 48;

    private static final String LABEL_DERIVE = "sendspin-pairing-code-derive-v1";
    private static final String LABEL_COMMIT = "sendspin-pair-commit-v1";
    private static final String LABEL_SID = "sendspin-pair-pake-v1";
    private static final String LABEL_WRAP_PSK = "sendspin-pair-psk-wrap-v1";
    private static final String LABEL_WRAP_NONCE = "sendspin-pair-nonce-wrap-v1";
    private static final char VERSION_CODE = '1';
    private static final String PREFIX = "SP:";

    private PairingCodes() {
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is always there", e);
        }
    }

    // ------------------------------------------------------------------ derivation and commitment

    /** {@code SHA-256("sendspin-pairing-code-derive-v1" || h || nonce_A || nonce_B)}. */
    public static byte[] codeDigest(byte[] h, byte[] nonceA, byte[] nonceB) {
        MessageDigest sha = sha256();
        sha.update(LABEL_DERIVE.getBytes(StandardCharsets.UTF_8));
        sha.update(h);
        sha.update(nonceA);
        sha.update(nonceB);
        return sha.digest();
    }

    /** The 6 ASCII digits: the digest as a big-endian integer mod 10^6, zero padded on the left. */
    public static String digitsCode(byte[] digest) {
        int value = new BigInteger(1, digest).mod(BigInteger.valueOf(1_000_000)).intValue();
        return String.format(java.util.Locale.ROOT, "%06d", value);
    }

    /** The code of the {@code qr_code} format: the first 24 bytes of the digest. */
    public static byte[] qrCode(byte[] digest) {
        return Arrays.copyOf(digest, QR_CODE_BYTES);
    }

    /** {@code commit_B = SHA-256("sendspin-pair-commit-v1" || nonce_B)}. */
    public static byte[] commit(byte[] nonceB) {
        MessageDigest sha = sha256();
        sha.update(LABEL_COMMIT.getBytes(StandardCharsets.UTF_8));
        sha.update(nonceB);
        return sha.digest();
    }

    /** The {@code sid} of the PAKE: {@code "sendspin-pair-pake-v1" || h || pairing_index (uint32 BE) || round (uint32 BE)}. */
    public static byte[] pakeSid(byte[] h, int pairingIndex, int round) {
        byte[] label = LABEL_SID.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(label.length + h.length + 8).put(label).put(h).putInt(pairingIndex).putInt(round).array();
    }

    // ------------------------------------------------------------------ the tokens of version 1

    /** The text of a code of 24 bytes as a version 1 pairing token (what the client shows as a QR code). */
    public static String codeToken(byte[] code) {
        if (code == null || code.length != QR_CODE_BYTES) {
            throw new IllegalArgumentException("a code has 24 bytes");
        }
        return PairingToken.encodeRaw(VERSION_CODE, code);
    }

    /** @return the 24 bytes of the code of a version 1 token (typed or scanned), or null if the text is not one */
    public static byte[] decodeCodeToken(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < text.length() && clean.length() <= 512; i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                clean.append(Character.toUpperCase(c));
            }
        }
        String s = clean.toString();
        if (s.startsWith(PREFIX)) {
            s = s.substring(PREFIX.length());
        }
        if (s.isEmpty() || s.charAt(0) != VERSION_CODE) {
            return null;
        }
        byte[] payload = PairingToken.decodeBodyForTests(s.substring(1));
        if (payload == null || payload.length < QR_CODE_BYTES) {
            return null;
        }
        return Arrays.copyOf(payload, QR_CODE_BYTES); // bytes beyond the defined payload are reserved and ignored
    }

    // ------------------------------------------------------------------ typed codes

    /** A typed code without spaces and hyphens; null unless it is exactly {@code digits} digits. */
    public static String normalizeDigits(String typed, int digits) {
        if (typed == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < typed.length() && i < 64; i++) {
            char c = typed.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            if (c < '0' || c > '9') {
                return null;
            }
            out.append(c);
        }
        return out.length() == digits ? out.toString() : null;
    }

    /** The presentation of a code: {@code 123-456} for 6 digits, {@code 1234-5678} for 8 (the value stays the contiguous digits). */
    public static String group(String digits) {
        int half = digits.length() / 2;
        return digits.length() % 2 == 0 ? digits.substring(0, half) + "-" + digits.substring(half) : digits;
    }

    // ------------------------------------------------------------------ wrapping

    private static byte[] wrapKey(String label, byte[] sid, byte[] isk) {
        MessageDigest sha = sha256();
        sha.update(label.getBytes(StandardCharsets.UTF_8));
        sha.update(sid);
        sha.update(isk);
        return sha.digest();
    }

    private static byte[] wrap(NoiseSuite suite, String label, byte[] sid, byte[] isk, byte[] value) throws NoiseException {
        byte[] key = wrapKey(label, sid, isk);
        try {
            return suite.encrypt(key, 0L, new byte[0], value); // a nonce of 12 zero bytes, no associated data
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] unwrap(NoiseSuite suite, String label, byte[] sid, byte[] isk, byte[] wrapped) throws NoiseException {
        if (wrapped == null || wrapped.length != WRAPPED_BYTES) {
            throw new NoiseException("bad wrapping");
        }
        byte[] key = wrapKey(label, sid, isk);
        try {
            return suite.decrypt(key, 0L, new byte[0], wrapped);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The new long-term PSK, sealed for {@code client/pair-finalize}. */
    public static byte[] wrapPsk(NoiseSuite suite, byte[] sid, byte[] isk, byte[] psk) throws NoiseException {
        return wrap(suite, LABEL_WRAP_PSK, sid, isk, psk);
    }

    public static byte[] unwrapPsk(NoiseSuite suite, byte[] sid, byte[] isk, byte[] wrapped) throws NoiseException {
        return unwrap(suite, LABEL_WRAP_PSK, sid, isk, wrapped);
    }

    /** The opening of the commitment, sealed for {@code client/pair-confirm}. */
    public static byte[] wrapNonce(NoiseSuite suite, byte[] sid, byte[] isk, byte[] nonceB) throws NoiseException {
        return wrap(suite, LABEL_WRAP_NONCE, sid, isk, nonceB);
    }

    public static byte[] unwrapNonce(NoiseSuite suite, byte[] sid, byte[] isk, byte[] wrapped) throws NoiseException {
        return unwrap(suite, LABEL_WRAP_NONCE, sid, isk, wrapped);
    }
}
