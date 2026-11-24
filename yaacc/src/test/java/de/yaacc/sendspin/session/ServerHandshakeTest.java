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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.sendspin.secure.Base64Url;
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

/** The server's opening exchange against a fake player: the happy paths of both suites and every way it can fail. */
public class ServerHandshakeTest {

    private FakePlayer player;
    private final Identity server = Identity.generate(new SecureRandom());

    @After
    public void tearDown() throws IOException {
        if (player != null) {
            player.close();
        }
    }

    private Future<ServerHandshake.Result> hub(ServerHandshake.PskResolver resolver) {
        return player.onHub(() -> ServerHandshake.perform(player.hubWs, server, resolver, new SecureRandom()));
    }

    private static final ServerHandshake.PskResolver SENTINEL = id -> ServerHandshake.PskChoice.sentinel();

    @Test
    public void bothSuitesComeUpAndTheChannelWorksBothWays() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            if (player != null) {
                player.close();
            }
            player = new FakePlayer().open();
            player.suite = suite;
            player.suiteName = suite.wireName();
            Future<ServerHandshake.Result> hub = hub(SENTINEL);

            SecureChannel playerSide = player.handshake();
            ServerHandshake.Result result = hub.get(5, TimeUnit.SECONDS);

            assertEquals(suite, result.suite);
            assertEquals(player.identity.id(), result.clientId);
            assertEquals(SendspinPsk.Category.SENTINEL, result.verifiedAs);
            assertFalse(result.credentialMismatch);
            assertEquals(server.id(), player.serverIdSeen);
            assertEquals(SendspinPsk.sentinelId(), player.pskIdSeen);
            assertEquals("sn", player.pskCategorySeen);

            result.channel.sendJson(SendspinMessages.serverHello("YAACC"));
            Envelope hello = playerSide.receive().envelope();
            assertEquals("server/hello", hello.type());
            assertEquals("YAACC", hello.payload().getString("name"));
            playerSide.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":5}}");
            Envelope time = result.channel.receive().envelope();
            assertEquals(5L, SendspinMessages.parseClientTime(time.payload()));
        }
    }

    @Test
    public void aPairedClientIsReferencedByItsLongTermPsk() throws Exception {
        player = new FakePlayer().open();
        byte[] longTerm = new byte[32];
        Arrays.fill(longTerm, (byte) 9);
        player.knownPsks.put(SendspinPsk.pskId(longTerm), longTerm);
        Future<ServerHandshake.Result> hub = hub(id -> new ServerHandshake.PskChoice(longTerm, SendspinPsk.Category.LONG_TERM));

        assertNotNull(player.handshake());
        ServerHandshake.Result result = hub.get(5, TimeUnit.SECONDS);

        assertEquals(SendspinPsk.Category.LONG_TERM, result.verifiedAs);
        assertFalse(result.credentialMismatch);
        assertEquals("lt", player.pskCategorySeen);
        assertEquals(SendspinPsk.pskId(longTerm), player.pskIdSeen);
    }

    @Test
    public void aClientThatLostItsRecordAnswersWithTheSentinelAndTheServerSeesAMismatch() throws Exception {
        player = new FakePlayer().open();
        byte[] longTerm = new byte[32];
        Arrays.fill(longTerm, (byte) 9); // the player does not know it
        Future<ServerHandshake.Result> hub = hub(id -> new ServerHandshake.PskChoice(longTerm, SendspinPsk.Category.LONG_TERM));

        assertNotNull(player.handshake());
        ServerHandshake.Result result = hub.get(5, TimeUnit.SECONDS);

        assertTrue("the signal that the credential could not be used", result.credentialMismatch);
        assertEquals(SendspinPsk.Category.SENTINEL, result.verifiedAs);
    }

    private void expectInitFailure(String expectedReason) throws Exception {
        Future<ServerHandshake.Result> hub = hub(SENTINEL);
        assertNull(player.handshake());
        assertEquals(expectedReason, player.serverError);
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail("expected a failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof ServerHandshake.HandshakeException);
        }
    }

    @Test
    public void anUnsupportedVersionIsAnsweredWithServerError() throws Exception {
        player = new FakePlayer().open();
        player.version = 2;
        expectInitFailure("unsupported_version");
    }

    @Test
    public void anUnknownSuiteIsAnsweredWithServerError() throws Exception {
        player = new FakePlayer().open();
        player.suiteName = "25519_Nope_SHA256";
        expectInitFailure("unsupported_suite");
    }

    @Test
    public void aVersionOtherThanOneWinsOverAnUnknownSuite() throws Exception {
        player = new FakePlayer().open();
        player.version = 7;
        player.suiteName = "25519_Nope_SHA256";
        expectInitFailure("unsupported_version");
    }

    @Test
    public void malformedInitsAreAnsweredWithServerError() throws Exception {
        String[] bad = {
                "not json",
                "{}",
                "{\"type\":\"client/init\"}",
                "{\"type\":\"client/init\",\"payload\":{\"client_id\":\"x\",\"version\":1,\"suite\":\"25519_ChaChaPoly_SHA256\"}}",
                "{\"type\":\"client/init\",\"payload\":{\"version\":1,\"suite\":\"25519_ChaChaPoly_SHA256\"}}",
                "{\"type\":\"client/init\",\"payload\":{\"client_id\":\"" + Base64Url.encode(new byte[32]) + "\",\"version\":\"1\",\"suite\":\"25519_ChaChaPoly_SHA256\"}}",
                "{\"type\":\"client/hello\",\"payload\":{}}",
        };
        for (String init : bad) {
            if (player != null) {
                player.close();
            }
            player = new FakePlayer().open();
            player.rawClientInit = init;
            expectInitFailure("malformed");
        }
    }

    @Test
    public void aClientThatDoesNotKnowTheServerKeyFailsSilently() throws Exception {
        player = new FakePlayer().open();
        player.breakStaticKey = true;
        Future<ServerHandshake.Result> hub = hub(SENTINEL);
        try {
            player.handshake();
            fail("the fake player cannot read message 1 with the wrong key");
        } catch (de.yaacc.sendspin.secure.NoiseException expected) {
            // the player's own side fails too
        }
        player.playerWs.close(1000, "");
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail("expected a failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void aSecondMessageWhosePayloadIsNotTheEmptyObjectIsRefused() throws Exception {
        player = new FakePlayer().open();
        player.message2Payload = "{\"x\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Future<ServerHandshake.Result> hub = hub(SENTINEL);
        player.handshake();
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail("expected a failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof ServerHandshake.HandshakeException);
        }
    }

    @Test
    public void aClientIdThatIsNotItsKeyFailsTheSecondMessageSilently() throws Exception {
        player = new FakePlayer().open();
        player.clientIdOverride = Identity.generate(new SecureRandom()).id(); // names another static key than the one used
        Future<ServerHandshake.Result> hub = hub(SENTINEL);
        try {
            player.handshake();
        } catch (de.yaacc.sendspin.secure.NoiseException e) {
            // the player cannot even read message 1: also a failure of this side
        }
        player.playerWs.close(1000, "");
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail("expected a failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void aBinaryFirstMessageIsRefused() throws Exception {
        player = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(SENTINEL);
        player.playerWs.sendBinary(new byte[]{1, 2, 3});
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail("expected a failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof ServerHandshake.HandshakeException);
        }
    }

    @Test
    public void aPeerThatGoesAwayBeforeTheHandshakeEndsFailsIt() throws Exception {
        player = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(SENTINEL);
        player.close();
        try {
            hub.get(10, TimeUnit.SECONDS);
            fail("expected a failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }
}
