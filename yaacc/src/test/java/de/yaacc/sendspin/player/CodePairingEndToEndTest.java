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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.SendspinPolicy;
import de.yaacc.sendspin.StringSetStore;
import de.yaacc.sendspin.client.PlayerSession;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.pairing.CodePairingClient;
import de.yaacc.sendspin.pairing.CodePairingServer;
import de.yaacc.sendspin.pairing.PairingWindow;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.PairingCodes;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.PlayerMessages;
import de.yaacc.sendspin.session.WsConnection;

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
 * The code-based pairing end to end on the JVM: the real server connection and the real player session over a fake line, the
 * operator's gesture on the player (the window) and entry on the server (the code), in both formats of the dynamic flow and the static
 * flow, ending in the new long-term PSK on both sides and an in-band re-handshake.
 */
public class CodePairingEndToEndTest {

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

    private static final class Shown implements CodePairingClient.Emitter {
        volatile String digits;
        volatile byte[] qr;

        @Override
        public void show(String digitsCode, byte[] qrCode) {
            digits = digitsCode;
            qr = qrCode;
        }

        @Override
        public void hide() {
            digits = null;
            qr = null;
        }
    }

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final MapStore serverStore = new MapStore();
    private final MapStore playerStore = new MapStore();
    private final SendspinPolicy policy = new SendspinPolicy(serverStore);
    private final Identity serverKeys = Identity.generate(new SecureRandom());
    private final Identity playerKeys = Identity.generate(new SecureRandom());
    private final SendspinClientPairings playerPairings = new SendspinClientPairings(playerStore, new SecureRandom());
    private final PairingWindow window = new PairingWindow(System::currentTimeMillis);
    private final Shown shown = new Shown();
    private HubConnection hub;
    private PlayerSession session;

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    private void connect(String staticCode) throws Exception {
        final FakePlayer fake = new FakePlayer().open();
        toClose.add(fake);
        hub = new HubConnection(new Endpoint("127.0.0.1", 8928, "/sendspin"), serverKeys, policy, (e, r) -> fake.hubLink(), HubClock.SYSTEM,
                new SecureRandom(), "Server", null);
        Connector.Link playerEnd = new Connector.Link() {
            @Override
            public WsConnection ws() {
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
            @Override public int volume() { return 50; }
            @Override public boolean muted() { return false; }
            @Override public int outputDelayMs() { return 0; }
            @Override public void setVolume(int v) { }
            @Override public void setMuted(boolean m) { }
            @Override public void setOutputDelayMs(int ms) { }
        };
        session = new PlayerSession(playerEnd, playerKeys, false, playerPairings, HubClock.SYSTEM, new SecureRandom(), "Player",
                Arrays.asList(new PlayerMessages.Offer("pcm", 48000, 2, 16)), 1_000_000L, 500, 800, audio, controls, null);
        session.setCodePairing(new CodePairingClient.Offer(true, true, staticCode), window, shown);
        toClose.add(hub::close);
        toClose.add(session::close);
        session.start();
        hub.start();
        waitFor("the server sees a player that wants pairing", () -> hub.phase() == HubConnection.Phase.PAIRING_REQUIRED);
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(what, condition.getAsBoolean());
    }

    private void assertPaired() throws Exception {
        waitFor("paired on the server", () -> policy.isPaired(playerKeys.id()));
        waitFor("paired on the player", () -> playerPairings.isPairedWith(serverKeys.id()));
        waitFor("the connection went on under the new key", () -> hub.isPaired());
        assertFalse("the window closed with the pairing", window.isOpen());
    }

    @Test(timeout = 90000)
    public void theHelloOffersTheCodeMethodsAndTheServerSeesThem() throws Exception {
        connect("12345678");
        assertTrue(hub.hello().offersCodePairing());
        assertEquals(Arrays.asList("digits", "qr_code"), hub.hello().codeFormats);
        assertTrue(hub.hello().staticCodeOffered);
    }

    @Test(timeout = 90000)
    public void theDynamicFlowWithDigitsPairsAndTheConnectionContinuesPaired() throws Exception {
        connect(null);
        assertTrue(hub.startCodePairing(CodePairingServer.Method.DYNAMIC_DIGITS));
        waitFor("the player holds back without a window", () -> hub.codePairing() != null
                && hub.codePairing().state() == CodePairingServer.State.WAITING_WINDOW);
        assertEquals(null, shown.digits);
        window.open(); // the operator's gesture on the player
        session.onWindowOpened();
        waitFor("the player shows the code", () -> shown.digits != null);
        assertTrue(hub.submitCode(PairingCodes.group(shown.digits)));
        assertPaired();
    }

    @Test(timeout = 90000)
    public void theDynamicFlowWithTheQrCodePairs() throws Exception {
        connect(null);
        window.open();
        assertTrue(hub.startCodePairing(CodePairingServer.Method.DYNAMIC_QR));
        waitFor("the player shows the QR code", () -> shown.qr != null);
        assertTrue(hub.submitCode(PairingCodes.codeToken(shown.qr)));
        assertPaired();
    }

    @Test(timeout = 90000)
    public void theStaticFlowPairsWithTheFixedCode() throws Exception {
        connect("12345678");
        window.open();
        assertTrue(hub.startCodePairing(CodePairingServer.Method.STATIC));
        waitFor("the server waits for the code", () -> hub.codePairing().state() == CodePairingServer.State.WAITING_CODE);
        assertTrue(hub.submitCode("1234-5678"));
        assertPaired();
    }

    @Test(timeout = 90000)
    public void aWrongStaticCodeLeavesTheConnectionUnpairedAndTheFailureShown() throws Exception {
        connect("12345678");
        window.open();
        assertTrue(hub.startCodePairing(CodePairingServer.Method.STATIC));
        waitFor("the server waits for the code", () -> hub.codePairing().state() == CodePairingServer.State.WAITING_CODE);
        assertTrue(hub.submitCode("87654321"));
        waitFor("the attempt failed", () -> hub.phase() == HubConnection.Phase.PAIRING_FAILED);
        assertEquals(HubConnection.PairingFailure.CODE_REJECTED, hub.pairingFailure());
        assertFalse(policy.isPaired(playerKeys.id()));
    }

    @Test(timeout = 90000)
    public void aMethodThePlayerDoesNotOfferIsNotStarted() throws Exception {
        connect(null); // no static code offered
        assertFalse(hub.startCodePairing(CodePairingServer.Method.STATIC));
        assertNotNull(hub.hello());
    }
}
