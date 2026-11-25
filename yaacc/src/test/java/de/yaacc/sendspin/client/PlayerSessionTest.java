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
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.sendspin.session.SendspinMessages;
import de.yaacc.sendspin.session.ServerHandshake;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The player session against a fake server (the server side of the real handshake over a loopback connection). */
public class PlayerSessionTest {

    private static final class Recorder implements PlayerSession.Audio {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Long> timestamps = new CopyOnWriteArrayList<>();

        @Override
        public void start(String codec, int sampleRate, int channels, int bitDepth, byte[] codecHeader) {
            events.add("start " + codec + " " + sampleRate + "/" + channels + "/" + bitDepth + (codecHeader == null ? "" : " header" + codecHeader.length));
        }

        @Override
        public void chunk(long serverTimestampMicros, byte[] pcm) {
            events.add("chunk " + pcm.length);
            timestamps.add(serverTimestampMicros);
        }

        @Override
        public void clear(Long fromTimestamp) {
            events.add("clear " + fromTimestamp);
        }

        @Override
        public void end() {
            events.add("end");
        }
    }

    private static final class FakeControls implements PlayerSession.Controls {
        volatile int volume = 50;
        volatile boolean muted;
        volatile int delay;

        @Override
        public int volume() {
            return volume;
        }

        @Override
        public boolean muted() {
            return muted;
        }

        @Override
        public int outputDelayMs() {
            return delay;
        }

        @Override
        public void setVolume(int v) {
            volume = v;
        }

        @Override
        public void setMuted(boolean m) {
            muted = m;
        }

        @Override
        public void setOutputDelayMs(int ms) {
            delay = ms;
        }
    }

