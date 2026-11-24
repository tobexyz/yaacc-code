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
package de.yaacc.sendspin.session;

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseException;
import de.yaacc.sendspin.secure.NoiseKkPsk2;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.NoiseTransport;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The server's side of the opening exchange of every connection (spec, "Communication" and "Encryption"): read the cleartext
 * {@code client/init}, answer with {@code server/init} and Noise message 1, read Noise message 2, switch to transport mode. The
 * server is always the Noise initiator, whichever side opened the WebSocket.
 * <p>
 * A failure that follows from the cleartext {@code client/init} alone (version, suite, shape) is answered with
 * {@code server/error} and a close; every other failure is silent: the connection is closed without another message. Both end
 * in a {@link HandshakeException}.
 */
public final class ServerHandshake {

    /** Which PSK the server references for a client, and as which category. */
    public static final class PskChoice {
        private final byte[] psk;
        private final SendspinPsk.Category category;

        public PskChoice(byte[] psk, SendspinPsk.Category category) {
            this.psk = psk.clone();
            this.category = category;
        }

        public static PskChoice sentinel() {
            return new PskChoice(SendspinPsk.sentinel(), SendspinPsk.Category.SENTINEL);
        }

        /** The PSK (a copy). */
        public byte[] psk() {
            return psk.clone();
        }

        public SendspinPsk.Category category() {
            return category;
        }
    }

    /** Chooses the PSK for the client the server is about to speak to (a long-term PSK of a paired client, else the Sentinel). */
    public interface PskResolver {
        PskChoice choose(String clientId);
    }

    /** What the handshake yields. */
    public static final class Result {
        public final SecureChannel channel;
        public final String clientId;
        public final NoiseSuite suite;
        /** The category of the PSK that verified (the Sentinel after a credential mismatch). */
        public final SendspinPsk.Category verifiedAs;
        /** True if the client could not use the PSK that was referenced and answered with the Sentinel (spec, "Sentinel Fallback"). */
        public final boolean credentialMismatch;
        /** The category of the PSK the server referenced in its first message (differs from {@link #verifiedAs} after a mismatch). */
        public final SendspinPsk.Category referenced;

        Result(SecureChannel channel, String clientId, NoiseSuite suite, SendspinPsk.Category verifiedAs, boolean credentialMismatch,
               SendspinPsk.Category referenced) {
            this.referenced = referenced;
            this.channel = channel;
            this.clientId = clientId;
            this.suite = suite;
            this.verifiedAs = verifiedAs;
            this.credentialMismatch = credentialMismatch;
        }
    }

    public static final class HandshakeException extends IOException {
        private static final long serialVersionUID = 1L;

        HandshakeException(String message) {
            super(message);
        }
    }

