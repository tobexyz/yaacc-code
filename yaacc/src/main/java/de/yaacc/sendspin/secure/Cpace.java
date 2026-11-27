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

import org.bouncycastle.math.ec.rfc7748.X25519;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * CPACE-X25519-SHA512 as the Sendspin specification uses it for the code-based pairing (issue #325, task 11.1): the group of
 * {@code G_X25519} with the Elligator 2 map of RFC 9380, SHA-512, the initiator/responder transcript, and the key confirmation that
 * the draft-irtf-cfrg-cpace-21 suggests (a MAC key from the intermediate session key, HMAC-SHA-512 over the sender's own message).
 * Checked against the draft's test vectors in {@code CpaceTest}. The primitives (X25519, SHA-512, HMAC) are the platform's and the
 * library's; nothing of this is a curve or a hash of its own. Sendspin's instantiation: CI empty, {@code ADa} "server", {@code ADb}
 * "client".
 */
public final class Cpace {

    public static final int FIELD_BYTES = 32;
    private static final byte[] DSI = "CPace255".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DSI_ISK = "CPace255_ISK".getBytes(StandardCharsets.UTF_8);
    private static final int HASH_BLOCK_BYTES = 128; // the input block of SHA-512

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A = BigInteger.valueOf(486662);
    private static final BigInteger Z = BigInteger.valueOf(2); // the non-square of Curve25519's Elligator 2 (RFC 9380)
    private static final BigInteger HALF = BigInteger.valueOf(2).modInverse(P);

    private Cpace() {
    }

    // ------------------------------------------------------------------ the string functions

    /** The length of {@code data} as LEB128, then {@code data}. */
    static byte[] prependLen(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = data.length;
        while (true) {
            if (length < 128) {
                out.write(length);
            } else {
                out.write((length & 0x7f) + 0x80);
            }
            length >>>= 7;
            if (length == 0) {
                break;
            }
        }
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    static byte[] lvCat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            byte[] encoded = prependLen(part);
            out.write(encoded, 0, encoded.length);
        }
        return out.toByteArray();
    }

    static byte[] transcriptIr(byte[] ya, byte[] ada, byte[] yb, byte[] adb) {
        byte[] first = lvCat(ya, ada);
        byte[] second = lvCat(yb, adb);
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    // ------------------------------------------------------------------ the generator

    /** {@code lv_cat(DSI, PRS, zero_bytes(len_zpad), CI, sid)}: the zero padding fills the first block of the hash. */
    static byte[] generatorString(byte[] prs, byte[] ci, byte[] sid) {
        int zpad = Math.max(0, HASH_BLOCK_BYTES - 1 - prependLen(prs).length - prependLen(DSI).length);
        return lvCat(DSI, prs, new byte[zpad], ci, sid);
    }

    /** {@code G_X25519.calculate_generator}: hash, decode as a u-coordinate, map with Elligator 2; the u-coordinate of the point. */
    public static byte[] generator(byte[] prs, byte[] ci, byte[] sid) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-512").digest(generatorString(prs, ci, sid));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512 is always there", e);
        }
        byte[] hashed = Arrays.copyOf(digest, FIELD_BYTES);
        hashed[FIELD_BYTES - 1] &= 0x7f; // decodeUCoordinate(…, 255): the unused bit is ignored
        BigInteger r = littleEndian(hashed).mod(P);
        return encode(elligator2(r));
    }

    /** The map of RFC 9380 for Curve25519 (the reference code of the draft): a field element to the u-coordinate of a curve point. */
    private static BigInteger elligator2(BigInteger r) {
        BigInteger denominator = BigInteger.ONE.add(Z.multiply(r).multiply(r)).mod(P);
        BigInteger v = A.negate().multiply(denominator.modInverse(P)).mod(P);
        BigInteger rhs = v.pow(3).add(A.multiply(v).multiply(v)).add(v).mod(P);
        BigInteger epsilon = rhs.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P); // the Legendre symbol: 1, p-1 or 0
        if (epsilon.equals(P.subtract(BigInteger.ONE))) {
            epsilon = BigInteger.ONE.negate();
        }
        return epsilon.multiply(v).subtract(BigInteger.ONE.subtract(epsilon).multiply(A).multiply(HALF)).mod(P);
    }

    private static BigInteger littleEndian(byte[] bytes) {
        byte[] big = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            big[i] = bytes[bytes.length - 1 - i];
        }
        return new BigInteger(1, big);
    }

    private static byte[] encode(BigInteger value) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[FIELD_BYTES];
        for (int i = 0; i < FIELD_BYTES; i++) {
            int index = big.length - 1 - i;
            out[i] = index >= 0 ? big[index] : 0;
        }
        return out;
    }

    // ------------------------------------------------------------------ the group

    /** {@code X25519(y, u)} (RFC 7748: the scalar is clamped, bit 255 of the coordinate is ignored); no check of the result. */
    public static byte[] scalarMult(byte[] y, byte[] u) {
        byte[] out = new byte[FIELD_BYTES];
        X25519.scalarMult(y, 0, u, 0, out, 0);
        return out;
    }

    /**
     * {@code G_X25519.scalar_mult_vfy}: as {@link #scalarMult} and the abort condition of CPace: the result is the neutral element (all
     * zero), which a point of small order gives.
     *
     * @throws CpaceException if the share has the wrong length or encodes a point of small order
     */
    public static byte[] scalarMultVfy(byte[] y, byte[] u) throws CpaceException {
        if (u == null || u.length != FIELD_BYTES) {
            throw new CpaceException("bad share");
        }
        byte[] out = scalarMult(y, u);
        int or = 0;
        for (byte b : out) {
            or |= b;
        }
        if (or == 0) {
            throw new CpaceException("point of small order");
        }
        return out;
    }

    // ------------------------------------------------------------------ the key and its confirmation

    /** {@code ISK = H(lv_cat(DSI || "_ISK", sid, K) || transcript_ir(Ya, ADa, Yb, ADb))}, 64 bytes. */
    static byte[] isk(byte[] sid, byte[] k, byte[] ya, byte[] ada, byte[] yb, byte[] adb) {
        byte[] first = lvCat(DSI_ISK, sid, k);
        byte[] second = transcriptIr(ya, ada, yb, adb);
        byte[] input = new byte[first.length + second.length];
        System.arraycopy(first, 0, input, 0, first.length);
        System.arraycopy(second, 0, input, first.length, second.length);
        return sha512(input);
    }

    private static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512 is always there", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-512 is always there", e);
        }
    }

    // ------------------------------------------------------------------ one party of a run

    /** One party of a CPace run: its share, the key it derived and the tags. {@link #destroy()} zeroes the secrets. */
    public static final class Party {
        private static final byte[] AD_SERVER = "server".getBytes(StandardCharsets.UTF_8);
        private static final byte[] AD_CLIENT = "client".getBytes(StandardCharsets.UTF_8);

        private final boolean initiator;
        private final byte[] sid;
        private byte[] scalar;
        private final byte[] share;
        private byte[] peerShare;
        private byte[] isk;
        private byte[] macKey;

        private Party(boolean initiator, byte[] prs, byte[] sid, SecureRandom random) {
            this.initiator = initiator;
            this.sid = sid.clone();
            byte[] g = generator(prs, new byte[0], sid);
            this.scalar = new byte[FIELD_BYTES];
            random.nextBytes(scalar);
            this.share = scalarMult(scalar, g);
        }

        /** This party's public share ({@code Ya} for the initiator, {@code Yb} for the responder), 32 bytes. */
        public byte[] share() {
            return share.clone();
        }

        /**
         * Takes the other party's share and derives the intermediate session key.
         *
         * @return the 64-byte ISK (a copy)
         * @throws CpaceException if the share must make the run abort
         */
        public byte[] finish(byte[] otherShare) throws CpaceException {
            byte[] k = scalarMultVfy(scalar, otherShare);
            peerShare = otherShare.clone();
            byte[] ya = initiator ? share : peerShare;
            byte[] yb = initiator ? peerShare : share;
            isk = Cpace.isk(sid, k, ya, AD_SERVER, yb, AD_CLIENT);
            macKey = sha512("CPaceMac".getBytes(StandardCharsets.UTF_8), sid, isk);
            Arrays.fill(k, (byte) 0);
            Arrays.fill(scalar, (byte) 0);
            return isk.clone();
        }

        /** The 64-byte ISK once {@link #finish} was called (a copy). */
        public byte[] sessionKey() {
            return isk.clone();
        }

        /** The session id of the run (a copy). */
        public byte[] sessionId() {
            return sid.clone();
        }

        /** This party's confirmation tag: HMAC-SHA-512 over its own message ({@code Ta} or {@code Tb}), 64 bytes. */
        public byte[] tag() {
            return hmac(macKey, lvCat(share, initiator ? AD_SERVER : AD_CLIENT));
        }

        /** Whether the other party's tag is the one this party expects for the other's message (constant time). */
        public boolean verifyPeerTag(byte[] tag) {
            byte[] expected = hmac(macKey, lvCat(peerShare, initiator ? AD_CLIENT : AD_SERVER));
            return tag != null && MessageDigest.isEqual(expected, tag);
        }

        public void destroy() {
            Arrays.fill(scalar, (byte) 0);
            if (isk != null) {
                Arrays.fill(isk, (byte) 0);
            }
            if (macKey != null) {
                Arrays.fill(macKey, (byte) 0);
            }
        }
    }

    /** The server's side (role A, the initiator) of a run with the pairing code {@code prs} and the session id {@code sid}. */
    public static Party initiator(byte[] prs, byte[] sid, SecureRandom random) {
        return new Party(true, prs, sid, random);
    }

    /** The client's side (role B, the responder). */
    public static Party responder(byte[] prs, byte[] sid, SecureRandom random) {
        return new Party(false, prs, sid, random);
    }
}
