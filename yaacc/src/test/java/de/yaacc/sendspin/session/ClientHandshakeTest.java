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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The client's side of the opening exchange against the server's side of the same code, over a real loopback connection. */
public class ClientHandshakeTest {

    private FakePlayer link; // used only for its two ends of a WebSocket connection
    private final Identity server = Identity.generate(new SecureRandom());
    private final Identity client = Identity.generate(new SecureRandom());

    @After
    public void tearDown() throws IOException {
        if (link != null) {
            link.close();
        }
    }

    private static byte[] psk(int seed) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) seed);
        return b;
    }

    private Future<ServerHandshake.Result> serverSide(ServerHandshake.PskChoice choice) {
        return link.onHub(() -> ServerHandshake.perform(link.hubWs, server, id -> choice, new SecureRandom()));
    }

    private ClientHandshake.Result clientSide(NoiseSuite suite, ClientHandshake.PskLookup lookup) throws Exception {
        return ClientHandshake.perform(link.playerWs, client, suite, lookup, new SecureRandom());
    }

    @Test
    public void bothSuitesComeUpUnderTheSentinelAndTheChannelWorksBothWays() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            if (link != null) {
                link.close();
            }
            link = new FakePlayer().open();
            Future<ServerHandshake.Result> s = serverSide(ServerHandshake.PskChoice.sentinel());
            ClientHandshake.Result c = clientSide(suite, (id, category) -> null);
            ServerHandshake.Result sr = s.get(5, TimeUnit.SECONDS);

            assertEquals(server.id(), c.serverId);
            assertEquals(client.id(), sr.clientId);
            assertEquals(SendspinPsk.Category.SENTINEL, c.matched);
            assertFalse("the Sentinel was referenced: no fallback", c.fellBack);
            assertEquals(suite, sr.suite);

            sr.channel.sendJson(SendspinMessages.serverHello("YAACC test"));
            assertEquals("YAACC test", ControllerMessages.parseServerHello(c.channel.receive().envelope().payload()));
            c.channel.sendJson(ControllerMessages.clientState(true));
            assertEquals("client/state", sr.channel.receive().envelope().type());
        }
    }

    @Test
    public void aPskTheClientHoldsIsUsedUnderItsCategory() throws Exception {
        for (SendspinPsk.Category category : new SendspinPsk.Category[]{SendspinPsk.Category.PAIRING, SendspinPsk.Category.LONG_TERM}) {
            if (link != null) {
                link.close();
            }
            link = new FakePlayer().open();
            Future<ServerHandshake.Result> s = serverSide(new ServerHandshake.PskChoice(psk(9), category));
            ClientHandshake.Result c = clientSide(NoiseSuite.CHACHA_POLY, (id, cat) ->
                    id.equals(SendspinPsk.pskId(psk(9))) && cat == category ? new ClientHandshake.Held(psk(9), null) : null);
            ServerHandshake.Result sr = s.get(5, TimeUnit.SECONDS);
            assertEquals(category, c.matched);
            assertEquals(category, sr.verifiedAs);
            assertFalse(sr.credentialMismatch);
            assertFalse(c.fellBack);
        }
    }

    @Test
    public void aPskTheClientDoesNotHoldFallsBackToTheSentinelAndTheServerSeesAMismatch() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> s = serverSide(new ServerHandshake.PskChoice(psk(9), SendspinPsk.Category.LONG_TERM));
        ClientHandshake.Result c = clientSide(NoiseSuite.CHACHA_POLY, (id, cat) -> null);
        ServerHandshake.Result sr = s.get(5, TimeUnit.SECONDS);
        assertEquals(SendspinPsk.Category.SENTINEL, c.matched);
        assertTrue(c.fellBack);
        assertTrue("the server gets the authenticated credential-mismatch signal", sr.credentialMismatch);
        assertEquals(SendspinPsk.Category.SENTINEL, sr.verifiedAs);
    }

    @Test
    public void aPskHeldUnderAnotherCategoryIsAMissAndFallsBack() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> s = serverSide(new ServerHandshake.PskChoice(psk(9), SendspinPsk.Category.LONG_TERM));
        ClientHandshake.Result c = clientSide(NoiseSuite.CHACHA_POLY, (id, cat) ->
                cat == SendspinPsk.Category.PAIRING ? new ClientHandshake.Held(psk(9), null) : null);
        assertTrue(c.fellBack);
        assertTrue(s.get(5, TimeUnit.SECONDS).credentialMismatch);
    }

    @Test
    public void aLongTermPskOfAnotherServerFailsTheHandshake() throws Exception {
        link = new FakePlayer().open();
        serverSide(new ServerHandshake.PskChoice(psk(9), SendspinPsk.Category.LONG_TERM));
        try {
            clientSide(NoiseSuite.CHACHA_POLY, (id, cat) -> new ClientHandshake.Held(psk(9), "someone else's id........................"));
            fail("a misbinding fails, it is not a miss");
        } catch (ServerHandshake.HandshakeException expected) {
            // the record belongs to another server
        }
    }

    @Test
    public void aServerErrorEndsTheHandshakeWithItsReason() throws Exception {
        link = new FakePlayer().open();
        link.onHub(() -> {
            link.hubWs.receive();
            link.hubWs.sendText(SendspinMessages.serverError("unsupported_suite"));
            return null;
        });
        try {
            clientSide(NoiseSuite.AES_GCM, (id, cat) -> null);
            fail();
        } catch (ServerHandshake.HandshakeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unsupported_suite"));
        }
    }

    @Test
    public void aMalformedServerInitEndsTheHandshake() throws Exception {
        link = new FakePlayer().open();
        link.onHub(() -> {
            link.hubWs.receive();
            link.hubWs.sendText("{\"type\":\"server/init\",\"payload\":{\"server_id\":\"short\",\"version\":1}}");
            return null;
        });
        try {
            clientSide(NoiseSuite.CHACHA_POLY, (id, cat) -> null);
            fail();
        } catch (ServerHandshake.HandshakeException expected) {
            // not a 32 byte key
        }
    }

    @Test
    public void aWrongServerVersionEndsTheHandshake() throws Exception {
        link = new FakePlayer().open();
        link.onHub(() -> {
            link.hubWs.receive();
            link.hubWs.sendText("{\"type\":\"server/init\",\"payload\":{\"server_id\":\"" + server.id() + "\",\"version\":2}}");
            return null;
        });
        try {
            clientSide(NoiseSuite.CHACHA_POLY, (id, cat) -> null);
            fail();
        } catch (ServerHandshake.HandshakeException expected) {
            // fine
        }
    }

    @Test
    public void aServerThatDoesNotKnowTheClientsKeyFailsTheHandshakeSilently() throws Exception {
        link = new FakePlayer().open();
        // the server initiates with a different static key than the one in server/init: the client's decryption of message 1 fails
        Identity other = Identity.generate(new SecureRandom());
        Future<ServerHandshake.Result> s = link.onHub(() -> {
            String init = link.hubWs.receive().data().length > 0 ? SendspinMessages.serverInit(other.id()) : null;
            link.hubWs.sendText(init);
            link.hubWs.sendText(SendspinMessages.noiseHandshake("AAAA"));
            return null;
        });
        try {
            clientSide(NoiseSuite.CHACHA_POLY, (id, cat) -> null);
            fail();
        } catch (IOException expected) {
            assertNotNull(expected);
        }
        s.get(5, TimeUnit.SECONDS);
    }
}
