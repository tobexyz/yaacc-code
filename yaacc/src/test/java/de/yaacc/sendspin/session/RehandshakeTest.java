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

/** The server's re-handshake inside an open channel (spec, "Re-handshake"), against a fake player. */
public class RehandshakeTest {

    private FakePlayer player;
    private final Identity server = Identity.generate(new SecureRandom());
    private SecureChannel hubSide;
    private SecureChannel playerSide;

    @After
    public void tearDown() throws IOException {
        if (player != null) {
            player.close();
        }
    }

    private static byte[] psk(int seed) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) seed);
        return b;
    }

    private void connect(NoiseSuite suite) throws Exception {
        player = new FakePlayer().open();
        player.suite = suite;
        player.suiteName = suite.wireName();
        Future<ServerHandshake.Result> hub = player.onHub(
                () -> ServerHandshake.perform(player.hubWs, server, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom()));
        playerSide = player.handshake();
        ServerHandshake.Result result = hub.get(5, TimeUnit.SECONDS);
        hubSide = result.channel;
    }

    private Future<Void> hubRehandshake(byte[] psk, SendspinPsk.Category category, long timeoutMillis) {
        return player.onHub(() -> {
            ServerHandshake.rehandshake(hubSide, server, player.identity.id(), player.suite,
                    new ServerHandshake.PskChoice(psk, category), new SecureRandom(), timeoutMillis);
            return null;
        });
    }

    @Test
    public void theKeysChangeAndTheChannelKeepsWorkingBothWaysUnderBothSuites() throws Exception {
        for (NoiseSuite suite : NoiseSuite.values()) {
            if (player != null) {
                player.close();
            }
            connect(suite);
            byte[] before = hubSide.transport().handshakeHash();
            player.knownPsks.put(SendspinPsk.pskId(psk(7)), psk(7));

            Future<Void> hub = hubRehandshake(psk(7), SendspinPsk.Category.PAIRING, 5000);
            player.rehandshake(playerSide, null);
            hub.get(5, TimeUnit.SECONDS);

            assertEquals("pr", player.pskCategorySeen);
            assertEquals(SendspinPsk.pskId(psk(7)), player.pskIdSeen);
            assertFalse("a new handshake hash", Arrays.equals(before, hubSide.transport().handshakeHash()));
            assertArrayEquals("both sides hold the same new hash", hubSide.transport().handshakeHash(), playerSide.transport().handshakeHash());

            hubSide.sendJson(SendspinMessages.serverActivate(java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList()));
            assertEquals("server/activate", playerSide.receive().envelope().type());
            playerSide.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":9}}");
            assertEquals(9L, SendspinMessages.parseClientTime(hubSide.receive().envelope().payload()));
        }
    }

    @Test
    public void aSecondRehandshakeUsesTheHashOfTheFirstAsItsPrologue() throws Exception {
        connect(NoiseSuite.CHACHA_POLY);
        player.knownPsks.put(SendspinPsk.pskId(psk(7)), psk(7));
        player.knownPsks.put(SendspinPsk.pskId(psk(8)), psk(8));
        Future<Void> first = hubRehandshake(psk(7), SendspinPsk.Category.PAIRING, 5000);
        player.rehandshake(playerSide, null);
        first.get(5, TimeUnit.SECONDS);
        Future<Void> second = hubRehandshake(psk(8), SendspinPsk.Category.LONG_TERM, 5000);
        player.rehandshake(playerSide, null);
        second.get(5, TimeUnit.SECONDS);
        assertEquals("lt", player.pskCategorySeen);
        hubSide.sendJson(SendspinMessages.serverHello("YAACC"));
        assertEquals("server/hello", playerSide.receive().envelope().type());
    }

    @Test
    public void messagesUnderTheOldKeysThatCrossMessage1AreKeptAndDeliveredAfterwards() throws Exception {
        connect(NoiseSuite.CHACHA_POLY);
        player.knownPsks.put(SendspinPsk.pskId(psk(7)), psk(7));
        Future<Void> hub = hubRehandshake(psk(7), SendspinPsk.Category.PAIRING, 5000);
        player.rehandshake(playerSide, () -> {
            try {
                playerSide.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":42}}");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        hub.get(5, TimeUnit.SECONDS);
        Envelope kept = hubSide.receive().envelope();
        assertEquals("client/time", kept.type());
        assertEquals(42L, SendspinMessages.parseClientTime(kept.payload()));
    }

    @Test
    public void aPlayerThatDoesNotAnswerFailsTheRehandshake() throws Exception {
        connect(NoiseSuite.CHACHA_POLY);
        Future<Void> hub = hubRehandshake(psk(7), SendspinPsk.Category.PAIRING, 300);
        // the player reads message 1 and says nothing
        assertEquals("noise/handshake", playerSide.receive().envelope().type());
        try {
            hub.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertNotNull(e.getCause());
            assertEquals(ServerHandshake.HandshakeException.class, e.getCause().getClass());
        }
    }

    @Test
    public void aSecondMessageUnderAnotherPskIsNotAcceptedBySentinelFallback() throws Exception {
        connect(NoiseSuite.CHACHA_POLY);
        // the player uses the Sentinel for message 2 although the server referenced psk 7 (what the initial handshake would allow)
        player.knownPsks.put(SendspinPsk.pskId(psk(7)), SendspinPsk.sentinel());
        Future<Void> hub = hubRehandshake(psk(7), SendspinPsk.Category.PAIRING, 5000);
        player.rehandshake(playerSide, null);
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertEquals(ServerHandshake.HandshakeException.class, e.getCause().getClass());
        }
    }

    @Test
    public void aSecondMessageWithAnotherPayloadThanTheEmptyObjectFails() throws Exception {
        connect(NoiseSuite.CHACHA_POLY);
        player.knownPsks.put(SendspinPsk.pskId(psk(7)), psk(7));
        player.message2Payload = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Future<Void> hub = hubRehandshake(psk(7), SendspinPsk.Category.PAIRING, 5000);
        player.rehandshake(playerSide, null);
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertEquals(ServerHandshake.HandshakeException.class, e.getCause().getClass());
        }
    }
}
