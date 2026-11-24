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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;

/**
 * Against the reference player of {@code tools/sendspin-rig} (opt-in): {@code -Dsendspin.rig=host:port} of a player started with
 * unpaired access. Skipped without the property. The first connection to a player the server has not met is unpaired: the player
 * admits it only if its unpaired access is on, and the server must be approved by its operator, which here is this test.
 */
public class SendspinRigTest {

    private String host;
    private int port;

    @Before
    public void rig() {
        String rig = System.getProperty("sendspin.rig");
        Assume.assumeTrue("no -Dsendspin.rig=host:port", rig != null && !rig.isEmpty());
        int colon = rig.lastIndexOf(':');
        host = rig.substring(0, colon);
        port = Integer.parseInt(rig.substring(colon + 1));
    }

    private SecureChannel connect(Identity server, SendspinMessagesHolder out) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(15000);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        SecureRandom random = new SecureRandom();
        WsHandshake.connect(in, socket.getOutputStream(), host, port, "/sendspin", random);
        WsConnection ws = new WsConnection(in, socket.getOutputStream(), true, 1 << 20, random);
        ServerHandshake.Result result = ServerHandshake.perform(ws, server, clientId -> ServerHandshake.PskChoice.sentinel(), random);
        out.result = result;
        return result.channel;
    }

    private static final class SendspinMessagesHolder {
        ServerHandshake.Result result;
    }

    @Test
    public void theHandshakeHelloActivateAndStateWorkWithTheReferencePlayer() throws Exception {
        SendspinMessagesHolder holder = new SendspinMessagesHolder();
        SecureChannel channel = connect(Identity.generate(new SecureRandom()), holder);
        assertNotNull(holder.result.clientId);
        assertEquals(43, holder.result.clientId.length());
        System.out.println("rig: handshake done, suite " + holder.result.suite + ", client " + holder.result.clientId);

        channel.sendJson(SendspinMessages.serverHello("YAACC rig test"));
        Envelope hello = channel.receive().envelope();
        assertEquals("client/hello", hello.type());
        SendspinMessages.ClientHello clientHello = SendspinMessages.parseClientHello(hello.payload());
        System.out.println("rig: client/hello name=" + clientHello.name + " roles=" + clientHello.supportedRoles + " unpaired="
                + clientHello.unpairedAccess + " pair=" + clientHello.pairMethods + " formats=" + clientHello.playerFormats.size()
                + " buffer=" + clientHello.playerBufferCapacity);
        assertTrue(clientHello.supportsPlayer());
        Assume.assumeTrue("the reference player does not admit unpaired servers", clientHello.unpairedAccess);

        channel.sendJson(SendspinMessages.serverActivate(Collections.singletonList("playback"), Collections.singletonList("player@v1")));
        channel.sendJson(SendspinMessages.groupUpdate(false, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "rig group"));

        // answer the time bursts until the first client/state arrives
        SendspinMessages.ClientState state = null;
        long deadline = System.nanoTime() + 20_000_000_000L;
        while ((state == null || !state.available) && System.nanoTime() < deadline) {
            SecureChannel.Inbound inbound = channel.receive();
            if (!inbound.isJson()) {
                continue;
            }
            Envelope envelope = inbound.envelope();
            System.out.println("rig: <- " + envelope.type());
            if ("client/time".equals(envelope.type())) {
                long received = System.nanoTime() / 1000;
                channel.sendJson(SendspinMessages.serverTime(SendspinMessages.parseClientTime(envelope.payload()), received,
                        System.nanoTime() / 1000));
            } else if ("client/state".equals(envelope.type())) {
                SendspinMessages.ClientState seen = SendspinMessages.parseClientState(envelope.payload());
                System.out.println("rig: client/state available=" + seen.available);
                state = seen;
            } else if ("client/goodbye".equals(envelope.type())) {
                throw new AssertionError("goodbye: " + SendspinMessages.parseGoodbyeReason(envelope.payload()));
            }
        }
        assertNotNull("no client/state within 20 s", state);
        System.out.println("rig: client/state available=" + state.available + (state.player == null ? "" : " volume=" + state.player.volume
                + " commands=" + state.player.supportedCommands + " lead=" + state.player.requiredLeadTimeMs + " minBuffer=" + state.player.minBufferMs));
        assertTrue(state.available);
        assertNotNull(state.player);
        channel.close(WsConnection.CLOSE_NORMAL);
    }

    private static long serverClockMicros() {
        return System.nanoTime() / 1000L;
    }

    /** A tone of 440 Hz, 16 bit, stereo little endian: the samples of the frames [from, from+count) at the rate. */
    private static byte[] tone(int rate, long from, int count) {
        byte[] out = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * (from + i) / rate) * 6000);
            out[4 * i] = (byte) sample;
            out[4 * i + 1] = (byte) (sample >> 8);
            out[4 * i + 2] = (byte) sample;
            out[4 * i + 3] = (byte) (sample >> 8);
        }
        return out;
    }

    @Test
    public void aToneStreamsToTheReferencePlayerInTimeWithoutTheConnectionBreaking() throws Exception {
        SendspinMessagesHolder holder = new SendspinMessagesHolder();
        final SecureChannel channel = connect(Identity.generate(new SecureRandom()), holder);
        channel.sendJson(SendspinMessages.serverHello("YAACC rig test"));
        SendspinMessages.ClientHello hello = SendspinMessages.parseClientHello(channel.receive().envelope().payload());
        Assume.assumeTrue(hello.unpairedAccess && hello.supportsPlayer());
        SendspinMessages.Format chosen = null;
        for (SendspinMessages.Format format : hello.playerFormats) {
            System.out.println("rig: format " + format.codec + " " + format.sampleRate + " " + format.channels + " " + format.bitDepth);
            if (chosen == null && "pcm".equals(format.codec) && format.channels == 2 && format.bitDepth == 16) {
                chosen = format; // the first PCM stereo 16 bit entry in the player's order
            }
        }
        assertNotNull("the player lists PCM stereo 16 bit", chosen);
        final int rate = chosen.sampleRate;
        channel.sendJson(SendspinMessages.serverActivate(Collections.singletonList("playback"), Collections.singletonList("player@v1")));
        channel.sendJson(SendspinMessages.groupUpdate(true, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "rig group"));

        final java.util.concurrent.atomic.AtomicReference<SendspinMessages.ClientState> latest = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger times = new java.util.concurrent.atomic.AtomicInteger();
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    SecureChannel.Inbound inbound = channel.receive();
                    if (!inbound.isJson()) {
                        continue;
                    }
                    Envelope envelope = inbound.envelope();
                    if ("client/time".equals(envelope.type())) {
                        long received = serverClockMicros();
                        times.incrementAndGet();
                        channel.sendJson(SendspinMessages.serverTime(SendspinMessages.parseClientTime(envelope.payload()), received,
                                serverClockMicros()));
                    } else if ("client/state".equals(envelope.type())) {
                        latest.set(SendspinMessages.parseClientState(envelope.payload()));
                    } else if ("client/goodbye".equals(envelope.type())) {
                        failure.set("goodbye " + SendspinMessages.parseGoodbyeReason(envelope.payload()));
                        return;
                    }
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
        reader.setDaemon(true);
        reader.start();

        long deadline = System.nanoTime() + 20_000_000_000L;
        while ((latest.get() == null || !latest.get().available) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        SendspinMessages.ClientState ready = latest.get();
        assertNotNull("no client/state", ready);
        assertTrue("available", ready.available);

        // the start: the first timestamp is the lead after now, the lead being what the player asked for plus a margin
        long leadMicros = Math.max(ready.player.minBufferMs, ready.player.requiredLeadTimeMs) * 1000L + 250_000L;
        channel.sendJson(SendspinMessages.streamStartPcm(serverClockMicros(), rate, 2, 16));
        long start = serverClockMicros() + leadMicros;
        final int frames = rate / 50; // 20 ms
        final int chunks = 50 * Integer.getInteger("sendspin.rig.seconds", 5); // 20 ms each
        long sendAhead = leadMicros;
        for (int i = 0; i < chunks; i++) {
            long timestamp = start + i * 20_000L;
            // keep about the lead of audio in the player's buffer: chunk i goes out when it is that close
            long sendAt = timestamp - sendAhead;
            long wait = sendAt - serverClockMicros();
            if (wait > 0) {
                Thread.sleep(wait / 1000, (int) (wait % 1000) * 1000);
            }
            byte[] audio = tone(rate, (long) i * frames, frames);
            channel.sendMessage(SendspinMessages.AUDIO_CHUNK_ID,
                    SendspinMessages.audioChunk(timestamp, timestamp - serverClockMicros(), audio, 0, audio.length));
            assertTrue("connection ended: " + failure.get(), failure.get() == null);
        }
        Thread.sleep(leadMicros / 1000 + 200);
        channel.sendJson(SendspinMessages.streamEnd());
        Thread.sleep(300);
        assertTrue("connection ended: " + failure.get(), failure.get() == null);
        System.out.println("rig: streamed " + chunks + " chunks, answered " + times.get() + " time messages, player state " + (latest.get() == null ? "?" : "available=" + latest.get().available));
        channel.close(WsConnection.CLOSE_NORMAL);
    }
}
