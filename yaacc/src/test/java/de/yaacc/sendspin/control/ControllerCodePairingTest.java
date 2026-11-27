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
package de.yaacc.sendspin.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.SendspinPolicy;
import de.yaacc.sendspin.StringSetStore;
import de.yaacc.sendspin.client.ControllerSession;
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
 * The phone as a controller client pairs with a server by a code (task 11.1, offered by every client): the real server connection with
 * the controller role and the real {@link ControllerSession} over a fake line; the operator's gesture on the phone (the window) and the
 * entry of the code on the server, ending in a long-term key on both sides.
 */
public class ControllerCodePairingTest {

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

        @Override
        public void show(String digitsCode, byte[] qrCode) {
            digits = digitsCode;
        }

        @Override
        public void hide() {
            digits = null;
        }
    }

    private final MapStore serverStore = new MapStore();
    private final MapStore clientStore = new MapStore();
    private final SendspinPolicy policy = new SendspinPolicy(serverStore);
    private final Identity serverKeys = Identity.generate(new SecureRandom());
    private final Identity clientKeys = Identity.generate(new SecureRandom());
    private final SendspinClientPairings clientPairings = new SendspinClientPairings(clientStore, new SecureRandom());
    private final PairingWindow window = new PairingWindow(System::currentTimeMillis);
    private final Shown shown = new Shown();
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final ControllerHost host = new ControllerHost(
            new ControllerLogic(() -> java.util.Collections.<PlaybackBridge.Group>emptyList(), "Phone", new SecureRandom()),
            HubClock.SYSTEM, () -> false, 4);
    private HubConnection hub;
    private ControllerSession session;

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
        host.close();
    }

    private void connect() throws Exception {
        final FakePlayer fake = new FakePlayer().open();
        toClose.add(fake);
        hub = new HubConnection(new Endpoint("127.0.0.1", 8927, "/sendspin"), serverKeys, policy, (e, r) -> fake.hubLink(), HubClock.SYSTEM,
                new SecureRandom(), "Server", null);
        hub.setControllerHost(host);
        Connector.Link clientEnd = new Connector.Link() {
            @Override
            public WsConnection ws() {
                return fake.playerWs;
            }

            @Override
            public void close() throws IOException {
                fake.close();
            }
        };
        session = new ControllerSession(new Endpoint("127.0.0.1", 8927, "/sendspin"), clientKeys, true, (e, r) -> clientEnd, HubClock.SYSTEM,
                new SecureRandom(), "Remote", null, clientPairings);
        session.setCodePairing(new CodePairingClient.Offer(true, true, "12345678"), window, shown);
        toClose.add(hub::close);
        toClose.add(session::close);
        hub.start();
        session.start();
        waitFor("the server wants the controller paired", () -> hub.phase() == HubConnection.Phase.PAIRING_REQUIRED);
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(what, condition.getAsBoolean());
    }

    @Test(timeout = 90000)
    public void theControllerHelloOffersTheCodeMethods() throws Exception {
        connect();
        assertTrue(hub.hello().offersCodePairing());
        assertEquals(Arrays.asList("digits", "qr_code"), hub.hello().codeFormats);
        assertTrue(hub.hello().staticCodeOffered);
    }

    @Test(timeout = 90000)
    public void theDynamicFlowPairsTheControllerAndItBecomesActive() throws Exception {
        connect();
        assertTrue(hub.startCodePairing(CodePairingServer.Method.DYNAMIC_DIGITS));
        waitFor("the phone holds back without a window", () -> hub.codePairing() != null
                && hub.codePairing().state() == CodePairingServer.State.WAITING_WINDOW);
        assertEquals(null, shown.digits);
        window.open(); // the operator's gesture on the phone
        session.onWindowOpened();
        waitFor("the phone shows the code", () -> shown.digits != null);
        assertTrue(hub.submitCode(PairingCodes.group(shown.digits)));
        waitFor("paired on the server", () -> policy.isPaired(clientKeys.id()));
        waitFor("paired on the phone", () -> clientPairings.isPairedWith(serverKeys.id()));
        waitFor("the controller is served after the new handshake", () -> session.phase() == ControllerSession.Phase.ACTIVE);
        assertFalse("the window closed with the pairing", window.isOpen());
    }

    @Test(timeout = 90000)
    public void theStaticCodePairsTheController() throws Exception {
        connect();
        assertTrue(hub.startCodePairing(CodePairingServer.Method.STATIC));
        window.open();
        session.onWindowOpened();
        waitFor("the server waits for the code", () -> hub.codePairing() != null
                && hub.codePairing().state() == CodePairingServer.State.WAITING_CODE);
        assertTrue(hub.submitCode("1234-5678"));
        waitFor("paired on the server", () -> policy.isPaired(clientKeys.id()));
        waitFor("paired on the phone", () -> clientPairings.isPairedWith(serverKeys.id()));
    }
}
