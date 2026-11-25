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

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The client's side of the opening exchange (spec, "Communication" and "Encryption") when this app is the client, here a controller
 * that connects out to a server: send {@code client/init}, read {@code server/init}, read Noise message 1 (the server is always the
 * initiator, whichever side opened the WebSocket), pick the PSK it names (or fall back to the Sentinel), answer with message 2 and
 * switch to transport mode. Every failure ends in a {@link ServerHandshake.HandshakeException}; the caller closes without another
 * message (a {@code server/error} is the one case the server answers).
 */
public final class ClientHandshake {

    /** A PSK the client holds and the server it belongs to (null for a pairing PSK, which is not bound to a server). */
    public static final class Held {
        private final byte[] psk;
        private final String serverId;

        public Held(byte[] psk, String serverId) {
            this.psk = psk.clone();
            this.serverId = serverId;
        }
    }

    /** What the client holds for the PSK that message 1 names: the id (43 characters) and the category. */
    public interface PskLookup {
        /** @return the PSK, or null if the client holds none with this id under this category (then the Sentinel is used) */
        Held find(String pskId, SendspinPsk.Category category);
    }

    /** What the handshake yields. */
    public static final class Result {
        public final SecureChannel channel;
        public final String serverId;
        /** The category of the PSK message 2 was written under (the Sentinel after a fallback). */
        public final SendspinPsk.Category matched;
        /** True if the server named a PSK the client does not hold and the Sentinel was used instead. */
        public final boolean fellBack;

        Result(SecureChannel channel, String serverId, SendspinPsk.Category matched, boolean fellBack) {
            this.channel = channel;
            this.serverId = serverId;
            this.matched = matched;
            this.fellBack = fellBack;
        }
    }

