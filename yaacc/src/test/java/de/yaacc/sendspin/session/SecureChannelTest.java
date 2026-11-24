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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Message ids, fragmentation and everything the channel refuses, with a fake player on the other end of a real connection. */
public class SecureChannelTest {

    private FakePlayer player;
    private SecureChannel hub;
    private SecureChannel peer;

    @Before
    public void connect() throws Exception {
        player = new FakePlayer().open();
        Identity server = Identity.generate(new SecureRandom());
        Future<ServerHandshake.Result> result = player.onHub(() ->
                ServerHandshake.perform(player.hubWs, server, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom()));
        peer = player.handshake();
        hub = result.get(5, TimeUnit.SECONDS).channel;
    }

    @After
    public void tearDown() throws IOException {
        player.close();
    }

    @Test
    public void everyMessageKeepsItsIdAndBody() throws Exception {
        byte[] body = new byte[]{9, 8, 7};
        hub.sendMessage(4, body);
        hub.sendMessage(255, new byte[0]);
        SecureChannel.Inbound first = peer.receive();
        assertEquals(4, first.id());
        assertArrayEquals(body, first.body());
        SecureChannel.Inbound second = peer.receive();
        assertEquals(255, second.id());
        assertEquals(0, second.body().length);
    }

    @Test
    public void theLargestBodyFitsAndOneByteMoreIsRefusedBeforeSending() throws Exception {
        byte[] biggest = new byte[SecureChannel.MAX_BODY];
        Arrays.fill(biggest, (byte) 3);
        hub.sendMessage(4, biggest);
        assertArrayEquals(biggest, peer.receive().body());
        try {
            hub.sendMessage(4, new byte[SecureChannel.MAX_BODY + 1]);
            fail("too big");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void fragmentsAreReassembledInOrder() throws Exception {
        // first fragment: flags 2, original type 4, data "ab"; middle: flags 0, "cd"; last: flags 1, "ef"
        peer.sendMessage(1, new byte[]{2, 4, 'a', 'b'});
        peer.sendMessage(1, new byte[]{0, 'c', 'd'});
        peer.sendMessage(1, new byte[]{1, 'e', 'f'});
        SecureChannel.Inbound whole = hub.receive();
        assertEquals(4, whole.id());
        assertArrayEquals("abcdef".getBytes(), whole.body());
    }

    @Test
    public void aFragmentedJsonMessageIsParsedAsOne() throws Exception {
        byte[] json = "{\"type\":\"client/goodbye\",\"payload\":{\"reason\":\"shutdown\"}}".getBytes();
        peer.sendMessage(1, concat(new byte[]{3, 0}, json)); // first and last at once, original type 0
        Envelope envelope = hub.receive().envelope();
        assertEquals("client/goodbye", envelope.type());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void expectProtocolError(byte[]... fragmentBodies) throws Exception {
        for (byte[] body : fragmentBodies) {
            peer.sendMessage(1, body);
        }
        try {
            hub.receive();
            fail("expected a protocol error");
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void malformedFragmentSequencesAreProtocolErrors() throws Exception {
        expectProtocolError(new byte[]{0, 'x'}); // a middle fragment with none in flight
        tearDown();
        connect();
        expectProtocolError(new byte[]{1, 'x'}); // a last fragment with none in flight
        tearDown();
        connect();
        expectProtocolError(new byte[]{4, 'x'}); // a reserved flag bit
        tearDown();
        connect();
        expectProtocolError(new byte[]{2, 1, 'x'}, new byte[]{2, 1, 'y'}); // a first fragment inside a fragmented message
        tearDown();
        connect();
        expectProtocolError(new byte[]{3, 1}); // an orig_type of 1: fragment of a fragment
        tearDown();
        connect();
        expectProtocolError(new byte[]{2}); // a first fragment without its type
        tearDown();
        connect();
        peer.sendMessage(1, new byte[]{2, 4, 'a'});
        peer.sendMessage(4, new byte[]{1}); // a message that is no fragment inside a fragmented one
        try {
            hub.receive();
            fail("expected a protocol error");
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void anEmptyPlaintextIsAProtocolError() throws Exception {
        player.playerWs.sendBinary(peerEncrypt(new byte[0]));
        try {
            hub.receive();
            fail("expected a protocol error");
        } catch (ProtocolException expected) {
            // fine
        }
    }

    private byte[] peerEncrypt(byte[] plain) throws NoiseException {
        return peer.transport().encrypt(plain);
    }

    @Test
    public void aTextMessageAfterTheHandshakeIsAProtocolError() throws Exception {
        player.playerWs.sendText("{\"type\":\"client/hello\",\"payload\":{}}");
        try {
            hub.receive();
            fail("expected a protocol error");
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void aTamperedOrReplayedMessageDoesNotVerify() throws Exception {
        byte[] wire = peerEncrypt(new byte[]{0, '{', '}'});
        wire[wire.length - 1] ^= 1;
        player.playerWs.sendBinary(wire);
        try {
            hub.receive();
            fail("expected a failure");
        } catch (NoiseException expected) {
            // fine
        }
    }

    @Test
    public void aJsonMessageIsNotJsonWhenItIsNot() throws Exception {
        peer.sendMessage(0, "[1,2]".getBytes());
        SecureChannel.Inbound inbound = hub.receive();
        try {
            inbound.envelope();
            fail("expected an error");
        } catch (ProtocolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }
}
