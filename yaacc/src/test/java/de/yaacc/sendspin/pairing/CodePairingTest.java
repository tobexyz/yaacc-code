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
package de.yaacc.sendspin.pairing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.PairingCodes;
import de.yaacc.sendspin.session.ProtocolException;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The two state machines of the code-based pairing (task 11.1) against each other, message by message, with no connection: the
 * dynamic flow in both formats, the static flow, a wrong code, the gating by the pairing window, and the protocol errors.
 */
public class CodePairingTest {

    private final SecureRandom random = new SecureRandom();
    private final byte[] h = new byte[32];

    {
        random.nextBytes(h);
    }

    /** A line between the two machines: what one sends the other reads. */
    private static final class Line implements PairingPort {
        final Deque<String> queue = new ArrayDeque<>();
        final List<String> all = new ArrayList<>();

        @Override
        public void send(String json) throws IOException {
            queue.add(json);
            all.add(json);
        }
    }

    private static final class Shown implements CodePairingClient.Emitter {
        String digits;
        byte[] qr;
        boolean hidden;

        @Override
        public void show(String digitsCode, byte[] qrCode) {
            digits = digitsCode;
            qr = qrCode;
            hidden = false;
        }

        @Override
        public void hide() {
            hidden = true;
        }
    }

    private final Line toClient = new Line();
    private final Line toServer = new Line();
    private final Shown shown = new Shown();
    private final PairingWindow window = new PairingWindow(() -> 1_000L);

    private CodePairingServer server(CodePairingServer.Method method) {
        return new CodePairingServer(NoiseSuite.CHACHA_POLY, h, 1, method, random, toClient);
    }

    private CodePairingClient client(String staticCode) {
        return new CodePairingClient(NoiseSuite.CHACHA_POLY, h, random, toServer, new CodePairingClient.Offer(true, true, staticCode), window, shown);
    }

    /** The long-term PSK the server took, once its machine was done (the connection then stores it and sends server/pair-finalize). */
    private byte[] serverPsk;

    /** Delivers everything that is queued, both ways, until the lines are quiet. */
    private void pump(CodePairingServer server, CodePairingClient client) throws Exception {
        boolean moved = true;
        while (moved) {
            if (server.state() == CodePairingServer.State.DONE && serverPsk == null) {
                serverPsk = server.takePsk();
                toClient.send(de.yaacc.sendspin.session.CodePairingMessages.serverPairFinalize()); // as the connection does after storing
            }
            moved = false;
            while (!toClient.queue.isEmpty()) {
                JSONObject m = new JSONObject(toClient.queue.poll());
                client.onMessage(m.getString("type"), m.getJSONObject("payload"));
                moved = true;
            }
            while (!toServer.queue.isEmpty()) {
                JSONObject m = new JSONObject(toServer.queue.poll());
                server.onMessage(m.getString("type"), m.getJSONObject("payload"));
                moved = true;
            }
        }
    }

    private void startBoth(CodePairingServer server, CodePairingClient client) throws Exception {
        server.start();
        JSONObject activate = new JSONObject(toClient.queue.poll());
        JSONObject pairing = activate.getJSONObject("payload").getJSONObject("pairing");
        client.onActivate(1, pairing.getString("method"), pairing.optString("format", null));
    }

    // ------------------------------------------------------------------ the dynamic flow