    private static final int MAX_INIT_BYTES = 1024;
    private static final int MAX_NOISE_BYTES = 4096;
    private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.UTF_8);

    private ClientHandshake() {
    }

    /**
     * @param ws    a connection whose WebSocket handshake is done; its read timeout bounds each step
     * @param suite the cipher suite this client asks for
     */
    public static Result perform(WsConnection ws, Identity identity, NoiseSuite suite, PskLookup psks, SecureRandom random)
            throws IOException {
        String init = ControllerMessages.clientInit(identity.id(), suite.wireName());
        byte[] clientInitBytes = init.getBytes(StandardCharsets.UTF_8);
        ws.sendText(init);

        WsConnection.Message first = ws.receive();
        if (!first.isText() || first.data().length > MAX_INIT_BYTES) {
            throw fail("server/init is not text or too big");
        }
        byte[] serverInitBytes = first.data();
        Envelope serverInit;
        try {
            serverInit = Envelope.parse(serverInitBytes, MAX_INIT_BYTES);
        } catch (ProtocolException e) {
            throw fail("malformed server/init");
        }
        if ("server/error".equals(serverInit.type())) {
            throw fail("the server refused: " + serverInit.payload().optString("reason", "?"));
        }
        if (!"server/init".equals(serverInit.type())) {
            throw fail("not a server/init");
        }
        Object version = serverInit.payload().opt("version");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != 1L) {
            throw fail("unsupported server version");
        }
        Object id = serverInit.payload().opt("server_id");
        byte[] serverPublic = id instanceof String ? Base64Url.decode32((String) id) : null;
        if (serverPublic == null) {
            throw fail("bad server_id");
        }
        String serverId = (String) id;

        byte[] message1;
        try {
            WsConnection.Message second = ws.receive();
            if (!second.isText() || second.data().length > MAX_NOISE_BYTES) {
                throw fail("bad noise message");
            }
            Envelope envelope = Envelope.parse(second.data(), MAX_NOISE_BYTES);
            if (!"noise/handshake".equals(envelope.type())) {
                throw fail("not a noise message");
            }
            message1 = Base64Url.decode(SendspinMessages.parseNoiseData(envelope.payload()));
            if (message1 == null) {
                throw fail("bad noise data");
            }
        } catch (ProtocolException e) {
            throw fail("bad noise message");
        }

        byte[] prologue = Arrays.copyOf(clientInitBytes, clientInitBytes.length + serverInitBytes.length);
        System.arraycopy(serverInitBytes, 0, prologue, clientInitBytes.length, serverInitBytes.length);
        NoiseKkPsk2 noise = NoiseKkPsk2.responder(suite, prologue, identity.keyPair(), serverPublic);
        try {
            JSONObject payload1 = new JSONObject(new String(noise.readMessage1(message1), StandardCharsets.UTF_8));
            String pskId = payload1.getString("psk_id");
            SendspinPsk.Category category = SendspinPsk.Category.fromCode(payload1.getString("psk_category"));
            if (category == null || pskId.length() != 43) {
                throw fail("bad message 1 payload");
            }
            byte[] psk = null;
            SendspinPsk.Category matched = SendspinPsk.Category.SENTINEL;
            boolean fellBack = false;
            if (category == SendspinPsk.Category.SENTINEL && pskId.equals(SendspinPsk.sentinelId())) {
                psk = SendspinPsk.sentinel();
            } else if (category != SendspinPsk.Category.SENTINEL) {
                Held held = psks.find(pskId, category);
                if (held != null) {
                    if (held.serverId != null && !held.serverId.equals(serverId)) {
                        noise.destroy();
                        throw fail("the pairing record belongs to another server"); // a misbinding fails, it is not a miss
                    }
                    psk = held.psk;
                    matched = category;
                }
            }
            if (psk == null) { // a miss: the Sentinel fallback of the protocol, the server learns of a credential mismatch
                psk = SendspinPsk.sentinel();
                fellBack = true;
            }
            byte[] message2 = noise.writeMessage2(EMPTY_OBJECT, psk, random);
            ws.sendText(SendspinMessages.noiseHandshake(Base64Url.encode(message2)));
            NoiseTransport transport = noise.split();
            return new Result(new SecureChannel(ws, transport), serverId, matched, fellBack);
        } catch (NoiseException | org.json.JSONException e) {
            noise.destroy();
            throw fail("handshake failed");
        }
    }

    /**
     * The responder side of a re-handshake inside the open channel (the server starts one after a pairing, to move to the long-term PSK):
     * reads Noise message 1 (already received, decoded), looks up the PSK it names (no Sentinel fallback here: a PSK this client does not
     * hold ends the connection), answers with message 2 under the old keys and puts the new keys in place. The prologue is the
     * previous handshake hash. The send lock is held until the new keys are in, so no application message goes out between.
     *
     * @return the category of the PSK the new keys are under
     */
    public static SendspinPsk.Category rehandshake(SecureChannel channel, Identity identity, String serverId, NoiseSuite suite,
                                                   PskLookup psks, byte[] message1, SecureRandom random) throws IOException {
        byte[] serverPublic = Base64Url.decode32(serverId);
        if (serverPublic == null || channel.transport() == null) {
            throw fail("no re-handshake here");
        }
        synchronized (channel.sendLock()) {
            NoiseKkPsk2 noise = NoiseKkPsk2.responder(suite, channel.transport().handshakeHash(), identity.keyPair(), serverPublic);
            try {
                JSONObject payload1 = new JSONObject(new String(noise.readMessage1(message1), StandardCharsets.UTF_8));
                String pskId = payload1.getString("psk_id");
                SendspinPsk.Category category = SendspinPsk.Category.fromCode(payload1.getString("psk_category"));
                if (category == null || category == SendspinPsk.Category.SENTINEL || pskId.length() != 43) {
                    throw fail("bad re-handshake payload");
                }
                Held held = psks.find(pskId, category);
                if (held == null || (held.serverId != null && !held.serverId.equals(serverId))) {
                    throw fail("the re-handshake names a PSK this client does not hold");
                }
                byte[] message2 = noise.writeMessage2(EMPTY_OBJECT, held.psk, random);
                channel.sendJson(SendspinMessages.noiseHandshake(Base64Url.encode(message2))); // still under the old keys
                channel.installTransport(noise.split());
                return category;
            } catch (NoiseException | org.json.JSONException e) {
                noise.destroy();
                throw fail("re-handshake failed");
            } catch (ServerHandshake.HandshakeException e) {
                noise.destroy();
                throw e;
            }
        }
    }

    private static ServerHandshake.HandshakeException fail(String message) {
        return new ServerHandshake.HandshakeException(message);
    }
}
