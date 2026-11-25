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
package de.yaacc.sendspin.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.SecureChannel;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

/** The Pairing PSK flow of the server (spec, "Pairing PSK Flow") against a fake player, over a real loopback connection. */
public class HubPairingTest {

    private static final String PCM_FORMATS = "[{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16}]";

    private FakePlayer player;
    private HubConnection hub;
    private SecureChannel peer;
    private final ServerPolicy.InMemory policy = new ServerPolicy.InMemory();
    private final Identity server = Identity.generate(new SecureRandom());
    private final byte[] pairingPsk = filled(0x31);
    private final byte[] longTerm = filled(0x77);

    @After
    public void tearDown() throws IOException {
        if (hub != null) {
            hub.close();
        }
        if (player != null) {
            player.close();
        }
    }

    private static byte[] filled(int value) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) value);
        return b;
    }

    private static String helloJson(String pairMethods) {
        return "{\"type\":\"client/hello\",\"payload\":{\"name\":\"Kitchen\",\"supported_roles\":[\"player@v1\"],"
                + "\"player@v1_support\":{\"supported_formats\":" + PCM_FORMATS + ",\"buffer_capacity\":1000000},"
                + "\"supported_pair_methods\":" + pairMethods + ",\"unpaired_access\":{\"enabled\":false}}}";
    }

    private static String stateJson() {
        return "{\"type\":\"client/state\",\"payload\":{\"available\":true,\"player\":{\"volume\":25,\"muted\":false,"
                + "\"output_delay_ms\":0,\"required_lead_time_ms\":250,\"min_buffer_ms\":250,\"supported_commands\":[\"volume\"]}}}";
    }

    private static String pairInit(int index) {
        return "{\"type\":\"client/pair-init\",\"payload\":{\"pairing_index\":" + index + "}}";
    }

    private static String pairFinalize(byte[] psk) {
        return "{\"type\":\"client/pair-finalize\",\"payload\":{\"long_term_psk\":\"" + Base64Url.encode(psk) + "\"}}";
    }

    /** The player is paired-only: it knows its pairing PSK; the operator pasted the token (the policy holds the PSK). */
    private void connect(boolean playerKnowsPairingPsk, long timeoutMillis) throws Exception {
        player = new FakePlayer().open();
        policy.addToken(player.identity.id(), pairingPsk);
        if (playerKnowsPairingPsk) {
            player.knownPsks.put(SendspinPsk.pskId(pairingPsk), pairingPsk);
        }
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null);
        hub.setPairingTimeoutMillis(timeoutMillis);
        hub.start();
        peer = player.handshake();
        assertNotNull(peer);
        assertEquals("server/hello", peer.receive().envelope().type());
    }

    private void awaitPhase(HubConnection.Phase expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (hub.phase() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, hub.phase());
    }

    private Envelope nextJson() throws Exception {
        while (true) {
            SecureChannel.Inbound inbound = peer.receive();
            if (inbound.isJson()) {
                return inbound.envelope();
            }
        }
    }

    @Test
    public void aTokenPairsThePlayerAndTheConnectionContinuesUnderTheLongTermPsk() throws Exception {
        connect(true, 30_000);
        assertEquals("the server references the pairing PSK of the token", "pr", player.pskCategorySeen);
        assertEquals(SendspinPsk.pskId(pairingPsk), player.pskIdSeen);

        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        Envelope activate = nextJson();
        assertEquals("server/activate", activate.type());
        JSONObject payload = activate.payload();
        assertEquals(1, payload.getJSONArray("activities").length());
        assertEquals("pairing", payload.getJSONArray("activities").getString(0));
        assertEquals("no roles while pairing", 0, payload.getJSONArray("active_roles").length());
        assertEquals("pairing_psk", payload.getJSONObject("pairing").getString("method"));
        awaitPhase(HubConnection.Phase.PAIRING);

        peer.sendJson(pairInit(1));
        peer.sendJson(pairFinalize(longTerm));
        assertEquals("server/pair-finalize", nextJson().type());
        player.knownPsks.put(SendspinPsk.pskId(longTerm), longTerm); // the player stores its record on receiving pair-finalize
        player.rehandshake(peer, null);
        assertEquals("lt", player.pskCategorySeen);
        assertEquals(SendspinPsk.pskId(longTerm), player.pskIdSeen);

        JSONObject second = nextJson().payload();
        assertEquals("the first message under the new keys is the activation: the role without playback", 0,
                second.getJSONArray("activities").length());
        assertEquals("player@v1", second.getJSONArray("active_roles").getString(0));
        assertTrue(policy.isPaired(player.identity.id()));
        assertNull("the token is used up", policy.pairingPsk(player.identity.id()));
        assertTrue(hub.isPaired());
        assertEquals(HubConnection.PairingFailure.NONE, hub.pairingFailure());

        peer.sendJson(stateJson());
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertTrue(hub.startPlayback());
        assertEquals("playback", nextJson().payload().getJSONArray("activities").getString(0));
        awaitPhase(HubConnection.Phase.READY);
        assertNotNull(hub.streamer());
    }

    @Test
    public void aPairingIndexBelowTheServersCountIsAnOldLeftoverAndIgnored() throws Exception {
        connect(true, 30_000);
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        peer.sendJson(pairInit(0)); // a leftover
        peer.sendJson(pairInit(1));
        peer.sendJson(pairFinalize(longTerm));
        assertEquals("server/pair-finalize", nextJson().type());
    }

    @Test
    public void aPairingIndexAboveTheCountIsAProtocolError() throws Exception {
        connect(true, 30_000);
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        peer.sendJson(pairInit(2));
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.PROTOCOL_ERROR, hub.endReason());
        assertFalse(policy.isPaired(player.identity.id()));
    }

    @Test
    public void finalizeBeforeInitIsAProtocolError() throws Exception {
        connect(true, 30_000);
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        peer.sendJson(pairFinalize(longTerm));
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.PROTOCOL_ERROR, hub.endReason());
        assertFalse(policy.isPaired(player.identity.id()));
    }

    @Test
    public void pairingMessagesWithoutAPairingActivationAreAProtocolError() throws Exception {
        // an ordinary Sentinel session (no token): the player has no business sending pair-init
        player = new FakePlayer().open();
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null);
        hub.start();
        peer = player.handshake();
        peer.receive();
        peer.sendJson(pairInit(1));
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.PROTOCOL_ERROR, hub.endReason());
    }

    @Test
    public void theSentinelOrThePairingPskAsLongTermPskIsRefusedAndTheAttemptCancelled() throws Exception {
        for (byte[] bad : new byte[][]{SendspinPsk.sentinel(), pairingPsk}) {
            if (player != null) {
                player.close();
                hub.close();
            }
            connect(true, 30_000);
            peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
            nextJson();
            peer.sendJson(pairInit(1));
            peer.sendJson(pairFinalize(bad));
            JSONObject cancel = nextJson().payload();
            assertEquals("a server/activate with no activity ends the attempt", 0, cancel.getJSONArray("activities").length());
            awaitPhase(HubConnection.Phase.PAIRING_FAILED);
            assertEquals(HubConnection.PairingFailure.CANNOT_STORE, hub.pairingFailure());
            assertFalse(policy.isPaired(player.identity.id()));
            assertNotNull("the token is kept for another try", policy.pairingPsk(player.identity.id()));
        }
    }

    @Test
    public void aPlayerThatAbortsStopsThePairingAndStaysConnected() throws Exception {
        connect(true, 30_000);
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        peer.sendJson("{\"type\":\"pair/abort\",\"payload\":{\"reason\":\"user_cancelled\"}}");
        assertEquals(0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.PAIRING_FAILED);
        assertEquals(HubConnection.PairingFailure.PLAYER_ABORTED, hub.pairingFailure());
        // in flight messages after the end of the attempt are discarded silently
        peer.sendJson(pairInit(1));
        peer.sendJson(pairFinalize(longTerm));
        peer.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":3}}");
        assertEquals("server/time", nextJson().type());
        assertEquals(HubConnection.Phase.PAIRING_FAILED, hub.phase());
        assertFalse(policy.isPaired(player.identity.id()));
    }

    @Test
    public void aPlayerThatSaysNothingIsCancelledAfterTheTimeout() throws Exception {
        connect(true, 200);
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        // the loop checks the timeout when a read times out; the connector's read timeout is 5 s, so give it a nudge by waiting
        JSONObject cancel = nextJson().payload();
        assertEquals(0, cancel.getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.PAIRING_FAILED);
        assertEquals(HubConnection.PairingFailure.TIMEOUT, hub.pairingFailure());
    }

    @Test
    public void aPlayerThatKeepsTheLineBusyIsCancelledAfterTheTimeoutToo() throws Exception {
        connect(true, 300);
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        long end = System.nanoTime() + 3_000_000_000L;
        while (hub.phase() != HubConnection.Phase.PAIRING_FAILED && System.nanoTime() < end) {
            peer.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":3}}"); // never silent for the read timeout
            Thread.sleep(50);
        }
        assertEquals(HubConnection.Phase.PAIRING_FAILED, hub.phase());
        assertEquals(HubConnection.PairingFailure.TIMEOUT, hub.pairingFailure());
    }

    @Test
    public void aPlayerThatDoesNotKnowThePairingPskMeansTheTokenIsRejected() throws Exception {
        connect(false, 30_000); // it answers with the Sentinel
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        assertEquals(0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.PAIRING_FAILED);
        assertEquals(HubConnection.PairingFailure.TOKEN_REJECTED, hub.pairingFailure());
        assertTrue(hub.credentialMismatch());
    }

    @Test
    public void aPlayerWithoutThePairingMethodCannotBePaired() throws Exception {
        connect(true, 30_000);
        peer.sendJson(helloJson("{}"));
        assertEquals(0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.PAIRING_FAILED);
        assertEquals(HubConnection.PairingFailure.NOT_OFFERED, hub.pairingFailure());
    }

    @Test
    public void ifThePolicyDoesNotReferenceTheLongTermPskAfterThePairingTheConnectionEnds() throws Exception {
        player = new FakePlayer().open();
        ServerPolicy.InMemory odd = new ServerPolicy.InMemory() {
            @Override
            public boolean completePairing(String clientId, byte[] longTermPsk) {
                super.completePairing(clientId, longTermPsk);
                addToken(clientId, pairingPsk); // a token pasted at the wrong moment: the next reference would be the pairing PSK
                return true;
            }
        };
        odd.addToken(player.identity.id(), pairingPsk);
        player.knownPsks.put(SendspinPsk.pskId(pairingPsk), pairingPsk);
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, odd, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null);
        hub.start();
        peer = player.handshake();
        peer.receive();
        peer.sendJson(helloJson("{\"pairing_psk\":{}}"));
        nextJson();
        peer.sendJson(pairInit(1));
        peer.sendJson(pairFinalize(longTerm));
        assertEquals("server/pair-finalize", nextJson().type());
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.FAILED, hub.endReason());
    }
}