    private static final int MAX_INIT_BYTES = 1024;
    private static final int MAX_NOISE_BYTES = 4096;
    private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.UTF_8);

    private ServerHandshake() {
    }

    /**
     * @param ws       a connection whose WebSocket handshake is done; its read timeout bounds each step (the spec suggests 30 s)
     * @param identity the server's identity (its static key stays with it; the handshake does not zero it)
     */
    public static Result perform(WsConnection ws, Identity identity, PskResolver psks, SecureRandom random) throws IOException {
        // 1. client/init, cleartext
        WsConnection.Message first = ws.receive();
        if (!first.isText()) {
            throw silent("client/init is not text");
        }
        byte[] clientInitBytes = first.data();
        String clientId;
        NoiseSuite suite;
        try {
            if (clientInitBytes.length > MAX_INIT_BYTES) {
                throw initFailure(ws, "malformed");
            }
            JSONObject root;
            try {
                Envelope envelope = Envelope.parse(clientInitBytes, MAX_INIT_BYTES);
                if (!"client/init".equals(envelope.type())) {
                    throw initFailure(ws, "malformed");
                }
                root = envelope.payload();
            } catch (ProtocolException e) {
                throw initFailure(ws, "malformed");
            }
            Object version = root.opt("version");
            if (!(version instanceof Integer) && !(version instanceof Long)) {
                throw initFailure(ws, "malformed");
            }
            if (((Number) version).longValue() != 1L) {
                throw initFailure(ws, "unsupported_version");
            }
            Object suiteName = root.opt("suite");
            Object id = root.opt("client_id");
            if (!(suiteName instanceof String) || !(id instanceof String)) {
                throw initFailure(ws, "malformed");
            }
            suite = NoiseSuite.fromWireName((String) suiteName);
            if (suite == null) {
                throw initFailure(ws, "unsupported_suite");
            }
            clientId = (String) id;
            if (Base64Url.decode32(clientId) == null) {
                throw initFailure(ws, "malformed");
            }
        } catch (HandshakeException e) {
            throw e;
        }
        byte[] clientPublic = Base64Url.decode32(clientId);

        // 2. server/init and Noise message 1, sent one after the other without waiting
        String serverInit = SendspinMessages.serverInit(identity.id());
        byte[] serverInitBytes = serverInit.getBytes(StandardCharsets.UTF_8);
        byte[] prologue = Arrays.copyOf(clientInitBytes, clientInitBytes.length + serverInitBytes.length);
        System.arraycopy(serverInitBytes, 0, prologue, clientInitBytes.length, serverInitBytes.length);

        PskChoice choice = psks.choose(clientId);
        NoiseKkPsk2 noise = NoiseKkPsk2.initiator(suite, prologue, identity.keyPair(), clientPublic);
        try {
            JSONObject payload1 = new JSONObject().put("psk_id", SendspinPsk.pskId(choice.psk)).put("psk_category", choice.category.code());
            byte[] message1 = noise.writeMessage1(payload1.toString().getBytes(StandardCharsets.UTF_8), random);
            ws.sendText(serverInit);
            ws.sendText(SendspinMessages.noiseHandshake(Base64Url.encode(message1)));
        } catch (NoiseException | JSONException e) {
            noise.destroy();
            throw silent("could not write message 1");
        }

        // 3. Noise message 2, then transport mode
        byte[] message2;
        try {
            WsConnection.Message second = ws.receive();
            if (!second.isText() || second.data().length > MAX_NOISE_BYTES) {
                throw silent("bad noise message");
            }
            Envelope envelope = Envelope.parse(second.data(), MAX_NOISE_BYTES);
            if (!"noise/handshake".equals(envelope.type())) {
                throw silent("not a noise message");
            }
            message2 = Base64Url.decode(SendspinMessages.parseNoiseData(envelope.payload()));
            if (message2 == null) {
                throw silent("bad noise data");
            }
        } catch (ProtocolException e) {
            noise.destroy();
            throw silent("bad noise message");
        }
        boolean credentialMismatch = false;
        SendspinPsk.Category verifiedAs = choice.category;
        try {
            byte[][] candidates = choice.category == SendspinPsk.Category.SENTINEL
                    ? new byte[][]{choice.psk}
                    : new byte[][]{choice.psk, SendspinPsk.sentinel()};
            int index = noise.readMessage2Any(message2, candidates);
            if (!Arrays.equals(noise.lastPayload(), EMPTY_OBJECT)) {
                noise.destroy();
                throw silent("bad payload of message 2");
            }
            if (index == 1) {
                credentialMismatch = true;
                verifiedAs = SendspinPsk.Category.SENTINEL;
            }
        } catch (NoiseException e) {
            throw silent("handshake failed");
        }
        NoiseTransport transport = noise.split();
        return new Result(new SecureChannel(ws, transport), clientId, suite, verifiedAs, credentialMismatch, choice.category);
    }

    /** How long the server waits for the player's second message of a re-handshake. */
    static final long REHANDSHAKE_TIMEOUT_MILLIS = 10_000L;

    /**
     * The server reruns the Noise handshake inside the open channel (spec, "Re-handshake"): to the Pairing PSK for a pairing, to the
     * long-term PSK after it. Both messages travel as encrypted {@code noise/handshake} messages; message 2 is still under the old
     * keys; the prologue is the hash of the handshake before. No application message is sent from message 1 on (the send lock is
     * held until the new keys are in place); messages of the player under the old keys that arrive meanwhile are kept and delivered
     * by {@link SecureChannel#receive()} afterwards. There is no Sentinel fallback here: a message 2 under another PSK fails.
     * <p>
     * Must be called from the thread that reads the channel.
     *
     * @throws HandshakeException if the player does not answer or does not verify (the caller closes, without a message)
     */
    public static void rehandshake(SecureChannel channel, Identity identity, String clientId, NoiseSuite suite, PskChoice choice,
                                   SecureRandom random) throws IOException {
        rehandshake(channel, identity, clientId, suite, choice, random, REHANDSHAKE_TIMEOUT_MILLIS);
    }

    static void rehandshake(SecureChannel channel, Identity identity, String clientId, NoiseSuite suite, PskChoice choice,
                            SecureRandom random, long timeoutMillis) throws IOException {
        byte[] clientPublic = Base64Url.decode32(clientId);
        if (clientPublic == null) {
            throw silent("bad client id");
        }
        synchronized (channel.sendLock()) {
            NoiseKkPsk2 noise = NoiseKkPsk2.initiator(suite, channel.transport().handshakeHash(), identity.keyPair(), clientPublic);
            try {
                JSONObject payload1 = new JSONObject().put("psk_id", SendspinPsk.pskId(choice.psk)).put("psk_category", choice.category.code());
                byte[] message1 = noise.writeMessage1(payload1.toString().getBytes(StandardCharsets.UTF_8), random);
                channel.sendJson(SendspinMessages.noiseHandshake(Base64Url.encode(message1)));
            } catch (NoiseException | JSONException e) {
                noise.destroy();
                throw silent("could not write message 1");
            }
            byte[] message2 = null;
            java.util.List<SecureChannel.Inbound> held = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            try {
                while (message2 == null) {
                    SecureChannel.Inbound inbound;
                    try {
                        inbound = channel.receive();
                    } catch (java.net.SocketTimeoutException idle) {
                        if (System.nanoTime() - deadline > 0) {
                            throw silent("no answer to the re-handshake");
                        }
                        continue;
                    }
                    if (inbound.isJson()) {
                        Envelope envelope = inbound.envelope();
                        if ("noise/handshake".equals(envelope.type())) {
                            message2 = Base64Url.decode(SendspinMessages.parseNoiseData(envelope.payload()));
                            if (message2 == null) {
                                throw silent("bad noise data");
                            }
                            continue;
                        }
                    }
                    held.add(inbound); // a message that was sent before the player saw message 1
                }
                byte[] payload2 = noise.readMessage2(message2, choice.psk);
                if (!Arrays.equals(payload2, EMPTY_OBJECT)) {
                    throw silent("bad payload of message 2");
                }
            } catch (ProtocolException | NoiseException e) {
                noise.destroy();
                throw silent("re-handshake failed");
            } catch (HandshakeException e) {
                noise.destroy();
                throw e;
            }
            channel.installTransport(noise.split());
            for (SecureChannel.Inbound inbound : held) {
                channel.stash(inbound);
            }
        }
    }

    private static HandshakeException silent(String message) {
        return new HandshakeException(message);
    }

    /** The one failure that is answered: {@code server/error} with a reason, then the caller closes. */
    private static HandshakeException initFailure(WsConnection ws, String reason) {
        try {
            ws.sendText(SendspinMessages.serverError(reason));
        } catch (IOException ignored) {
            // the peer is gone
        }
        return new HandshakeException("init failure: " + reason);
    }
}
