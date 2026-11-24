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

import java.security.SecureRandom;
import java.util.Arrays;

/** Curve25519 key pairs and the Diffie-Hellman function of the Noise suites, on the lightweight API of the library. */
public final class X25519Keys {

    public static final int KEY_LENGTH = 32;

    private X25519Keys() {
    }

    /** A key pair; {@link #destroy()} zeroes the private key. */
    public static final class KeyPair {
        private final byte[] privateKey;
        private final byte[] publicKey;

        private KeyPair(byte[] privateKey, byte[] publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        /** @return the public key (32 bytes, a copy) */
        public byte[] publicKey() {
            return publicKey.clone();
        }

        byte[] privateKeyBytes() {
            return privateKey;
        }

        /** @return the private key (32 bytes, a copy): only for storing it wrapped, never for logging */
        public byte[] exportPrivateKey() {
            return privateKey.clone();
        }

        public void destroy() {
            Arrays.fill(privateKey, (byte) 0);
        }
    }

    public static KeyPair generate(SecureRandom random) {
        byte[] priv = new byte[KEY_LENGTH];
        X25519.generatePrivateKey(random, priv);
        return fromPrivate(priv);
    }

    /** @param privateKey 32 bytes (copied, clamped as the function requires) */
    public static KeyPair fromPrivate(byte[] privateKey) {
        if (privateKey == null || privateKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("a private key has 32 bytes");
        }
        byte[] priv = privateKey.clone();
        byte[] pub = new byte[KEY_LENGTH];
        X25519.scalarMultBase(priv, 0, pub, 0);
        return new KeyPair(priv, pub);
    }

    /** @throws NoiseException if the public key is not 32 bytes or the result is all zero (a point of small order) */
    static byte[] dh(byte[] privateKey, byte[] publicKey) throws NoiseException {
        if (publicKey == null || publicKey.length != KEY_LENGTH) {
            throw new NoiseException("bad public key");
        }
        byte[] out = new byte[KEY_LENGTH];
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0)) {
            throw new NoiseException("bad public key");
        }
        return out;
    }
}
