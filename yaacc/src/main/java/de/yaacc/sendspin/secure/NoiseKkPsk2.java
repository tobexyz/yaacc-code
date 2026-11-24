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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Noise handshake {@code KKpsk2} (Noise Protocol Framework, revision 34) that every Sendspin connection uses:
 * <pre>
 *   -&gt; s            (pre-message: the static key of the initiator, the server)
 *   &lt;- s            (pre-message: the static key of the responder, the client)
 *   ...
 *   -&gt; e, es, ss
 *   &lt;- e, ee, se, psk
 * </pre>
 * With a {@code psk} token in the pattern every {@code e} token also mixes the public ephemeral key into the chaining key
 * (section 9.2 of the Noise specification). The PSK is mixed in by the second message, which is why the first message can be read
 * before the responder has chosen the PSK. The hash is SHA-256, the suites are {@link NoiseSuite}.
 * <p>
 * The initiator writes message 1 and reads message 2, the responder reads message 1 and writes message 2; afterwards
 * {@link #split()} gives the transport. An instance is used by one thread, for one handshake. The secrets of the handshake are
 * zeroed by {@link #destroy()}, which {@link #split()} calls; the static key is the caller's and stays.
 */
public final class NoiseKkPsk2 {

    private static final int HASH_LENGTH = 32;

    private final NoiseSuite suite;
    private final boolean initiator;
    private final X25519Keys.KeyPair staticKey;
    private final byte[] remoteStatic;
    private X25519Keys.KeyPair ephemeral;
    private byte[] remoteEphemeral;

    // the symmetric state
    private byte[] chainingKey;
    private byte[] hash;
    private byte[] cipherKey; // null while empty
    private long cipherNonce;
    private boolean destroyed;

    private NoiseKkPsk2(NoiseSuite suite, boolean initiator, byte[] prologue, X25519Keys.KeyPair staticKey, byte[] remoteStatic) {
        this.suite = suite;
        this.initiator = initiator;
        this.staticKey = staticKey;
        this.remoteStatic = remoteStatic.clone();
        byte[] name = suite.protocolName().getBytes(StandardCharsets.US_ASCII);
        if (name.length <= HASH_LENGTH) {
            hash = Arrays.copyOf(name, HASH_LENGTH);
        } else {
            hash = sha256(name);
        }
        chainingKey = hash.clone();
        mixHash(prologue);
        // the pre-messages: the static key of the initiator first, then the one of the responder
        mixHash(initiator ? staticKey.publicKey() : remoteStatic);
        mixHash(initiator ? remoteStatic : staticKey.publicKey());
    }

    /** The server's side. @param prologue the exact bytes of {@code client/init} followed by those of {@code server/init} */
    public static NoiseKkPsk2 initiator(NoiseSuite suite, byte[] prologue, X25519Keys.KeyPair staticKey, byte[] remoteStatic) {
        return new NoiseKkPsk2(suite, true, prologue, staticKey, remoteStatic);
    }

    /** The client's side. */
    public static NoiseKkPsk2 responder(NoiseSuite suite, byte[] prologue, X25519Keys.KeyPair staticKey, byte[] remoteStatic) {
        return new NoiseKkPsk2(suite, false, prologue, staticKey, remoteStatic);
    }

    // ------------------------------------------------------------------ the messages

    /** Message 1, written by the initiator: {@code e, es, ss} and the payload. */
    public byte[] writeMessage1(byte[] payload, SecureRandom random) throws NoiseException {
        requireRole(true);
        ephemeral = X25519Keys.generate(random);
        byte[] ePub = ephemeral.publicKey();
        mixHash(ePub);
        mixKey(ePub);
        mixKey(X25519Keys.dh(ephemeral.privateKeyBytes(), remoteStatic)); // es
        mixKey(X25519Keys.dh(staticKey.privateKeyBytes(), remoteStatic)); // ss
        return concat(ePub, encryptAndHash(payload));
    }

    /** Message 1, read by the responder; @return the payload (not yet authenticated by the PSK, which the second message adds). */
    public byte[] readMessage1(byte[] message) throws NoiseException {
        requireRole(false);
        if (message.length < X25519Keys.KEY_LENGTH + NoiseSuite.TAG_LENGTH) {
            throw new NoiseException("message too short");
        }
        remoteEphemeral = Arrays.copyOfRange(message, 0, X25519Keys.KEY_LENGTH);
        mixHash(remoteEphemeral);
        mixKey(remoteEphemeral);
        mixKey(X25519Keys.dh(staticKey.privateKeyBytes(), remoteEphemeral)); // es
        mixKey(X25519Keys.dh(staticKey.privateKeyBytes(), remoteStatic)); // ss
        return decryptAndHash(Arrays.copyOfRange(message, X25519Keys.KEY_LENGTH, message.length));
    }

    /** Message 2, written by the responder: {@code e, ee, se, psk} and the payload. */
    public byte[] writeMessage2(byte[] payload, byte[] psk, SecureRandom random) throws NoiseException {
        requireRole(false);
        requirePsk(psk);
        ephemeral = X25519Keys.generate(random);
        byte[] ePub = ephemeral.publicKey();
        mixHash(ePub);
        mixKey(ePub);
        mixKey(X25519Keys.dh(ephemeral.privateKeyBytes(), remoteEphemeral)); // ee
        mixKey(X25519Keys.dh(ephemeral.privateKeyBytes(), remoteStatic)); // se (the static key of the initiator, our ephemeral)
        mixKeyAndHash(psk);
        return concat(ePub, encryptAndHash(payload));
    }

    /**
     * Message 2, read by the initiator; @return the payload. A message that does not verify leaves this object unusable; to try
     * another PSK on the same message (the Sentinel fallback of the protocol) use {@link #readMessage2Any(byte[], byte[]...)}.
     */
    public byte[] readMessage2(byte[] message, byte[] psk) throws NoiseException {
        requireRole(true);
        requirePsk(psk);
        if (message.length < X25519Keys.KEY_LENGTH + NoiseSuite.TAG_LENGTH) {
            throw new NoiseException("message too short");
        }
        remoteEphemeral = Arrays.copyOfRange(message, 0, X25519Keys.KEY_LENGTH);
        mixHash(remoteEphemeral);
        mixKey(remoteEphemeral);
        mixKey(X25519Keys.dh(ephemeral.privateKeyBytes(), remoteEphemeral)); // ee
        mixKey(X25519Keys.dh(staticKey.privateKeyBytes(), remoteEphemeral)); // se (our static key, the ephemeral of the responder)
        mixKeyAndHash(psk);
        return decryptAndHash(Arrays.copyOfRange(message, X25519Keys.KEY_LENGTH, message.length));
    }

    /**
     * Message 2 against several PSKs in order, from the state after message 1: the first that verifies wins. The server uses it to
     * try the PSK its first message referenced and then the Sentinel PSK (credential mismatch).
     *
     * @return the index of the PSK that verified; the payload is in {@link #lastPayload()}
     * @throws NoiseException if none verifies; this object is then unusable
     */
    public int readMessage2Any(byte[] message, byte[]... psks) throws NoiseException {
        requireRole(true);
        byte[] savedChaining = chainingKey.clone();
        byte[] savedHash = hash.clone();
        byte[] savedKey = cipherKey == null ? null : cipherKey.clone();
        long savedNonce = cipherNonce;
        for (int i = 0; i < psks.length; i++) {
            try {
                lastPayload = readMessage2(message, psks[i]);
                return i;
            } catch (NoiseException e) {
                // restore the state after message 1 and try the next PSK
                chainingKey = savedChaining.clone();
                hash = savedHash.clone();
                cipherKey = savedKey == null ? null : savedKey.clone();
                cipherNonce = savedNonce;
            }
        }
        destroy();
        throw new NoiseException("handshake failed");
    }

    private byte[] lastPayload;

    /** The payload of the message 2 that {@link #readMessage2Any(byte[], byte[]...)} read. */
    public byte[] lastPayload() {
        return lastPayload == null ? null : lastPayload.clone();
    }

    /** The handshake hash {@code h}: the prologue of a re-handshake in transport mode. Valid after message 2 was written or read. */
    public byte[] handshakeHash() {
        return hash.clone();
    }

    /** Ends the handshake: the two transport ciphers; the handshake state is zeroed. */
    public NoiseTransport split() {
        byte[][] keys = hkdf(chainingKey, new byte[0], 2);
        NoiseTransport transport = initiator
                ? new NoiseTransport(suite, keys[0], keys[1], hash.clone())
                : new NoiseTransport(suite, keys[1], keys[0], hash.clone());
        destroy();
        return transport;
    }

    public void destroy() {
        destroyed = true;
        wipe(chainingKey);
        wipe(hash);
        wipe(cipherKey);
        // the static key belongs to the caller (the identity outlives the handshake): not zeroed here
        if (ephemeral != null) {
            ephemeral.destroy();
        }
    }

    // ------------------------------------------------------------------ the symmetric state

    private void requireRole(boolean wantInitiator) throws NoiseException {
        if (destroyed || initiator != wantInitiator) {
            throw new NoiseException("wrong state");
        }
    }

    private static void requirePsk(byte[] psk) throws NoiseException {
        if (psk == null || psk.length != 32) {
            throw new NoiseException("bad psk");
        }
    }

    private void mixHash(byte[] data) {
        hash = sha256(concat(hash, data));
    }

    private void mixKey(byte[] inputKeyMaterial) {
        byte[][] out = hkdf(chainingKey, inputKeyMaterial, 2);
        chainingKey = out[0];
        initializeKey(out[1]);
    }

    private void mixKeyAndHash(byte[] inputKeyMaterial) {
        byte[][] out = hkdf(chainingKey, inputKeyMaterial, 3);
        chainingKey = out[0];
        mixHash(out[1]);
        initializeKey(out[2]);
    }

    private void initializeKey(byte[] key) {
        cipherKey = key;
        cipherNonce = 0;
    }

    private byte[] encryptAndHash(byte[] plaintext) throws NoiseException {
        byte[] ciphertext = cipherKey == null ? plaintext.clone() : suite.encrypt(cipherKey, cipherNonce++, hash, plaintext);
        mixHash(ciphertext);
        return ciphertext;
    }

    private byte[] decryptAndHash(byte[] ciphertext) throws NoiseException {
        byte[] plaintext = cipherKey == null ? ciphertext.clone() : suite.decrypt(cipherKey, cipherNonce++, hash, ciphertext);
        mixHash(ciphertext);
        return plaintext;
    }

    // ------------------------------------------------------------------ SHA-256, HMAC and the HKDF of Noise

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is part of every platform");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256")); // an empty key is the same as a zero byte key
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every platform");
        }
    }

    /** HKDF of the Noise specification (section 4.3) with two or three outputs. */
    static byte[][] hkdf(byte[] chainingKey, byte[] inputKeyMaterial, int outputs) {
        byte[] tempKey = hmac(chainingKey, inputKeyMaterial);
        byte[] out1 = hmac(tempKey, new byte[]{1});
        byte[] out2 = hmac(tempKey, concat(out1, new byte[]{2}));
        if (outputs == 2) {
            return new byte[][]{out1, out2};
        }
        byte[] out3 = hmac(tempKey, concat(out2, new byte[]{3}));
        return new byte[][]{out1, out2, out3};
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
