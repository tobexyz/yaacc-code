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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.WsConnection;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * A player of the older, unencrypted wire (the Kodi add-on's) against the hub connection: no activation message, the roles in the
 * {@code server/hello}, nothing sent before the operator approved, the audio chunk without the send-ahead field. The switch that
 * allows this wire is off by default in the app; here it is on.
 */
public class HubLegacyTest {

    private static final String HELLO = "{\"type\":\"client/hello\",\"payload\":{\"client_id\":\"kodi-1\",\"name\":\"Kodi\",\"version\":1,"
            + "\"supported_roles\":[\"player@v1\",\"metadata@v1\",\"controller@v1\"],\"player@v1_support\":{\"supported_formats\":["
            + "{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":48000,\"bit_depth\":16}],\"buffer_capacity\":2000000,"
            + "\"supported_commands\":[\"volume\",\"mute\"]}}}";
    private static final String STATE = "{\"type\":\"client/state\",\"payload\":{\"available\":true,\"player\":{\"volume\":30,\"muted\":false,"
            + "\"static_delay_ms\":0,\"required_lead_time_ms\":3200,\"min_buffer_ms\":3500}}}";

    private FakePlayer player;
    private HubConnection hub;
    private final ServerPolicy.InMemory policy = new ServerPolicy.InMemory();
    private final Identity server = Identity.generate(new SecureRandom());
    private volatile boolean legacyAllowed = true;

    @After
    public void tearDown() throws IOException {
        if (hub != null) {
            hub.close();
        }
        if (player != null) {
            player.close();
        }
    }

    private void connect() throws Exception {
        player = new FakePlayer().open();
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null, () -> legacyAllowed);
        hub.start();
        player.playerWs.sendText(HELLO);
    }

    private void awaitPhase(HubConnection.Phase expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (hub.phase() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, hub.phase());
    }

    /** The next text message of the hub (the player's side of the plain wire). */
    private Envelope nextText() throws Exception {
        while (true) {
            WsConnection.Message m = player.playerWs.receive();
            if (m.isText()) {
                return Envelope.parse(m.data());
            }
        }
    }

    private void becomeAvailable() throws Exception {
        connect();
        awaitPhase(HubConnection.Phase.NEEDS_APPROVAL);
        hub.approve();
        assertEquals("server/hello", nextText().type());
        player.playerWs.sendText(STATE);
        awaitPhase(HubConnection.Phase.AVAILABLE);
    }

    @Test
    public void anUnapprovedLegacyPlayerGetsNothingUntilTheOperatorApproves() throws Exception {
        connect();
        awaitPhase(HubConnection.Phase.NEEDS_APPROVAL);
        assertEquals("kodi-1", hub.clientId());
        assertEquals("Kodi", hub.hello().name);
        assertNull("no stream for a player that is not approved", hub.streamer());
        player.playerWs.sendText("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":7}}");
        Envelope time = nextText(); // answering the clock is not an activation: the first thing the hub says is the time
        assertEquals("server/time", time.type());
        assertEquals(7L, time.payload().getLong("client_transmitted"));
        hub.approve();
        Envelope hello = nextText();
        assertEquals("server/hello", hello.type());
        JSONObject payload = hello.payload();
        assertEquals(server.id(), payload.getString("server_id"));
        assertEquals("YAACC test", payload.getString("name"));
        assertEquals(1, payload.getInt("version"));
        assertEquals("discovery", payload.getString("connection_reason"));
        assertEquals("only the player role is active", 1, payload.getJSONArray("active_roles").length());
        assertEquals("player@v1", payload.getJSONArray("active_roles").getString(0));
        assertTrue("remembered", policy.isApproved("kodi-1"));
        assertFalse(hub.isPaired());
    }

    @Test
    public void anApprovedLegacyPlayerGetsItsHelloAtOnceAndPlaybackIsNotAMessage() throws Exception {
        player = new FakePlayer().open();
        policy.approve("kodi-1");
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null, () -> true);
        hub.start();
        player.playerWs.sendText(HELLO);
        assertEquals("server/hello", nextText().type());
        player.playerWs.sendText(STATE);
        awaitPhase(HubConnection.Phase.AVAILABLE);

        assertTrue(hub.startPlayback());
        awaitPhase(HubConnection.Phase.READY);
        assertEquals("group/update", nextText().type()); // there is no server/activate on this wire: the next message is the group
        assertNotNull(hub.streamer());
        assertEquals(48000, hub.streamFormat().sampleRate);
        assertEquals("what the player asked for plus the margin", 3500_000L + 250_000L, hub.leadMicros());
        assertEquals(2_000_000L, hub.capacityBytes());
    }

    @Test
    public void playbackAskedForBeforeTheHelloIsTheConnectionReason() throws Exception {
        player = new FakePlayer().open();
        policy.approve("kodi-1");
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null, () -> true);
        assertTrue(hub.startPlayback());
        hub.start();
        player.playerWs.sendText(HELLO);
        assertEquals("playback", nextText().payload().getString("connection_reason"));
    }

    @Test
    public void aPlayGivenUpBeforeTheHelloDoesNotMakeTheConnectionReasonPlayback() throws Exception {
        player = new FakePlayer().open();
        policy.approve("kodi-1");
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null, () -> true);
        assertTrue(hub.startPlayback());
        hub.stopPlayback(); // the play was given up before the player said hello
        hub.start();
        player.playerWs.sendText(HELLO);
        assertEquals("discovery", nextText().payload().getString("connection_reason"));
    }

    @Test
    public void theStreamStartsAsJsonAndTheAudioHasNoSendAheadField() throws Exception {
        becomeAvailable();
        assertTrue(hub.startPlayback());
        awaitPhase(HubConnection.Phase.READY);
        PcmStreamer streamer = hub.streamer();
        assertNotNull(streamer);
        byte[] pcm = new byte[48000 / 1000 * 20 * 4]; // one chunk of 20 ms: 48 kHz, 16 bit, two channels
        assertTrue(streamer.write(pcm, 0, pcm.length));
        Envelope start = null;
        byte[] binary = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((start == null || binary == null) && System.nanoTime() < deadline) {
            WsConnection.Message m = player.playerWs.receive();
            if (m.isText()) {
                Envelope e = Envelope.parse(m.data());
                if ("stream/start".equals(e.type())) {
                    start = e;
                }
            } else {
                binary = m.data();
            }
        }
        assertNotNull(start);
        JSONObject format = start.payload().getJSONObject("player");
        assertEquals("pcm", format.getString("codec"));
        assertEquals(48000, format.getInt("sample_rate"));
        assertEquals(16, format.getInt("bit_depth"));
        assertEquals(2, format.getInt("channels"));
        assertNotNull(binary);
        assertEquals("the audio message id", 4, binary[0]);
        assertEquals("the id, the timestamp and the audio of 20 ms", 1 + 8 + pcm.length, binary.length);
        assertTrue("a timestamp after now", ByteBuffer.wrap(binary, 1, 8).getLong() > HubClock.SYSTEM.nowMicros());
    }

    @Test
    public void volumeAndMuteGoOutOnlyAsTheHelloOfThePlayerListedThem() throws Exception {
        becomeAvailable();
        assertTrue(hub.setVolume(40));
        JSONObject command = nextText().payload().getJSONObject("player");
        assertEquals("volume", command.getString("command"));
        assertEquals(40, command.getInt("volume"));
        assertTrue(hub.setMuted(true));
        assertTrue(nextText().payload().getJSONObject("player").getBoolean("mute"));
        assertEquals(Integer.valueOf(30), hub.state().player.volume);
    }

    @Test
    public void stoppingEndsTheStreamAndSendsNoActivation() throws Exception {
        becomeAvailable();
        assertTrue(hub.startPlayback());
        awaitPhase(HubConnection.Phase.READY);
        hub.stopPlayback();
        awaitPhase(HubConnection.Phase.AVAILABLE);
        player.playerWs.sendText("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":1}}");
        Envelope e = nextText();
        while ("group/update".equals(e.type())) {
            e = nextText();
        }
        assertEquals("nothing but the time answer: there is no activation to drop", "server/time", e.type());
    }

    @Test
    public void aLegacyPlayerWithoutThePlayerRoleIsUnusableAndGetsAnEmptyRoleList() throws Exception {
        player = new FakePlayer().open();
        policy.approve("kodi-1");
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null, () -> true);
        hub.start();
        player.playerWs.sendText("{\"type\":\"client/hello\",\"payload\":{\"client_id\":\"remote-1\",\"name\":\"Remote\","
                + "\"supported_roles\":[\"controller@v1\"]}}");
        Envelope hello = nextText();
        assertEquals("server/hello", hello.type());
        assertEquals(0, hello.payload().getJSONArray("active_roles").length());
        awaitPhase(HubConnection.Phase.UNUSABLE);
        assertFalse(hub.startPlayback());
    }

    @Test
    public void whenTheSwitchIsOffAClientHelloIsRefused() throws Exception {
        legacyAllowed = false;
        connect();
        Envelope answer = nextText();
        assertEquals("server/error", answer.type());
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.FAILED, hub.endReason());
    }

    @Test
    public void aGoodbyeEndsTheConnectionWithItsReason() throws Exception {
        becomeAvailable();
        player.playerWs.sendText("{\"type\":\"client/goodbye\",\"payload\":{\"reason\":\"shutdown\"}}");
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.SHUTDOWN, hub.endReason());
    }

    @Test
    public void theBytesOfTheLegacyAudioMessageAreExactlyIdTimestampAudio() {
        byte[] chunk = de.yaacc.sendspin.session.SendspinMessages.audioChunkLegacy(1, new byte[]{5}, 0, 1);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 1, 5}, chunk);
    }
}
