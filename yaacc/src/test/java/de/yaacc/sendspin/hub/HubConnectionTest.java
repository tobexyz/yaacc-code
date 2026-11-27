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

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.SendspinPsk;
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
import java.util.Arrays;

/**
 * The whole life of a connection, against a fake player over a real loopback connection: admission, activation, time, state, the
 * stream and the commands, and every way it ends.
 */
public class HubConnectionTest {

    private FakePlayer player;
    private HubConnection hub;
    private SecureChannel peer;
    private final ServerPolicy.InMemory policy = new ServerPolicy.InMemory();
    private final Identity server = Identity.generate(new SecureRandom());

    @After
    public void tearDown() throws IOException {
        if (hub != null) {
            hub.close();
        }
        if (player != null) {
            player.close();
        }
    }

    private static final String PCM_FORMATS = "[{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16},"
            + "{\"codec\":\"flac\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16}]";
    /** FLAC is the player's first choice, PCM the second (the spec: the server takes the highest priority it can produce). */
    private static final String FLAC_FIRST = "[{\"codec\":\"flac\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16},"
            + "{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16}]";
    private static final String FLAC_ONLY = "[{\"codec\":\"flac\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16}]";
    /** A format the hub can not produce: Opus (dropped by the owner) and FLAC with 24 bits. */
    private static final String UNPRODUCIBLE = "[{\"codec\":\"opus\",\"channels\":2,\"sample_rate\":48000,\"bit_depth\":16},"
            + "{\"codec\":\"flac\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":24}]";

    private static String helloJson(boolean unpaired, String formats, String roles) {
        return "{\"type\":\"client/hello\",\"payload\":{\"name\":\"Kitchen\",\"supported_roles\":" + roles
                + ",\"player@v1_support\":{\"supported_formats\":" + formats + ",\"buffer_capacity\":1000000},"
                + "\"supported_pair_methods\":{\"pairing_psk\":{}},\"unpaired_access\":{\"enabled\":" + unpaired + "}}}";
    }

    private static String stateJson(boolean available, String commands) {
        return "{\"type\":\"client/state\",\"payload\":{\"available\":" + available + ",\"player\":{\"volume\":25,\"muted\":false,"
                + "\"output_delay_ms\":0,\"required_lead_time_ms\":250,\"min_buffer_ms\":250,\"supported_commands\":" + commands + "}}}";
    }

    /** Starts the hub against a fresh fake player and does the handshake on the player's side. */
    private void connect() throws Exception {
        player = new FakePlayer().open();
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null);
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
    public void anApprovedPlayerIsActivatedForPlaybackAndBecomesReadyAfterItsState() throws Exception {
        // approve by the id the fake player will use: create it first, then connect
        player = new FakePlayer().open();
        policy.approve(player.identity.id());
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null);
        hub.start();
        peer = player.handshake();
        assertEquals("server/hello", peer.receive().envelope().type());

        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        JSONObject idle = nextJson().payload();
        assertEquals("no playback activity while nothing plays (spec: declare the minimal set)", 0, idle.getJSONArray("activities").length());
        assertEquals("player@v1", idle.getJSONArray("active_roles").getString(0));
        assertFalse("not available before the player's state", hub.phase() == HubConnection.Phase.AVAILABLE);
        peer.sendJson(stateJson(true, "[\"volume\",\"mute\"]"));
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertNull("no stream without playback", hub.streamer());

        assertTrue(hub.startPlayback());
        JSONObject activate = nextJson().payload();
        assertEquals("playback", activate.getJSONArray("activities").getString(0));
        assertEquals("player@v1", activate.getJSONArray("active_roles").getString(0));
        assertEquals("group/update", nextJson().type());
        awaitPhase(HubConnection.Phase.READY);
        assertEquals(44100, hub.streamFormat().sampleRate);
        assertNotNull(hub.streamer());
        assertEquals(1000000L, hub.capacityBytes());
        assertEquals("what the player asked for plus the margin", 250_000L + 250_000L, hub.leadMicros());
    }

    @Test
    public void stoppingDropsThePlaybackActivityAndTheConnectionStaysAvailable() throws Exception {
        becomeReady();
        hub.stopPlayback();
        JSONObject drop = nextJson().payload();
        assertEquals(0, drop.getJSONArray("activities").length());
        assertEquals("the role stays", "player@v1", drop.getJSONArray("active_roles").getString(0));
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertNull(hub.streamer());
        assertTrue("it can play again", hub.startPlayback());
        assertEquals("playback", nextJson().payload().getJSONArray("activities").getString(0));
    }

    @Test
    public void playbackRequestedBeforeTheFirstActivationIsDeclaredByTheFirstActivation() throws Exception {
        connect();
        policy.approve(player.identity.id());
        assertTrue("accepted: the first activation will carry it", hub.startPlayback());
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        JSONObject first = nextJson().payload();
        assertEquals("a first activation without it would tie with another server's idle connection and lose", "playback",
                first.getJSONArray("activities").getString(0));
        assertEquals("player@v1", first.getJSONArray("active_roles").getString(0));
        assertEquals("group/update", nextJson().type());
        peer.sendJson(stateJson(true, "[\"volume\"]"));
        awaitPhase(HubConnection.Phase.READY);
    }

    @Test
    public void aPlayerThatIsNotApprovedGetsNothingEvenIfPlaybackWasRequested() throws Exception {
        connect();
        assertTrue(hub.startPlayback());
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        assertEquals("no activity", 0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.NEEDS_APPROVAL);
        assertNull(hub.streamer());
        hub.approve();
        assertEquals("approved by the operator: now the playback that was asked for", "playback",
                nextJson().payload().getJSONArray("activities").getString(0));
    }

    @Test
    public void playbackCanNotStartForAPlayerThatCanNotBeUsed() throws Exception {
        connect();
        peer.sendJson(helloJson(true, UNPRODUCIBLE, "[\"player@v1\"]"));
        nextJson();
        awaitPhase(HubConnection.Phase.UNUSABLE);
        assertFalse(hub.startPlayback());
    }

    @Test
    public void anUnapprovedPlayerWaitsWithNothingActivatedUntilTheOperatorApproves() throws Exception {
        connect();
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        JSONObject activate = nextJson().payload();
        assertEquals("no activity", 0, activate.getJSONArray("activities").length());
        assertEquals("no role", 0, activate.getJSONArray("active_roles").length());
        awaitPhase(HubConnection.Phase.NEEDS_APPROVAL);
        assertNull("no stream for a player that is not approved", hub.streamer());

        hub.approve();
        JSONObject second = nextJson().payload();
        assertEquals("approving does not start playback", 0, second.getJSONArray("activities").length());
        assertEquals("player@v1", second.getJSONArray("active_roles").getString(0));
        assertTrue("remembered", policy.isApproved(player.identity.id()));
        peer.sendJson(stateJson(true, "[\"volume\"]"));
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertTrue(hub.startPlayback());
        assertEquals("playback", nextJson().payload().getJSONArray("activities").getString(0));
        awaitPhase(HubConnection.Phase.READY);
    }

    @Test
    public void aPlayerThatDoesNotAdmitUnpairedAccessIsNotActivated() throws Exception {
        connect();
        policy.approve(player.identity.id());
        peer.sendJson(helloJson(false, PCM_FORMATS, "[\"player@v1\"]"));
        assertEquals("no activity", 0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.PAIRING_REQUIRED);
    }

    @Test
    public void aPlayerWithoutAFormatTheHubCanProduceIsUnusableAndNotActivated() throws Exception {
        connect();
        policy.approve(player.identity.id());
        peer.sendJson(helloJson(true, UNPRODUCIBLE, "[\"player@v1\"]"));
        assertEquals(0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.UNUSABLE);
    }

    @Test
    public void aPlayerWithoutThePlayerRoleIsUnusable() throws Exception {
        connect();
        peer.sendJson("{\"type\":\"client/hello\",\"payload\":{\"name\":\"remote\",\"supported_roles\":[\"controller@v1\"],"
                + "\"supported_pair_methods\":{},\"unpaired_access\":{\"enabled\":true}}}");
        assertEquals(0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.UNUSABLE);
    }

    @Test
    public void aCredentialMismatchMeansThePlayerNeedsPairingAgain() throws Exception {
        player = new FakePlayer().open();
        byte[] longTerm = new byte[32];
        Arrays.fill(longTerm, (byte) 5);
        ServerPolicy mismatch = new ServerPolicy.InMemory() {
            @Override
            public ServerHandshake.PskChoice pskFor(String clientId) {
                return new ServerHandshake.PskChoice(longTerm, SendspinPsk.Category.LONG_TERM);
            }
        };
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, mismatch, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", null);
        hub.start();
        peer = player.handshake(); // the player does not know the PSK and answers with the Sentinel
        assertEquals("server/hello", peer.receive().envelope().type());
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        assertEquals(0, nextJson().payload().getJSONArray("activities").length());
        awaitPhase(HubConnection.Phase.RE_PAIR_NEEDED);
        assertTrue(hub.credentialMismatch());
    }

    private void becomeReady() throws Exception {
        becomeReady(PCM_FORMATS);
    }

    private void becomeReady(String formats) throws Exception {
        connect();
        policy.approve(player.identity.id());
        peer.sendJson(helloJson(true, formats, "[\"player@v1\"]"));
        nextJson(); // activate: the role, no activity
        peer.sendJson(stateJson(true, "[\"volume\",\"mute\"]"));
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertTrue(hub.startPlayback());
        nextJson(); // activate: playback
        nextJson(); // group/update
        awaitPhase(HubConnection.Phase.READY);
    }

    @Test
    public void timeMessagesAreAnsweredWithTheFourValuesInOrder() throws Exception {
        becomeReady();
        peer.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":123456}}");
        JSONObject time = nextJson().payload();
        assertEquals(123456L, time.getLong("client_transmitted"));
        assertTrue(time.getLong("server_received") <= time.getLong("server_transmitted"));
    }

    @Test
    public void unknownMessageTypesAreIgnored() throws Exception {
        becomeReady();
        peer.sendJson("{\"type\":\"something/new\",\"payload\":{\"x\":1}}");
        peer.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":1}}");
        assertEquals("server/time", nextJson().type());
        assertTrue(hub.isReady());
    }

    @Test
    public void aSecondClientHelloIsAProtocolError() throws Exception {
        becomeReady();
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.PROTOCOL_ERROR, hub.endReason());
    }

    @Test
    public void aStateOutOfRangeIsAProtocolError() throws Exception {
        becomeReady();
        peer.sendJson("{\"type\":\"client/state\",\"payload\":{\"available\":true,\"player\":{\"volume\":500,\"output_delay_ms\":0,"
                + "\"required_lead_time_ms\":0,\"min_buffer_ms\":0,\"supported_commands\":[]}}}");
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.PROTOCOL_ERROR, hub.endReason());
    }

    @Test
    public void everyGoodbyeReasonIsMapped() throws Exception {
        String[][] cases = {{"another_server", "ANOTHER_SERVER"}, {"shutdown", "SHUTDOWN"}, {"restart", "RESTART"},
                {"user_request", "USER_REQUEST"}, {"unauthorized", "UNAUTHORIZED"}, {"pairing_required", "PAIRING_REQUIRED"},
                {"concurrent_attempt", "CONCURRENT_ATTEMPT"}, {"unpaired", "UNPAIRED"}, {"something_new", "OTHER"}};
        for (String[] c : cases) {
            tearDown();
            becomeReady();
            peer.sendJson("{\"type\":\"client/goodbye\",\"payload\":{\"reason\":\"" + c[0] + "\"}}");
            awaitPhase(HubConnection.Phase.ENDED);
            assertEquals(c[0], HubConnection.EndReason.valueOf(c[1]), hub.endReason());
        }
    }

    @Test
    public void volumeAndMuteGoOutOnlyWhenThePlayerOffersThem() throws Exception {
        becomeReady();
        assertTrue(hub.setVolume(40));
        JSONObject volume = nextJson().payload().getJSONObject("player");
        assertEquals("volume", volume.getString("command"));
        assertEquals(40, volume.getInt("volume"));
        assertTrue(hub.setMuted(true));
        assertTrue(nextJson().payload().getJSONObject("player").getBoolean("mute"));
        assertEquals("clamped to the range", 100, clampedVolume());
        // a player that offers neither
        tearDown();
        connect();
        policy.approve(player.identity.id());
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        nextJson();
        peer.sendJson(stateJson(true, "[]"));
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertFalse(hub.setVolume(10));
        assertFalse(hub.setMuted(true));
    }

    private int clampedVolume() throws Exception {
        assertTrue(hub.setVolume(500));
        return nextJson().payload().getJSONObject("player").getInt("volume");
    }

    @Test
    public void thePcmOfTheStreamerReachesThePlayerAsStartAndAudioMessages() throws Exception {
        becomeReady();
        PcmStreamer streamer = hub.streamer();
        byte[] chunk = new byte[882 * 4];
        assertTrue(streamer.write(chunk, 0, chunk.length));
        Envelope start = nextJson();
        assertEquals("stream/start", start.type());
        assertEquals(44100, start.payload().getJSONObject("player").getInt("sample_rate"));
        assertEquals("pcm", start.payload().getJSONObject("player").getString("codec"));
        SecureChannel.Inbound audio = peer.receive();
        assertEquals(SendspinMessages.AUDIO_CHUNK_ID, audio.id());
        assertEquals(12 + chunk.length, audio.body().length);

        streamer.end();
        assertEquals("stream/end", nextJson().type());
    }

    @Test
    public void aPlayerThatListsFlacFirstGetsFlacWithItsHeaderAndFlacFrames() throws Exception {
        becomeReady(FLAC_FIRST);
        assertEquals(java.util.Collections.singleton(44100), hub.supportedRates());
        PcmStreamer streamer = hub.streamer();
        byte[] chunk = new byte[882 * 4];
        for (int i = 0; i < 882; i++) {
            short v = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / 44100.0));
            chunk[4 * i] = (byte) v;
            chunk[4 * i + 1] = (byte) (v >> 8);
            chunk[4 * i + 2] = (byte) v;
            chunk[4 * i + 3] = (byte) (v >> 8);
        }
        assertTrue(streamer.write(chunk, 0, chunk.length));
        Envelope start = nextJson();
        assertEquals("stream/start", start.type());
        JSONObject p = start.payload().getJSONObject("player");
        assertEquals("flac", p.getString("codec"));
        assertEquals(44100, p.getInt("sample_rate"));
        assertEquals(2, p.getInt("channels"));
        assertEquals(16, p.getInt("bit_depth"));
        byte[] header = java.util.Base64.getDecoder().decode(p.getString("codec_header"));
        assertEquals("the fLaC marker and the 34 bytes of STREAMINFO", 42, header.length);
        assertEquals("fLaC", new String(header, 0, 4, "US-ASCII"));
        SecureChannel.Inbound audio = peer.receive();
        assertEquals(SendspinMessages.AUDIO_CHUNK_ID, audio.id());
        byte[] body = audio.body();
        assertEquals("a FLAC frame starts with the sync code", 0xFF, body[12] & 0xFF);
        assertEquals(0xF8, body[13] & 0xFF);
        assertTrue("smaller than the PCM it carries: " + (body.length - 12), body.length - 12 < chunk.length / 2);
        streamer.end();
        assertEquals("stream/end", nextJson().type());
    }

    @Test
    public void flacCanBeTheOnlyFormatOfAPlayer() throws Exception {
        becomeReady(FLAC_ONLY);
        assertEquals(44100, hub.streamFormat().sampleRate);
    }

    @Test
    public void closingEndsARunningStreamFirst() throws Exception {
        becomeReady();
        hub.streamer().write(new byte[882 * 4], 0, 882 * 4);
        nextJson(); // stream/start
        peer.receive(); // the chunk
        hub.close();
        assertEquals("stream/end", nextJson().type());
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.CLOSED_BY_APP, hub.endReason());
    }

    @Test
    public void aPlayerThatCannotBeReachedEndsTheConnectionAsFailed() throws Exception {
        Connector refusing = (endpoint, random) -> {
            throw new IOException("connection failed");
        };
        hub = new HubConnection(new Endpoint("127.0.0.1", 1, "/sendspin"), server, policy, refusing, HubClock.SYSTEM, new SecureRandom(), "YAACC test", null);
        hub.start();
        awaitPhase(HubConnection.Phase.ENDED);
        assertEquals(HubConnection.EndReason.FAILED, hub.endReason());
    }

    @Test
    public void theListenerHearsEveryChange() throws Exception {
        final java.util.List<HubConnection.Phase> phases = new java.util.concurrent.CopyOnWriteArrayList<>();
        player = new FakePlayer().open();
        policy.approve(player.identity.id());
        Connector connector = (endpoint, random) -> player.hubLink();
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), server, policy, connector, HubClock.SYSTEM,
                new SecureRandom(), "YAACC test", c -> phases.add(c.phase()));
        hub.start();
        peer = player.handshake();
        peer.receive();
        peer.sendJson(helloJson(true, PCM_FORMATS, "[\"player@v1\"]"));
        nextJson();
        peer.sendJson(stateJson(true, "[]"));
        awaitPhase(HubConnection.Phase.AVAILABLE);
        assertTrue(hub.startPlayback());
        awaitPhase(HubConnection.Phase.READY);
        assertTrue(phases.toString(), phases.containsAll(Arrays.asList(HubConnection.Phase.CONNECTING, HubConnection.Phase.HANDSHAKING,
                HubConnection.Phase.WAITING_FOR_HELLO, HubConnection.Phase.ACTIVATING, HubConnection.Phase.AVAILABLE, HubConnection.Phase.READY)));
    }
}