    @Test
    public void theDynamicFlowWithDigitsPairsAndBothSidesHoldTheSameKey() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_DIGITS);
        CodePairingClient c = client(null);
        startBoth(s, c);
        pump(s, c);
        assertEquals(CodePairingServer.State.WAITING_CODE, s.state());
        assertNotNull("the device shows the code", shown.digits);
        assertEquals(6, shown.digits.length());
        assertTrue(s.provideCode(PairingCodes.group(shown.digits))); // typed with its hyphen
        pump(s, c);
        assertEquals(CodePairingServer.State.DONE, s.state());
        assertEquals(CodePairingClient.State.DONE, c.state());
        assertNotNull(serverPsk);
        assertArrayEquals("both sides keep the same long-term PSK", serverPsk, c.takePsk());
        assertTrue("the code is not shown any more", shown.hidden);
        assertFalse("a completed pairing closes the window", window.isOpen());
    }

    @Test
    public void theDynamicFlowWithTheQrCodeAcceptsTheScannedToken() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_QR);
        CodePairingClient c = client(null);
        startBoth(s, c);
        pump(s, c);
        assertNotNull(shown.qr);
        assertEquals(24, shown.qr.length);
        assertFalse("a typed 6 digit code is no QR code", s.provideCode("123456"));
        assertTrue(s.provideCode(PairingCodes.codeToken(shown.qr)));
        pump(s, c);
        assertEquals(CodePairingServer.State.DONE, s.state());
        assertEquals(CodePairingClient.State.DONE, c.state());
    }

    @Test
    public void aWrongCodeCostsARoundAndTheNextRoundWithTheRightCodePairs() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_DIGITS);
        CodePairingClient c = client(null);
        startBoth(s, c);
        pump(s, c);
        String right = shown.digits;
        String wrong = right.equals("000000") ? "111111" : "000000";
        assertTrue(s.provideCode(wrong));
        pump(s, c);
        assertEquals("the client asked for another round", 2, s.round());
        assertEquals(CodePairingServer.State.WAITING_CODE, s.state());
        assertEquals("the code is the same in every round", right, shown.digits);
        assertTrue(s.provideCode(right));
        pump(s, c);
        assertEquals(CodePairingServer.State.DONE, s.state());
        assertEquals(CodePairingClient.State.DONE, c.state());
    }

    @Test
    public void theTwentyFirstRoundIsRefusedByTheClient() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_DIGITS);
        CodePairingClient c = client(null);
        startBoth(s, c);
        pump(s, c);
        String wrong = shown.digits.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 25 && s.state() == CodePairingServer.State.WAITING_CODE; i++) {
            assertTrue(s.provideCode(wrong));
            pump(s, c);
        }
        assertEquals(CodePairingServer.State.FAILED, s.state());
        assertEquals(CodePairingServer.Failure.CODE_MISMATCH, s.failure());
        assertTrue("the window is exhausted at the round limit", window.isExhausted());
        assertTrue(s.round() <= 21);
    }

    // ------------------------------------------------------------------ the static flow

    @Test
    public void theStaticFlowPairsWithTheFixedCode() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.STATIC);
        CodePairingClient c = client("12345678");
        startBoth(s, c);
        pump(s, c);
        assertEquals(CodePairingServer.State.WAITING_CODE, s.state());
        assertNull("a static code is never shown by the attempt", shown.digits);
        assertFalse("a static code has 8 digits", s.provideCode("123456"));
        assertTrue(s.provideCode("1234-5678"));
        pump(s, c);
        assertEquals(CodePairingServer.State.DONE, s.state());
        assertEquals(CodePairingClient.State.DONE, c.state());
    }

    @Test
    public void aWrongStaticCodeEndsTheAttemptAtOnce() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.STATIC);
        CodePairingClient c = client("12345678");
        startBoth(s, c);
        pump(s, c);
        assertTrue(s.provideCode("87654321"));
        pump(s, c);
        assertEquals(CodePairingServer.State.FAILED, s.state());
        assertEquals(CodePairingServer.Failure.CODE_MISMATCH, s.failure());
        assertEquals(CodePairingClient.State.FAILED, c.state());
        assertNull(serverPsk);
        assertFalse("the window stays open after one failure", window.isExhausted());
    }

    @Test
    public void theStaticCodeMayBeTypedBeforeTheDeviceHasOpenedItsWindow() throws Exception {
        CodePairingServer s = server(CodePairingServer.Method.STATIC);
        CodePairingClient c = client("12345678");
        startBoth(s, c);
        pump(s, c); // the client holds back: no window
        assertTrue(s.provideCode("12345678")); // the code is available from the start
        pump(s, c);
        assertEquals(CodePairingClient.State.PENDING_WINDOW, c.state());
        window.open();
        c.windowOpened();
        pump(s, c);
        assertEquals(CodePairingServer.State.DONE, s.state());
        assertEquals(CodePairingClient.State.DONE, c.state());
    }

    // ------------------------------------------------------------------ the gate

    @Test
    public void withoutAnOpenWindowTheClientOnlySaysItIsPending() throws Exception {
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_DIGITS);
        CodePairingClient c = client(null);
        startBoth(s, c);
        assertEquals(CodePairingClient.State.PENDING_WINDOW, c.state());
        assertTrue(toServer.all.get(0).contains("client/pair-pending"));
        assertFalse("no pair-init before the gesture", toServer.all.toString().contains("client/pair-init"));
        pump(s, c);
        assertEquals(CodePairingServer.State.WAITING_WINDOW, s.state());
        assertNull("no code is shown without a window", shown.digits);
        window.open();
        c.windowOpened();
        pump(s, c);
        assertEquals(CodePairingServer.State.WAITING_CODE, s.state());
        assertNotNull(shown.digits);
    }

    @Test
    public void aMethodTheClientDoesNotOfferIsAbortedWithMethodNotSupported() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.STATIC);
        CodePairingClient c = client(null); // no static code configured
        startBoth(s, c);
        assertTrue(toServer.all.toString().contains("method_not_supported"));
        assertEquals(CodePairingClient.State.FAILED, c.state());
    }

    // ------------------------------------------------------------------ protocol errors

    @Test
    public void aShareOfSmallOrderIsAProtocolError() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.STATIC);
        CodePairingClient c = client("12345678");
        startBoth(s, c);
        pump(s, c);
        assertTrue(s.provideCode("12345678"));
        toClient.queue.poll(); // the real server/pair-auth is dropped; a low order point takes its place
        try {
            c.onMessage("server/pair-auth", new JSONObject("{\"pake_msg_1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"));
            fail("a share that encodes the zero point must abort");
        } catch (ProtocolException expected) {
            // the connection is closed
        }
    }

    @Test
    public void aMessageOutOfSequenceIsAProtocolError() throws Exception {
        window.open();
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_DIGITS);
        s.start();
        try {
            s.onMessage("client/pair-auth", new JSONObject("{\"pake_msg_2\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"));
            fail("a share before the server asked for it");
        } catch (ProtocolException expected) {
            // refused
        }
    }

    @Test
    public void aPairingIndexThatIsHigherThanTheServersIsAProtocolErrorAndALowerOneIsIgnored() throws Exception {
        CodePairingServer s = server(CodePairingServer.Method.STATIC);
        s.start();
        s.onMessage("client/pair-init", new JSONObject("{\"pairing_index\":0}")); // a leftover of a superseded pairing
        assertEquals(CodePairingServer.State.ACTIVATED, s.state());
        try {
            s.onMessage("client/pair-init", new JSONObject("{\"pairing_index\":7}"));
            fail();
        } catch (ProtocolException expected) {
            // refused
        }
    }

    @Test
    public void theDynamicFlowRequiresTheCommitment() throws Exception {
        CodePairingServer s = server(CodePairingServer.Method.DYNAMIC_DIGITS);
        s.start();
        try {
            s.onMessage("client/pair-init", new JSONObject("{\"pairing_index\":1}"));
            fail("no commit_B in the dynamic flow");
        } catch (ProtocolException expected) {
            // refused
        }
    }
}
