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
package de.yaacc.sendspin.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.sendspin.session.SendspinMessages;
import de.yaacc.sendspin.session.ServerHandshake;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The controller session against a fake server (the server side of the real handshake over a loopback connection). */
public class ControllerSessionTest {

    private static final String GROUP = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ROLES = "[\"controller@v1\",\"metadata@v1\"]";

    private FakePlayer link; // the two ends of one connection
    private ControllerSession session;
    private SecureChannel server; // the fake server's end of the encrypted channel
    private final Identity serverIdentity = Identity.generate(new SecureRandom());
    private final Identity clientIdentity = Identity.generate(new SecureRandom());

    @After
    public void tearDown() throws IOException {
        if (session != null) {
            session.close();
        }
        if (link != null) {
            link.close();
        }
    }

    private void connect(boolean unpairedAccess) throws Exception {
        link = new FakePlayer().open();
        Connector connector = (endpoint, random) -> link.hubLink();
        session = new ControllerSession(new Endpoint("127.0.0.1", 8927, "/sendspin"), clientIdentity, unpairedAccess, connector,
                HubClock.SYSTEM, new SecureRandom(), "YAACC test", null);
        session.setBurstIntervalMillis(100);
        Future<ServerHandshake.Result> s = link.onHub(
                () -> ServerHandshake.perform(link.playerWs, serverIdentity, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom()));
        session.start();
        server = s.get(5, TimeUnit.SECONDS).channel;
        server.sendJson(SendspinMessages.serverHello("Music Assistant"));
        assertEquals("client/hello", nextJson().type());
    }

    /** The next message, without the clock bursts of the session (they run beside everything else). */
    private Envelope nextJson() throws Exception {
        while (true) {
            Envelope e = nextJsonAny();
            if (!"client/time".equals(e.type())) {
                return e;
            }
            answerTime(e);
        }
    }

    private Envelope nextJsonAny() throws Exception {
        while (true) {
            SecureChannel.Inbound inbound = server.receive();
            if (inbound.isJson()) {
                return inbound.envelope();
            }
        }
    }

    private void answerTime(Envelope e) throws Exception {
        long t1 = e.payload().getLong("client_transmitted");
        server.sendJson(SendspinMessages.serverTime(t1, t1 + 5_000_000L, t1 + 5_000_020L));
    }

    private void awaitPhase(ControllerSession.Phase expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (session.phase() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, session.phase());
    }