    private FakePlayer link;
    private PlayerSession session;
    private SecureChannel server;
    private final Identity serverIdentity = Identity.generate(new SecureRandom());
    private final Identity clientIdentity = Identity.generate(new SecureRandom());
    private final Recorder audio = new Recorder();
    private final FakeControls controls = new FakeControls();

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
        List<de.yaacc.sendspin.session.PlayerMessages.Offer> formats = Arrays.asList(new de.yaacc.sendspin.session.PlayerMessages.Offer("pcm", 48000, 2, 16),
                new de.yaacc.sendspin.session.PlayerMessages.Offer("pcm", 44100, 2, 16), new de.yaacc.sendspin.session.PlayerMessages.Offer("flac", 48000, 2, 16));
        session = new PlayerSession(link.hubLink(), clientIdentity, unpairedAccess, null, HubClock.SYSTEM, new SecureRandom(), "Phone", formats,
                1_000_000L, 500, 800, audio, controls, null);
        session.setBurstIntervalMillis(100);
        Future<ServerHandshake.Result> s = link.onHub(
                () -> ServerHandshake.perform(link.playerWs, serverIdentity, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom()));
        session.start();
        server = s.get(5, TimeUnit.SECONDS).channel;
        server.sendJson(SendspinMessages.serverHello("Music Assistant"));
    }

    private Envelope next() throws Exception {
        while (true) {
            while (true) {
                SecureChannel.Inbound inbound = server.receive();
                if (inbound.isJson()) {
                    Envelope e = inbound.envelope();
                    if ("client/time".equals(e.type())) {
                        long t1 = e.payload().getLong("client_transmitted");
                        server.sendJson(SendspinMessages.serverTime(t1, t1 + 5_000_000L, t1 + 5_000_020L));
                        continue;
                    }
                    return e;
                }
            }
        }
    }

    private void activatePlayer() throws Exception {
        connect(true);
        assertEquals("client/hello", next().type());
        server.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":[\"playback\"],\"active_roles\":[\"player@v1\"]}}");
        assertEquals("client/state", next().type());
        awaitPhase(PlayerSession.Phase.ACTIVE);
    }

    private void awaitPhase(PlayerSession.Phase expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (session.phase() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, session.phase());
    }

    private void awaitEvents(int count) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (audio.events.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(audio.events.toString(), audio.events.size() >= count);
    }

    @Test
    public void theHelloOffersThePlayerRoleAndTheStateTellsTheVolumeAfterTheActivation() throws Exception {
        connect(true);
        Envelope hello = next();
        assertEquals("client/hello", hello.type());
        assertTrue(hello.payload().getJSONArray("supported_roles").toString().contains("player@v1"));
        assertEquals(3, hello.payload().getJSONObject("player@v1_support").getJSONArray("supported_formats").length());
        server.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":[\"playback\"],\"active_roles\":[\"player@v1\"]}}");
        Envelope state = next();
        assertEquals("client/state", state.type());
        assertEquals(50, state.payload().getJSONObject("player").getInt("volume"));
        assertEquals(500, state.payload().getJSONObject("player").getInt("required_lead_time_ms"));
        awaitPhase(PlayerSession.Phase.ACTIVE);
    }

    @Test
    public void aStreamIsHandedToTheAudioInOrderAndAClearAndAnEndAreToo() throws Exception {
        activatePlayer();
        server.sendJson(SendspinMessages.streamStartPcm(1_000L, 48000, 2, 16));
        awaitEvents(1);
        assertEquals("start pcm 48000/2/16", audio.events.get(0));
        server.sendMessage(4, SendspinMessages.audioChunk(7_000_000L, 500_000L, new byte[3840], 0, 3840));
        server.sendMessage(4, SendspinMessages.audioChunk(7_020_000L, 500_000L, new byte[3840], 0, 3840));
        awaitEvents(3);
        assertEquals(Arrays.asList(7_000_000L, 7_020_000L), audio.timestamps);
        server.sendJson(SendspinMessages.streamClear(2_000L, null));
        awaitEvents(4);
        assertEquals("clear null", audio.events.get(3));
        server.sendJson(SendspinMessages.streamEnd());
        awaitEvents(5);
        assertEquals("end", audio.events.get(4));
        assertFalse(session.isStreaming());
    }

    @Test
    public void audioWithoutAStreamOrWithoutTheRoleIsIgnored() throws Exception {
        connect(true);
        assertEquals("client/hello", next().type());
        server.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":[],\"active_roles\":[]}}");
        awaitPhase(PlayerSession.Phase.WAITING_FOR_SERVER);
        server.sendJson(SendspinMessages.streamStartPcm(1_000L, 48000, 2, 16));
        server.sendMessage(4, SendspinMessages.audioChunk(1L, 0L, new byte[3840], 0, 3840));
        Thread.sleep(200);
        assertTrue("no player role, no audio", audio.events.isEmpty());
    }

    @Test
    public void aVolumeAMuteAndAnOutputDelayCommandAreAppliedAndAnswered() throws Exception {
        activatePlayer();
        server.sendJson(SendspinMessages.commandVolume(30));
        Envelope state = next();
        assertEquals(30, state.payload().getJSONObject("player").getInt("volume"));
        assertEquals(30, controls.volume);
        server.sendJson(SendspinMessages.commandMute(true));
        assertTrue(next().payload().getJSONObject("player").getBoolean("muted"));
        server.sendJson(SendspinMessages.commandOutputDelay(120));
        assertEquals(120, next().payload().getJSONObject("player").getInt("output_delay_ms"));
        assertEquals(120, controls.delay);
    }

    @Test
    public void aFlacStreamWithItsHeaderIsHandedToTheAudioAndOneWithoutAHeaderEndsTheSession() throws Exception {
        activatePlayer();
        byte[] header = new byte[42];
        String text = java.util.Base64.getEncoder().encodeToString(header);
        server.sendJson("{\"type\":\"stream/start\",\"payload\":{\"server_transmitted\":1,\"player\":{\"codec\":\"flac\",\"sample_rate\":48000,"
                + "\"channels\":2,\"bit_depth\":16,\"codec_header\":\"" + text + "\"}}}");
        awaitEvents(1);
        assertEquals("start flac 48000/2/16 header42", audio.events.get(0));
        server.sendJson("{\"type\":\"stream/start\",\"payload\":{\"server_transmitted\":2,\"player\":{\"codec\":\"flac\",\"sample_rate\":48000,"
                + "\"channels\":2,\"bit_depth\":16}}}");
        awaitPhase(PlayerSession.Phase.ENDED);
        assertEquals(PlayerSession.EndReason.PROTOCOL_ERROR, session.endReason());
    }

    @Test
    public void aStreamInAFormatThatWasNotOfferedEndsTheSession() throws Exception {
        activatePlayer();
        server.sendJson(SendspinMessages.streamStartPcm(1_000L, 22050, 2, 16));
        awaitPhase(PlayerSession.Phase.ENDED);
        assertEquals(PlayerSession.EndReason.PROTOCOL_ERROR, session.endReason());
        assertTrue(audio.events.isEmpty());
    }

    @Test
    public void theClockBurstsGiveAnEstimateOfTheServersClock() throws Exception {
        activatePlayer();
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!session.hasClockEstimate() && System.nanoTime() < deadline) {
            Envelope e;
            try {
                e = server.receive().envelope();
            } catch (java.net.SocketTimeoutException idle) {
                continue; // the session starts its first burst when its own line has been idle
            }
            if ("client/time".equals(e.type())) {
                long t1 = e.payload().getLong("client_transmitted");
                server.sendJson(SendspinMessages.serverTime(t1, t1 + 5_000_000L, t1 + 5_000_020L));
            }
        }
        assertTrue(session.hasClockEstimate());
        long clientTime = 1_000_000_000L;
        long mapped = session.serverToClientMicros(clientTime + 5_000_000L);
        assertTrue("about five seconds of offset: " + (mapped - clientTime), Math.abs(mapped - clientTime) < 50_000L);
    }

    @Test
    public void aServerThatPlaysUnpairedWhileThePhoneDemandsPairingIsRefused() throws Exception {
        connect(false);
        assertEquals("client/hello", next().type());
        server.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":[\"playback\"],\"active_roles\":[\"player@v1\"]}}");
        Envelope goodbye = next();
        assertEquals("client/goodbye", goodbye.type());
        assertEquals("pairing_required", goodbye.payload().getString("reason"));
        awaitPhase(PlayerSession.Phase.ENDED);
        assertEquals(PlayerSession.EndReason.PAIRING_REQUIRED, session.endReason());
    }

    @Test
    public void theEndOfTheSessionEndsTheStreamOfTheAudio() throws Exception {
        activatePlayer();
        server.sendJson(SendspinMessages.streamStartPcm(1_000L, 44100, 2, 16));
        awaitEvents(1);
        session.close();
        awaitPhase(PlayerSession.Phase.ENDED);
        awaitEvents(2);
        assertEquals("end", audio.events.get(audio.events.size() - 1));
    }
}
