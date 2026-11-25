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
package de.yaacc.sendspin.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;
import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.StringSetStore;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The host with two servers on fake connections: one server plays at a time, the one whose stream started last. */
public class SendspinPlayerHostTest {

    private static final class Events implements SendspinPlayerHost.EngineFactory {
        final List<String> log = new CopyOnWriteArrayList<>();
        int opened;
        int closedCount;

        @Override
        public SendspinPlayerHost.Engine open(de.yaacc.multiroom.protocol.OffsetSource offsets, MicroClock clock) {
            final int number = ++opened;
            return new SendspinPlayerHost.Engine() {
                @Override
                public SendspinAudioEngine.Target target() {
                    return new SendspinAudioEngine.Target() {
                        @Override
                        public void onServerSettings(ServerSettingsMessage s) {
                            log.add("settings" + number + " v" + s.getVolume() + (s.isMuted() ? " muted" : ""));
                        }

                        @Override
                        public void onCodecHeader(CodecHeaderMessage h) {
                            log.add("header" + number);
                        }

                        @Override
                        public void onChunk(WireChunkMessage c) {
                            log.add("chunk" + number);
                        }

                        @Override
                        public void flush() {
                            log.add("flush" + number);
                        }

                        @Override
                        public void setManualLatencyMs(int ms) {
                            log.add("latency" + number + " " + ms);
                        }
                    };
                }

                @Override
                public void close() {
                    closedCount++;
                    log.add("close" + number);
                }
            };
        }
    }

    private static final class MapStore implements StringSetStore, SendspinPlayerHost.Store {
        final Map<String, Set<String>> map = new HashMap<>();
        int volume = 70;
        boolean muted;
        int delay = 15;

        @Override
        public Set<String> get(String key) {
            Set<String> f = map.get(key);
            return f == null ? new HashSet<String>() : new HashSet<>(f);
        }

        @Override
        public void put(String key, Set<String> values) {
            map.put(key, new HashSet<>(values));
        }

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
        public void save(int v, boolean m, int d) {
            volume = v;
            muted = m;
            delay = d;
        }
    }

    /** One server of the test: its end of a fake connection. */
    private final class Server {
        final FakePlayer link;
        SecureChannel channel;
        final Identity identity = Identity.generate(new SecureRandom());

        Server() throws Exception {
            link = new FakePlayer().open();
        }

        void connect() throws Exception {
            Future<ServerHandshake.Result> s = link.onHub(
                    () -> ServerHandshake.perform(link.playerWs, identity, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom()));
            host.accept(link.hubLink());
            channel = s.get(5, TimeUnit.SECONDS).channel;
            channel.sendJson(SendspinMessages.serverHello("Server"));
            assertEquals("client/hello", next().type());
            channel.sendJson("{\"type\":\"server/activate\",\"payload\":{\"activities\":[\"playback\"],\"active_roles\":[\"player@v1\"]}}");
            assertEquals("client/state", next().type());
        }

        Envelope next() throws Exception {
            while (true) {
                SecureChannel.Inbound in = channel.receive();
                if (!in.isJson()) {
                    continue;
                }
                Envelope e = in.envelope();
                if ("client/time".equals(e.type())) {
                    continue;
                }
                return e;
            }
        }

        void stream(int rate) throws Exception {
            channel.sendJson(SendspinMessages.streamStartPcm(1L, rate, 2, 16));
        }

        void chunk(long ts) throws Exception {
            channel.sendMessage(4, SendspinMessages.audioChunk(ts, 0L, new byte[3840], 0, 3840));
        }
    }

    private final Events events = new Events();
    private final MapStore store = new MapStore();
    private SendspinPlayerHost host;
    private final List<Server> servers = new ArrayList<>();

    private Server newServer() throws Exception {
        if (host == null) {
            host = new SendspinPlayerHost(Identity.generate(new SecureRandom()), new SendspinClientPairings(store, new SecureRandom()),
                    () -> true, "Phone", events, MicroClock.monotonic(), new SecureRandom(), store, null);
        }
        Server s = new Server();
        servers.add(s);
        return s;
    }

    @After
    public void tearDown() throws IOException {
        if (host != null) {
            host.close();
        }
        for (Server s : servers) {
            s.link.close();
        }
    }

    private void await(String entry) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!events.log.contains(entry) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(entry + " in " + events.log, events.log.contains(entry));
    }

    @Test
    public void aStreamPlaysThroughTheEngineWithTheStoredVolumeAndEndsWithIt() throws Exception {
        Server a = newServer();
        a.connect();
        a.stream(48000);
        await("header1");
        assertTrue(events.log.contains("settings1 v70"));
        assertTrue(events.log.contains("latency1 15"));
        a.chunk(5_000_000L);
        await("chunk1");
        assertEquals("Server", host.playingServerName());
        a.channel.sendJson(SendspinMessages.streamEnd());
        await("close1");
        assertFalse(host.isPlaying());
    }

    @Test
    public void theServerWhoseStreamStartedLastPlaysAndTheOtherIsToldItWasDisplaced() throws Exception {
        Server a = newServer();
        a.connect();
        a.stream(48000);
        await("header1");
        Server b = newServer();
        b.connect();
        b.stream(44100);
        await("header2");
        Envelope goodbye = a.next();
        assertEquals("client/goodbye", goodbye.type());
        assertEquals("another_server", goodbye.payload().getString("reason"));
        b.chunk(9_000_000L);
        await("chunk2");
        assertTrue("the first engine was closed", events.log.contains("close1"));
    }

    @Test
    public void aVolumeCommandIsKeptForTheNextRun() throws Exception {
        Server a = newServer();
        a.connect();
        a.channel.sendJson(SendspinMessages.commandVolume(25));
        assertEquals("client/state", a.next().type());
        assertEquals(25, store.volume);
        assertEquals(25, host.volume());
    }

    @Test
    public void connectionsBeyondTheLimitAreClosedAtOnce() throws Exception {
        for (int i = 0; i < SendspinPlayerHost.MAX_SESSIONS; i++) {
            newServer().connect();
        }
        assertEquals(SendspinPlayerHost.MAX_SESSIONS, host.sessionCount());
        Server extra = newServer();
        host.accept(extra.link.hubLink());
        assertEquals(SendspinPlayerHost.MAX_SESSIONS, host.sessionCount());
    }

    @Test
    public void aStreamWithNoClockEstimateYetStillStartsTheEngine() throws Exception {
        Server a = newServer();
        a.connect();
        assertNull(host.playingServerName());
        a.stream(44100);
        await("header1");
        assertTrue(host.isPlaying());
    }
}
