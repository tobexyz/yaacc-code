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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The first message decides: {@code client/init} starts the Noise handshake, {@code client/hello} is the older unencrypted wire (off unless allowed). */
public class LegacyHandshakeTest {

    private static final String HELLO = "{\"type\":\"client/hello\",\"payload\":{\"client_id\":\"kodi-1\",\"name\":\"Kodi\",\"version\":1,"
            + "\"supported_roles\":[\"player@v1\"],\"player@v1_support\":{\"supported_formats\":[{\"codec\":\"pcm\",\"channels\":2,"
            + "\"sample_rate\":48000,\"bit_depth\":16}],\"buffer_capacity\":1000000,\"supported_commands\":[\"volume\"]}}}";

    private FakePlayer link;
    private final Identity server = Identity.generate(new SecureRandom());

    @After
    public void tearDown() throws IOException {
        if (link != null) {
            link.close();
        }
    }

    private Future<ServerHandshake.Result> hub(boolean allowLegacy) {
        return link.onHub(() -> ServerHandshake.perform(link.hubWs, server, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom(),
                allowLegacy));
    }

    @Test
    public void aLegacyHelloLargerThanAnInitIsStillAccepted() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(true);
        StringBuilder formats = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            formats.append(i == 0 ? "" : ",").append("{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":").append(44100 + i * 50)
                    .append(",\"bit_depth\":16}");
        }
        String big = "{\"type\":\"client/hello\",\"payload\":{\"client_id\":\"kodi-1\",\"name\":\"Kodi\",\"version\":1,\"supported_roles\":"
                + "[\"player@v1\"],\"player@v1_support\":{\"supported_formats\":[" + formats + "],\"buffer_capacity\":1000000,"
                + "\"supported_commands\":[\"volume\"]}}}";
        assertTrue("larger than the limit of a client/init: " + big.length(), big.length() > 1024);
        link.playerWs.sendText(big);
        assertTrue(hub.get(5, TimeUnit.SECONDS).legacy);
    }

    @Test
    public void aClientHelloAsTheFirstMessageIsALegacySessionWhenItIsAllowed() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(true);
        link.playerWs.sendText(HELLO);
        ServerHandshake.Result result = hub.get(5, TimeUnit.SECONDS);
        assertTrue(result.legacy);
        assertEquals("the id of the legacy hello", "kodi-1", result.clientId);
        assertNull("no cipher suite on the plain wire", result.suite);
        assertEquals(SendspinPsk.Category.SENTINEL, result.verifiedAs);
        assertFalse(result.credentialMismatch);
        assertEquals("Kodi", result.legacyHello.getString("name"));
        assertNotNull(result.channel);
    }

    @Test
    public void thePlainChannelSendsTextAndBinaryAndReadsThem() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(true);
        link.playerWs.sendText(HELLO);
        SecureChannel server = hub.get(5, TimeUnit.SECONDS).channel;
        SecureChannel player = SecureChannel.plain(link.playerWs);

        server.sendJson("{\"type\":\"server/time\",\"payload\":{}}");
        WsConnection.Message text = link.playerWs.receive();
        assertTrue("a JSON message is a WebSocket text message on the plain wire", text.isText());

        server.sendMessage(4, new byte[]{9, 8, 7});
        WsConnection.Message binary = link.playerWs.receive();
        assertFalse(binary.isText());
        assertArrayEquals("the message id and the body, as inside a Noise message", new byte[]{4, 9, 8, 7}, binary.data());

        player.sendJson("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":5}}");
        SecureChannel.Inbound json = server.receive();
        assertTrue(json.isJson());
        assertEquals("client/time", json.envelope().type());
        player.sendMessage(7, new byte[]{1});
        SecureChannel.Inbound bin = server.receive();
        assertEquals(7, bin.id());
        assertArrayEquals(new byte[]{1}, bin.body());
        assertNull("there is no transport to swap keys of", server.transport());
    }

    @Test
    public void withoutTheAllowanceAClientHelloIsAnInitFailure() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(false);
        link.playerWs.sendText(HELLO);
        Envelope answer = Envelope.parse(link.playerWs.receive().data());
        assertEquals("server/error", answer.type());
        assertEquals("malformed", answer.payload().getString("reason"));
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertEquals(ServerHandshake.HandshakeException.class, e.getCause().getClass());
        }
    }

    @Test
    public void aLegacyHelloWithoutAnIdFailsTheHandshake() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(true);
        link.playerWs.sendText("{\"type\":\"client/hello\",\"payload\":{\"name\":\"x\",\"supported_roles\":[\"player@v1\"]}}");
        try {
            hub.get(5, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertEquals(ServerHandshake.HandshakeException.class, e.getCause().getClass());
        }
    }

    @Test
    public void anEncryptedClientStillWorksWhenLegacyIsAllowed() throws Exception {
        link = new FakePlayer().open();
        Future<ServerHandshake.Result> hub = hub(true);
        assertNotNull(link.handshake());
        ServerHandshake.Result result = hub.get(5, TimeUnit.SECONDS);
        assertFalse(result.legacy);
        assertNotNull(result.suite);
        assertNull(result.legacyHello);
    }
}
