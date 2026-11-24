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

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two cipher suites of the protocol (the {@code <DH>_<cipher>_<hash>} part of the Noise protocol name): ChaCha20-Poly1305 from
 * the lightweight API of the library (the platform has it from API 28 only) and AES-GCM from the platform. Both use a 16 byte tag;
 * the nonce is 4 zero bytes and the 64 bit counter, little endian for ChaChaPoly and big endian for AESGCM (Noise, section 12).
 */
public enum NoiseSuite {
    CHACHA_POLY("25519_ChaChaPoly_SHA256", false),
    AES_GCM("25519_AESGCM_SHA256", true);

    public static final int TAG_LENGTH = 16;
    public static final int KEY_LENGTH = 32;

    private final String wireName;
    private final boolean bigEndianNonce;

    NoiseSuite(String wireName, boolean bigEndianNonce) {
        this.wireName = wireName;
        this.bigEndianNonce = bigEndianNonce;
    }

    /** The value of {@code suite} in {@code client/init}. */
    public String wireName() {
        return wireName;
    }

    /** The full Noise protocol name of the handshake. */
    public String protocolName() {
        return "Noise_KKpsk2_" + wireName;
    }

    /** @return the suite of the wire name, or null for a name this implementation does not know */
    public static NoiseSuite fromWireName(String name) {
        for (NoiseSuite suite : values()) {
            if (suite.wireName.equals(name)) {
                return suite;
            }
        }
        return null;
    }

    private byte[] nonce(long counter) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) {
            int shift = bigEndianNonce ? (7 - i) * 8 : i * 8;
            nonce[4 + i] = (byte) (counter >>> shift);
        }
        return nonce;
    }

    byte[] encrypt(byte[] key, long counter, byte[] ad, byte[] plaintext) throws NoiseException {
        try {
            if (this == CHACHA_POLY) {
                ChaCha20Poly1305 aead = new ChaCha20Poly1305();
                aead.init(true, new AEADParameters(new KeyParameter(key), TAG_LENGTH * 8, nonce(counter), ad));
                byte[] out = new byte[aead.getOutputSize(plaintext.length)];
                int n = aead.processBytes(plaintext, 0, plaintext.length, out, 0);
                n += aead.doFinal(out, n);
                return n == out.length ? out : java.util.Arrays.copyOf(out, n);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH * 8, nonce(counter)));
            cipher.updateAAD(ad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException | InvalidCipherTextException | RuntimeException e) {
            throw new NoiseException("encryption failed");
        }
    }

    byte[] decrypt(byte[] key, long counter, byte[] ad, byte[] ciphertext) throws NoiseException {
        if (ciphertext.length < TAG_LENGTH) {
            throw new NoiseException("message too short");
        }
        try {
            if (this == CHACHA_POLY) {
                ChaCha20Poly1305 aead = new ChaCha20Poly1305();
                aead.init(false, new AEADParameters(new KeyParameter(key), TAG_LENGTH * 8, nonce(counter), ad));
                byte[] out = new byte[aead.getOutputSize(ciphertext.length)];
                int n = aead.processBytes(ciphertext, 0, ciphertext.length, out, 0);
                n += aead.doFinal(out, n);
                return n == out.length ? out : java.util.Arrays.copyOf(out, n);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH * 8, nonce(counter)));
            cipher.updateAAD(ad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | InvalidCipherTextException | RuntimeException e) {
            throw new NoiseException("decryption failed");
        }
    }
}
