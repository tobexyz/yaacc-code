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
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.SendspinPolicy;
import de.yaacc.sendspin.StringSetStore;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.client.PlayerSession;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.PlayerMessages;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two instances of the app, as the owner tried it on two phones: the first is the server (hub, policy with its stores), the second is
 * the phone as a player that does not admit unpaired servers (its session, its client keys, its pairing store). The token that the player
 * shows is pasted into the server; the pairing runs; the next connection is paired and ready.
 */
public class TwoInstancesPairingTest {

    private static final class MapStore implements StringSetStore {
        final Map<String, Set<String>> map = new HashMap<>();

        @Override
        public Set<String> get(String key) {
            Set<String> f = map.get(key);
            return f == null ? new HashSet<String>() : new HashSet<>(f);
        }

        @Override
        public void put(String key, Set<String> values) {
            map.put(key, new HashSet<>(values));
        }
    }

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final MapStore serverStore = new MapStore();
    private final MapStore playerStore = new MapStore();
    private final SendspinPolicy serverPolicy = new SendspinPolicy(serverStore);
    private final Identity serverKeys = Identity.generate(new SecureRandom());
    private final Identity playerKeys = Identity.generate(new SecureRandom()); // the phone's client keys, used for the token and the connection
    private final SendspinClientPairings playerPairings = new SendspinClientPairings(playerStore, new SecureRandom());

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    private final class Pair {
        final HubConnection hub;
        final PlayerSession session;

        Pair() throws Exception {
            final FakePlayer fake = new FakePlayer().open();
            toClose.add(fake);
            Connector connector = (endpoint, random) -> fake.hubLink();
            hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), serverKeys, serverPolicy, connector, HubClock.SYSTEM,
                    new SecureRandom(), "Server phone", null);
            Connector.Link playerEnd = new Connector.Link() {
                @Override
                public de.yaacc.sendspin.session.WsConnection ws() {
                    return fake.playerWs;
                }

                @Override
                public void close() throws IOException {
                    fake.close();
                }
            };
            PlayerSession.Audio audio = new PlayerSession.Audio() {
                @Override
                public void start(String codec, int rate, int channels, int bits, byte[] header) {
                }

                @Override
                public void chunk(long ts, byte[] pcm) {
                }

                @Override
                public void clear(Long from) {
                }

                @Override
                public void end() {
                }
            };
            PlayerSession.Controls controls = new PlayerSession.Controls() {
                @Override
                public int volume() {
                    return 50;
                }

                @Override
                public boolean muted() {
                    return false;
                }

                @Override
                public int outputDelayMs() {
                    return 0;
                }

                @Override
                public void setVolume(int v) {
                }

                @Override
                public void setMuted(boolean m) {
                }

                @Override
                public void setOutputDelayMs(int ms) {
                }
            };
            session = new PlayerSession(playerEnd, playerKeys, false, playerPairings, HubClock.SYSTEM, new SecureRandom(), "Player phone",
                    Arrays.asList(new PlayerMessages.Offer("pcm", 48000, 2, 16)), 1_000_000L, 500, 800, audio, controls, null);
            toClose.add(hub::close);
            toClose.add(session::close);
        }

        void start() {
            session.start();
            hub.start();
        }
    }

    private static void await(HubConnection hub, HubConnection.Phase phase) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (hub.phase() != phase && hub.phase() != HubConnection.Phase.ENDED && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(phase, hub.phase());
    }

    @Test(timeout = 60000)
    public void theTokenOfThePlayerIsTheOneTheServerExpectsAndThePairingGoesThrough() throws Exception {
        Pair first = new Pair();
        first.start();
        // the player admits no unpaired server: the server shows "pairing required" and knows the id the player presented
        await(first.hub, HubConnection.Phase.PAIRING_REQUIRED);
        assertEquals("the id the player presented is its client key", playerKeys.id(), first.hub.clientId());

        String token = playerPairings.token(playerKeys); // what the player's screen shows
        assertEquals("the server accepts the token for this player", SendspinPolicy.PairOutcome.STARTED,
                serverPolicy.pairWithToken(token, first.hub.clientId()));
        first.hub.close();
        first.session.close();

        Pair second = new Pair(); // the next connection references the Pairing PSK; both sides run the flow
        second.start();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!second.hub.isPaired() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("paired: phase " + second.hub.phase() + " failure " + second.hub.pairingFailure(), second.hub.isPaired());
        assertTrue(serverPolicy.isPaired(playerKeys.id()));
        assertTrue(playerPairings.isPairedWith(serverKeys.id()));
    }
}
