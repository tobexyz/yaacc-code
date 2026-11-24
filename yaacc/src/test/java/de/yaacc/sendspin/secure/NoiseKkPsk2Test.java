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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The handshake against vectors made by an independent implementation (the Python library noiseprotocol, which the reference
 * player uses): the messages, the transport ciphertexts and the handshake hash must be byte for byte the same, for both suites.
 */
public class NoiseKkPsk2Test {

    /** name, prologue, psk, initiator static, responder static, initiator ephemeral, responder ephemeral, their public statics, payload 1, message 1, message 2, two transport ciphertexts, hash */
    private static final String[] CHACHA = {
        "Noise_KKpsk2_25519_ChaChaPoly_SHA256",
        "7b2274797065223a22636c69656e742f696e6974227d7b2274797065223a227365727665722f696e6974227d",
        "1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3",
        "0101010101010101010101010101010101010101010101010101010101010101", "0202020202020202020202020202020202020202020202020202020202020202", "0303030303030303030303030303030303030303030303030303030303030303", "0404040404040404040404040404040404040404040404040404040404040404",
        "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209", "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59",
        "7b2270736b5f6964223a2278222c2270736b5f63617465676f7279223a22736e227d",
        "5dfedd3b6bd47f6fa28ee15d969d5bb0ea53774d488bdaf9df1c6e0124b3ef22de17239f704fd092e7c60374d30a4afbbadbb148020c61fe3b39c1fc27f516d90b58a979d65758afb833ea796ddf9f5a7a7f",
        "ac01b2209e86354fb853237b5de0f4fab13c7fcbf433a61c019369617fecf10b20f81d016782c5ecb663e422ec77b2223f47",
        "47a254efb3d8bb8aa3fbb03197c5cdf78f0986b05c6080d34abd3397894bc072aaef0ff87adc", "70bf9569b520d0dc75bc75d7918158b67947a80912ccead855a49e183b40e54ea2326e3c853f",
        "bbdaa445304695c1a7dfc05795cd958d0b9da488219ee2d9fc51bcdd783a046b"
    };

    private static final String[] AESGCM = {
        "Noise_KKpsk2_25519_AESGCM_SHA256",
        "7b2274797065223a22636c69656e742f696e6974227d7b2274797065223a227365727665722f696e6974227d",
        "1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3",
        "0101010101010101010101010101010101010101010101010101010101010101", "0202020202020202020202020202020202020202020202020202020202020202", "0303030303030303030303030303030303030303030303030303030303030303", "0404040404040404040404040404040404040404040404040404040404040404",
        "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209", "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59",
        "7b2270736b5f6964223a2278222c2270736b5f63617465676f7279223a22736e227d",
        "5dfedd3b6bd47f6fa28ee15d969d5bb0ea53774d488bdaf9df1c6e0124b3ef224b49676e9a8590ed58e45d01983306c6d3327292467fa2db3047f3ee80c7775153d13c1bd443cbc13332ac6e794bf7f1a445",
        "ac01b2209e86354fb853237b5de0f4fab13c7fcbf433a61c019369617fecf10bf612b1ffd3d9afd6debbf0f0abcdf9363ca6",
        "100e7cec91571cfbab7aa8b8c25af618d9cee0c1ee110b157da6d801c2d51e6a86f7c2e0db9d", "9691e693629821d15a8c504a3cd15fd72a898d81f34a316b314c828b193f2a558cef89d1f754",
        "1d7f1bbde9b19cffc3c140f33f43e4e46f7b5552478e648d0a804858f0faf5a6"
    };