    private void activate(String activities, String roles) throws Exception {
        server.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":" + activities + ",\"active_roles\":" + roles + "}}");
    }

    private static String controllerState(int volume, String commands) {
        return "{\"type\":\"server/state\",\"payload\":{\"controller\":{\"supported_commands\":" + commands + ",\"volume\":" + volume
                + ",\"muted\":false,\"repeat\":\"off\",\"shuffle\":false,\"seek_max_ms\":200000}}}";
    }

    private void becomeActive() throws Exception {
        connect(true);
        activate("[]", ROLES);
        assertEquals("client/state", nextJson().type());
        awaitPhase(ControllerSession.Phase.ACTIVE);
        server.sendJson(controllerState(35, "[\"play\",\"pause\",\"next\",\"volume\",\"seek\",\"mute\"]"));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (session.controller() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    public void theSessionSendsItsHelloAfterTheServersAndIsActiveAfterTheActivation() throws Exception {
        connect(true);
        assertEquals(ControllerSession.Phase.WAITING_FOR_ACTIVATION, session.phase());
        assertEquals("Music Assistant", session.serverName());
        assertEquals(serverIdentity.id(), session.serverId());
        activate("[]", ROLES);
        Envelope state = nextJson();
        assertEquals("client/state", state.type());
        assertTrue(state.payload().getBoolean("available"));
        awaitPhase(ControllerSession.Phase.ACTIVE);
    }

    @Test
    public void groupAndServerStateReachTheSnapshot() throws Exception {
        becomeActive();
        server.sendJson("{\"type\":\"group/update\",\"payload\":{\"playback_state\":\"playing\",\"group_id\":\"" + GROUP
                + "\",\"group_name\":\"Kitchen\"}}");
        server.sendJson("{\"type\":\"server/state\",\"payload\":{\"metadata\":{\"timestamp\":1,\"title\":\"Song\",\"artist\":\"Band\","
                + "\"progress\":{\"track_progress\":5000,\"track_duration\":180000,\"playback_speed\":1000}}}}");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((session.group() == null || session.metadata() == null) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("Kitchen", session.group().groupName);
        assertTrue(session.group().playing);
        assertEquals("Song", session.metadata().title);
        assertEquals(35, session.controller().volume);
        assertTrue("the position runs on from the timestamp", session.positionMs() >= 5000);
    }

    @Test
    public void commandsGoOutOnlyWhenTheServerOffersThem() throws Exception {
        becomeActive();
        assertTrue(session.pause());
        JSONObject c = nextJson().payload().getJSONObject("controller");
        assertEquals("pause", c.getString("command"));
        assertTrue(session.setVolume(250));
        assertEquals("clamped", 100, nextJson().payload().getJSONObject("controller").getInt("volume"));
        assertTrue(session.seek(900_000L));
        assertEquals("clamped to the seek range", 200_000L, nextJson().payload().getJSONObject("controller").getLong("position_ms"));
        assertFalse("previous is not offered", session.previous());
        assertFalse("stop is not offered", session.stop());
        assertFalse("shuffle is not offered", session.setShuffle(true));
        assertTrue(session.next());
        assertEquals("next", nextJson().payload().getJSONObject("controller").getString("command"));
    }

    @Test
    public void noCommandsBeforeTheControllerIsActive() throws Exception {
        connect(true);
        assertFalse(session.play());
        activate("[]", "[]");
        awaitPhase(ControllerSession.Phase.WAITING_FOR_SERVER);
        assertFalse("the server has not activated the role", session.play());
    }

    @Test
    public void timeBurstsAreAnsweredAndGiveAnEstimate() throws Exception {
        becomeActive();
        int answered = 0;
        while (answered < 8) {
            Envelope e = nextJsonAny();
            if ("client/time".equals(e.type())) {
                answerTime(e);
                answered++;
            }
        }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!session.hasClockEstimate() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("a burst has at least 8 messages: " + answered, answered >= 8);
        assertTrue(session.hasClockEstimate());
    }

    @Test
    public void anUnpairedServerIsRefusedWhenUnpairedAccessIsOff() throws Exception {
        connect(false);
        activate("[]", ROLES);
        JSONObject goodbye = nextJson().payload();
        assertEquals("pairing_required", goodbye.getString("reason"));
        awaitPhase(ControllerSession.Phase.ENDED);
        assertEquals(ControllerSession.EndReason.PAIRING_REQUIRED, session.endReason());
    }

    @Test
    public void anActivationTheKeyDoesNotAllowIsUnauthorized() throws Exception {
        connect(true);
        activate("[\"bogus\"]", "[]");
        assertEquals("unauthorized", nextJson().payload().getString("reason"));
        awaitPhase(ControllerSession.Phase.ENDED);
        assertEquals(ControllerSession.EndReason.UNAUTHORIZED, session.endReason());
    }

    @Test
    public void aPairingActivationIsAnsweredWithMethodNotSupportedAndTheConnectionStays() throws Exception {
        connect(true);
        server.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":[\"pairing\"],\"active_roles\":[],"
                + "\"pairing\":{\"method\":\"dynamic_pairing_code\",\"format\":\"digits\"}}}");
        Envelope abort = nextJson();
        assertEquals("pair/abort", abort.type());
        assertEquals("method_not_supported", abort.payload().getString("reason"));
        assertNotNull(session.phase());
        assertFalse(session.phase() == ControllerSession.Phase.ENDED);
    }

    @Test
    public void anEmptyGroupIdIsAProtocolError() throws Exception {
        becomeActive();
        server.sendJson("{\"type\":\"group/update\",\"payload\":{\"playback_state\":\"stopped\",\"group_id\":\"\",\"group_name\":\"g\"}}");
        awaitPhase(ControllerSession.Phase.ENDED);
        assertEquals(ControllerSession.EndReason.PROTOCOL_ERROR, session.endReason());
    }

    @Test
    public void aServerThatUnpairsOnAnUnpairedSessionIsIgnored() throws Exception {
        becomeActive();
        server.sendJson("{\"type\":\"server/unpair\",\"payload\":{}}");
        server.sendJson("{\"type\":\"group/update\",\"payload\":{\"playback_state\":\"stopped\",\"group_id\":\"" + GROUP
                + "\",\"group_name\":\"Still here\"}}");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (session.group() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("Still here", session.group().groupName);
        assertEquals(ControllerSession.Phase.ACTIVE, session.phase());
    }

    @Test
    public void aReHandshakeIsNotSupportedYetAndEndsTheConnection() throws Exception {
        becomeActive();
        server.sendJson(SendspinMessages.noiseHandshake("AAAA"));
        awaitPhase(ControllerSession.Phase.ENDED);
        assertEquals(ControllerSession.EndReason.FAILED, session.endReason());
    }

    @Test
    public void aScheduledMetadataStateWaitsForItsTime() throws Exception {
        becomeActive();
        // the server clock for the session starts at the client clock: a timestamp far ahead is pending
        long farFuture = System.nanoTime() / 1000L + 3_600_000_000L;
        server.sendJson("{\"type\":\"server/state\",\"payload\":{\"metadata\":{\"timestamp\":1,\"title\":\"Now\"}}}");
        server.sendJson("{\"type\":\"server/state\",\"payload\":{\"metadata\":{\"timestamp\":" + farFuture + ",\"title\":\"Next\"}}}");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (session.pendingMetadata() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("Now", session.metadata().title);
        assertEquals("Next", session.pendingMetadata().title);
    }

    @Test
    public void closingTellsTheServerTheUserAskedForIt() throws Exception {
        becomeActive();
        session.close();
        Envelope e = nextJsonAny();
        while ("client/time".equals(e.type())) {
            e = nextJsonAny(); // a burst message that was already on its way
        }
        assertEquals("client/goodbye", e.type());
        assertEquals("user_request", e.payload().getString("reason"));
        awaitPhase(ControllerSession.Phase.ENDED);
        assertEquals(ControllerSession.EndReason.CLOSED_BY_APP, session.endReason());
    }

    @Test
    public void theServerClosingEndsTheSession() throws Exception {
        becomeActive();
        link.playerWs.close(1000, "");
        awaitPhase(ControllerSession.Phase.ENDED);
        assertNull("no reason of the protocol, the server just went", null);
        assertTrue(session.endReason() == ControllerSession.EndReason.SERVER_CLOSED || session.endReason() == ControllerSession.EndReason.FAILED);
    }
}
