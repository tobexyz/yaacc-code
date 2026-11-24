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
import de.yaacc.sendspin.secure.NoiseKkPsk2;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.NoiseTransport;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The client side of the protocol for tests: a player that opens the exchange with {@code client/init}, answers the Noise
 * handshake and then speaks over the encrypted channel, with switches to break every step. The WebSocket connection to it is a real
 * loopback connection, opened by the hub side (the server connects to the player).
 */
final class FakePlayer implements AutoCloseable {

    final SecureRandom random = new SecureRandom();
    final Identity identity = Identity.generate(random);

    // what a test may change
    String suiteName = NoiseSuite.CHACHA_POLY.wireName();
    NoiseSuite suite = NoiseSuite.CHACHA_POLY;
    Object version = 1;
    String clientIdOverride;
    String rawClientInit;
    byte[] message2Payload = "{}".getBytes(StandardCharsets.UTF_8);
    boolean breakStaticKey;
    final Map<String, byte[]> knownPsks = new HashMap<>();

    // what a test may read
    String serverError;
    String pskIdSeen;
    String pskCategorySeen;
    String serverIdSeen;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-player");
        t.setDaemon(true);
        return t;
    });
    private Socket hubSocket;
    private Socket playerSocket;
    /** The hub's end of the WebSocket (a client). */
    WsConnection hubWs;
    /** The player's end (a server). */
    WsConnection playerWs;

    /** Opens the WebSocket connection (handshake included) between a hub end and the player end. */
    FakePlayer open() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            hubSocket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            playerSocket = server.accept();
        }
        hubSocket.setSoTimeout(5000);
        playerSocket.setSoTimeout(5000);
        BufferedInputStream hubIn = new BufferedInputStream(hubSocket.getInputStream());
        BufferedInputStream playerIn = new BufferedInputStream(playerSocket.getInputStream());
        Future<?> accepted = executor.submit(() -> {
            WsHandshake.accept(playerIn, playerSocket.getOutputStream(), true);
            return null;
        });
        WsHandshake.connect(hubIn, hubSocket.getOutputStream(), "127.0.0.1", hubSocket.getPort(), "/sendspin", random);
        accepted.get();
        hubWs = new WsConnection(hubIn, hubSocket.getOutputStream(), true, 1 << 20, random);
        playerWs = new WsConnection(playerIn, playerSocket.getOutputStream(), false, 1 << 20, random);
        return this;
    }

    /** Runs the work of the hub side on another thread. */
    <T> Future<T> onHub(java.util.concurrent.Callable<T> work) {
        return executor.submit(work);
    }

    /**
     * The player's side of the opening exchange. @return the encrypted channel, or null if the server answered with
     * {@code server/error} (then {@link #serverError} holds the reason)
     */
    SecureChannel handshake() throws Exception {
        String clientId = clientIdOverride != null ? clientIdOverride : identity.id();
        String init = rawClientInit != null ? rawClientInit
                : new JSONObject().put("type", "client/init").put("payload",
                new JSONObject().put("client_id", clientId).put("version", version).put("suite", suiteName)).toString();
        byte[] initBytes = init.getBytes(StandardCharsets.UTF_8);
        playerWs.sendText(init);

        WsConnection.Message first = playerWs.receive();
        Envelope firstEnvelope = Envelope.parse(first.data());
        if ("server/error".equals(firstEnvelope.type())) {
            serverError = firstEnvelope.payload().getString("reason");
            return null;
        }
        byte[] serverInitBytes = first.data();
        serverIdSeen = firstEnvelope.payload().getString("server_id");
        Envelope noise1 = Envelope.parse(playerWs.receive().data());
        byte[] message1 = Base64Url.decode(SendspinMessages.parseNoiseData(noise1.payload()));

        byte[] prologue = Arrays.copyOf(initBytes, initBytes.length + serverInitBytes.length);
        System.arraycopy(serverInitBytes, 0, prologue, initBytes.length, serverInitBytes.length);
        byte[] serverPublic = Base64Url.decode32(serverIdSeen);
        NoiseKkPsk2 noise = NoiseKkPsk2.responder(suite, prologue, identity.keyPair(), breakStaticKey ? new byte[32] : serverPublic);
        byte[] payload1 = noise.readMessage1(message1);
        JSONObject json = new JSONObject(new String(payload1, StandardCharsets.UTF_8));
        pskIdSeen = json.getString("psk_id");
        pskCategorySeen = json.getString("psk_category");
        byte[] psk = knownPsks.get(pskIdSeen);
        if (psk == null) {
            psk = SendspinPsk.sentinel(); // the Sentinel fallback of the protocol
        }
        byte[] message2 = noise.writeMessage2(message2Payload, psk, random);
        playerWs.sendText(SendspinMessages.noiseHandshake(Base64Url.encode(message2)));
        NoiseTransport transport = noise.split();
        return new SecureChannel(playerWs, transport);
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        if (hubSocket != null) {
            hubSocket.close();
        }
        if (playerSocket != null) {
            playerSocket.close();
        }
    }
}