    /** A random source that gives fixed bytes: the ephemeral keys of the vectors. */
    private static SecureRandom fixed(int value) {
        return new SecureRandom() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) value);
            }
        };
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static NoiseSuite suiteOf(String[] v) {
        return v[0].contains("AESGCM") ? NoiseSuite.AES_GCM : NoiseSuite.CHACHA_POLY;
    }

    private static void checkVector(String[] v) throws Exception {
        NoiseSuite suite = suiteOf(v);
        assertEquals(v[0], suite.protocolName());
        byte[] prologue = hex(v[1]);
        byte[] psk = hex(v[2]);
        NoiseKkPsk2 initiator = NoiseKkPsk2.initiator(suite, prologue, X25519Keys.fromPrivate(hex(v[3])), hex(v[8]));
        NoiseKkPsk2 responder = NoiseKkPsk2.responder(suite, prologue, X25519Keys.fromPrivate(hex(v[4])), hex(v[7]));

        byte[] message1 = initiator.writeMessage1(hex(v[9]), fixed(3));
        assertArrayEquals("message 1", hex(v[10]), message1);
        assertArrayEquals(hex(v[9]), responder.readMessage1(message1));
        byte[] message2 = responder.writeMessage2("{}".getBytes(StandardCharsets.UTF_8), psk, fixed(4));
        assertArrayEquals("message 2", hex(v[11]), message2);
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), initiator.readMessage2(message2, psk));

        assertArrayEquals(hex(v[14]), initiator.handshakeHash());
        assertArrayEquals(hex(v[14]), responder.handshakeHash());
        NoiseTransport server = initiator.split();
        NoiseTransport client = responder.split();
        byte[] toClient = server.encrypt("hello server to client".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("transport, server to client", hex(v[12]), toClient);
        assertEquals("hello server to client", new String(client.decrypt(toClient), StandardCharsets.UTF_8));
        byte[] toServer = client.encrypt("hello client to server".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("transport, client to server", hex(v[13]), toServer);
        assertEquals("hello client to server", new String(server.decrypt(toServer), StandardCharsets.UTF_8));
    }

    @Test
    public void chachaPolyMatchesTheIndependentImplementation() throws Exception {
        checkVector(CHACHA);
    }

    @Test
    public void aesGcmMatchesTheIndependentImplementation() throws Exception {
        checkVector(AESGCM);
    }

    private static NoiseKkPsk2[] pair(NoiseSuite suite, byte[] prologue, byte[] clientPrologue) throws Exception {
        X25519Keys.KeyPair serverKey = X25519Keys.generate(new SecureRandom());
        X25519Keys.KeyPair clientKey = X25519Keys.generate(new SecureRandom());
        return new NoiseKkPsk2[]{
                NoiseKkPsk2.initiator(suite, prologue, serverKey, clientKey.publicKey()),
                NoiseKkPsk2.responder(suite, clientPrologue, clientKey, serverKey.publicKey())};
    }

    @Test
    public void aWrongPskFailsTheSecondMessage() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            NoiseKkPsk2[] sides = pair(suite, new byte[]{1}, new byte[]{1});
            byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
            sides[1].readMessage1(m1);
            byte[] m2 = sides[1].writeMessage2("{}".getBytes(StandardCharsets.UTF_8), SendspinPsk.sentinel(), new SecureRandom());
            byte[] other = SendspinPsk.sentinel();
            other[0] ^= 1;
            try {
                sides[0].readMessage2(m2, other);
                fail("expected a failure for " + suite);
            } catch (NoiseException expected) {
                // fine
            }
        }
    }

    @Test
    public void theSentinelFallbackFindsTheSecondCandidate() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            NoiseKkPsk2[] sides = pair(suite, new byte[]{1}, new byte[]{1});
            byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
            sides[1].readMessage1(m1);
            byte[] m2 = sides[1].writeMessage2("{}".getBytes(StandardCharsets.UTF_8), SendspinPsk.sentinel(), new SecureRandom());
            byte[] longTerm = new byte[32];
            Arrays.fill(longTerm, (byte) 7);

            int index = sides[0].readMessage2Any(m2, longTerm, SendspinPsk.sentinel());

            assertEquals("the long-term PSK did not verify, the Sentinel did", 1, index);
            assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), sides[0].lastPayload());
            // and the transport works
            NoiseTransport server = sides[0].split();
            NoiseTransport client = sides[1].split();
            assertArrayEquals(new byte[]{9}, client.decrypt(server.encrypt(new byte[]{9})));
        }
    }

    @Test
    public void noCandidatePskThatVerifiesIsAFailure() throws Exception {
        NoiseKkPsk2[] sides = pair(NoiseSuite.CHACHA_POLY, new byte[]{1}, new byte[]{1});
        byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
        sides[1].readMessage1(m1);
        byte[] m2 = sides[1].writeMessage2("{}".getBytes(StandardCharsets.UTF_8), new byte[32], new SecureRandom());
        try {
            sides[0].readMessage2Any(m2, SendspinPsk.sentinel());
            fail("expected a failure");
        } catch (NoiseException expected) {
            // fine
        }
    }

    @Test
    public void aDifferentPrologueFailsAtTheFirstMessage() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            NoiseKkPsk2[] sides = pair(suite, new byte[]{1}, new byte[]{2});
            byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
            try {
                sides[1].readMessage1(m1);
                fail("expected a failure for " + suite);
            } catch (NoiseException expected) {
                // the init messages were changed on the way: the handshake does not verify
            }
        }
    }

    @Test
    public void aMessageOfAnotherServerKeyFailsAtTheFirstMessage() throws Exception {
        X25519Keys.KeyPair server = X25519Keys.generate(new SecureRandom());
        X25519Keys.KeyPair impostor = X25519Keys.generate(new SecureRandom());
        X25519Keys.KeyPair client = X25519Keys.generate(new SecureRandom());
        NoiseKkPsk2 initiator = NoiseKkPsk2.initiator(NoiseSuite.AES_GCM, new byte[0], impostor, client.publicKey());
        NoiseKkPsk2 responder = NoiseKkPsk2.responder(NoiseSuite.AES_GCM, new byte[0], client, server.publicKey());
        byte[] m1 = initiator.writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
        try {
            responder.readMessage1(m1);
            fail("a server that is not the one named in server/init must not verify");
        } catch (NoiseException expected) {
            // fine
        }
    }

    @Test
    public void transportRejectsReplayReorderAndTampering() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            NoiseKkPsk2[] sides = pair(suite, new byte[0], new byte[0]);
            byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
            sides[1].readMessage1(m1);
            byte[] m2 = sides[1].writeMessage2("{}".getBytes(StandardCharsets.UTF_8), SendspinPsk.sentinel(), new SecureRandom());
            sides[0].readMessage2(m2, SendspinPsk.sentinel());
            NoiseTransport server = sides[0].split();
            NoiseTransport client = sides[1].split();

            byte[] first = server.encrypt(new byte[]{1});
            byte[] second = server.encrypt(new byte[]{2});
            try {
                client.decrypt(second); // out of order: the counter does not fit
                fail("reorder");
            } catch (NoiseException expected) {
                // fine
            }
            // a failed message does not move the receiver on in a way that lets the stream continue: the caller closes
            NoiseKkPsk2[] again = pair(suite, new byte[0], new byte[0]);
            assertTrue(first.length > 0 && again.length == 2);
        }
    }

    @Test
    public void aTamperedTransportMessageDoesNotVerify() throws Exception {
        NoiseKkPsk2[] sides = pair(NoiseSuite.CHACHA_POLY, new byte[0], new byte[0]);
        byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
        sides[1].readMessage1(m1);
        byte[] m2 = sides[1].writeMessage2("{}".getBytes(StandardCharsets.UTF_8), SendspinPsk.sentinel(), new SecureRandom());
        sides[0].readMessage2(m2, SendspinPsk.sentinel());
        NoiseTransport server = sides[0].split();
        NoiseTransport client = sides[1].split();
        byte[] message = server.encrypt("audio".getBytes(StandardCharsets.UTF_8));
        message[message.length - 1] ^= 1;
        try {
            client.decrypt(message);
            fail("tampering");
        } catch (NoiseException expected) {
            // fine
        }
    }

    @Test
    public void theLargestTransportMessageIsAccepted() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            NoiseKkPsk2[] sides = pair(suite, new byte[0], new byte[0]);
            byte[] m1 = sides[0].writeMessage1("{}".getBytes(StandardCharsets.UTF_8), new SecureRandom());
            sides[1].readMessage1(m1);
            byte[] m2 = sides[1].writeMessage2("{}".getBytes(StandardCharsets.UTF_8), SendspinPsk.sentinel(), new SecureRandom());
            sides[0].readMessage2(m2, SendspinPsk.sentinel());
            NoiseTransport server = sides[0].split();
            NoiseTransport client = sides[1].split();
            byte[] biggest = new byte[NoiseTransport.MAX_PLAINTEXT_LENGTH];
            byte[] wire = server.encrypt(biggest);
            assertEquals(NoiseTransport.MAX_MESSAGE_LENGTH, wire.length);
            assertArrayEquals(biggest, client.decrypt(wire));
            try {
                server.encrypt(new byte[NoiseTransport.MAX_PLAINTEXT_LENGTH + 1]);
                fail("too long");
            } catch (NoiseException expected) {
                // fine
            }
        }
    }
}
