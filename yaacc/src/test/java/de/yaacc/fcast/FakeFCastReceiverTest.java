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
package de.yaacc.fcast;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import de.yaacc.fcast.FakeFCastReceiver.Op;
import de.yaacc.fcast.FakeFCastReceiver.Packet;

/**
 * Task 1.3: the fixture behaves as the reference receivers do (handshake, {@code Initial}, old versions, pings, scripted
 * updates, a second sender, oversize packets, a stalled reader, silence), and it never binds a fixed port.
 */
public class FakeFCastReceiverTest {
    private static final int WAIT = 3_000;

    // ---- the wire format, against bytes written by hand

    @Test
    public void aPacketWithoutBodyIsFiveBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FakeFCastReceiver.Wire.write(out, Op.PAUSE, null);
        assertArrayEquals(new byte[]{1, 0, 0, 0, 2}, out.toByteArray());
    }

    @Test
    public void theSizeCountsTheOpcodeAndIsLittleEndian() throws Exception {
        byte[] body = "{\"time\":5.5}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FakeFCastReceiver.Wire.write(out, Op.SEEK, body);
        byte[] bytes = out.toByteArray();
        assertEquals(body.length + 1, bytes[0] & 0xff);
        assertEquals(0, bytes[1]);
        assertEquals(Op.SEEK, bytes[4]);
        Packet back = FakeFCastReceiver.Wire.read(new ByteArrayInputStream(bytes));
        assertEquals(Op.SEEK, back.opcode);
        assertEquals("{\"time\":5.5}", back.body);
    }

    @Test
    public void aSizeAbove255UsesTheSecondByte() throws Exception {
        byte[] body = new byte[300];
        java.util.Arrays.fill(body, (byte) 'a');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FakeFCastReceiver.Wire.write(out, Op.PLAY, body);
        byte[] bytes = out.toByteArray();
        assertEquals(301 & 0xff, bytes[0] & 0xff);
        assertEquals(1, bytes[1]);
    }

    @Test
    public void theReaderRefusesOversizeEmptyAndTruncatedPackets() throws Exception {
        try {
            FakeFCastReceiver.Wire.read(new ByteArrayInputStream(new byte[]{(byte) 0x41, (byte) 0x7d, 0, 0, 6})); // 32065
            fail("oversize accepted");
        } catch (FakeFCastReceiver.OversizeException expected) {
            assertEquals(32065, expected.declaredSize);
        }
        try {
            FakeFCastReceiver.Wire.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 0}));
            fail("size 0 accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("opcode"));
        }
        try {
            FakeFCastReceiver.Wire.read(new ByteArrayInputStream(new byte[]{9, 0, 0, 0, 1, '{'}));
            fail("truncated body accepted");
        } catch (EOFException expected) {
            // as wanted
        }
        assertNull(FakeFCastReceiver.Wire.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void theLimitItselfIsAccepted() throws Exception {
        byte[] body = new byte[FakeFCastReceiver.MAX_PACKET - 1];
        java.util.Arrays.fill(body, (byte) 'x');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FakeFCastReceiver.Wire.write(out, Op.PLAY, body);
        Packet packet = FakeFCastReceiver.Wire.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(FakeFCastReceiver.MAX_PACKET - 1, packet.body.length());
    }

    // ---- the receiver's behaviour

    @Test
    public void thePortIsNotFixed() throws Exception {
        try (FakeFCastReceiver a = FakeFCastReceiver.start(); FakeFCastReceiver b = FakeFCastReceiver.start()) {
            assertTrue(a.port() > 0);
            assertNotEquals(a.port(), b.port());
            assertNotEquals(46899, a.port());
        }
    }

    @Test
    public void aNormalReceiverGreetsThenAnswersTheVersionWithInitialAndTheVolume() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            Packet greeting = sender.read(WAIT);
            assertEquals(Op.VERSION, greeting.opcode);
            assertEquals(3, greeting.json().getInt("version"));

            sender.sendVersion(3);
            Packet initial = sender.read(WAIT);
            assertEquals(Op.INITIAL, initial.opcode);
            assertEquals("Fake Receiver", initial.json().getString("displayName"));
            assertEquals("FakeFCast", initial.json().getString("appName"));
            Packet volume = sender.read(WAIT);
            assertEquals(Op.VOLUME_UPDATE, volume.opcode);
            assertEquals(1.0, volume.json().getDouble("volume"), 0.0001);

            sender.sendInitial("YAACC test");
            assertTrue(receiver.awaitPackets(Op.INITIAL, 1, WAIT));
            assertEquals("YAACC test", receiver.received(Op.INITIAL).get(0).json().getString("displayName"));
        }
    }

    @Test
    public void aDesktopStyleReceiverGreetsWith4AndSettlesOnTheSendersLowerVersion() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().greeting(4));
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            Packet greeting = sender.read(WAIT);
            assertEquals(4, greeting.json().getInt("version"));
            sender.sendVersion(3);
            assertEquals(Op.INITIAL, sender.read(WAIT).opcode);
            assertEquals(3, receiver.session(0).negotiatedVersion());
        }
    }

    @Test
    public void aReceiverWithoutGreetingWaitsForTheSender() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().greeting(0));
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            try {
                sender.read(150);
                fail("a packet came before the sender said anything");
            } catch (SocketTimeoutException expected) {
                // as wanted
            }
            sender.sendVersion(3);
            assertEquals(Op.INITIAL, sender.read(WAIT).opcode);
        }
    }

    @Test
    public void anOldReceiverSendsNoInitialAndSettlesOnItsOwnVersion() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().greeting(2).speaks(2));
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            assertEquals(2, sender.read(WAIT).json().getInt("version"));
            sender.sendVersion(3);
            Packet next = sender.read(WAIT);
            assertEquals("a v2 receiver sends the volume, no Initial", Op.VOLUME_UPDATE, next.opcode);
            try {
                sender.readUntil(Op.INITIAL, 200);
                fail("Initial from a version 2 receiver");
            } catch (SocketTimeoutException expected) {
                // as wanted
            }
            assertEquals(2, receiver.session(0).negotiatedVersion());
        }
    }

    @Test
    public void aPingIsAnsweredWithAPongUnlessTheReceiverIgnoresPings() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.send(Op.PING, null);
            assertNotNull(sender.readUntil(Op.PONG, WAIT));
        }
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().ignorePings());
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.send(Op.PING, null);
            try {
                sender.readUntil(Op.PONG, 250);
                fail("a Pong although pings are ignored");
            } catch (SocketTimeoutException expected) {
                // as wanted
            }
            assertTrue(receiver.awaitPackets(Op.PING, 1, WAIT));
        }
    }

    @Test
    public void aReceiverThatPingsIsAnsweredAndCounts() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().pingEvery(40));
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            assertNotNull(sender.readUntil(Op.PING, WAIT));
            sender.send(Op.PONG, null);
            assertTrue(receiver.awaitPackets(Op.PONG, 1, WAIT));
            assertTrue(receiver.session(0).pongCount() >= 1);
        }
    }

    @Test
    public void scriptedUpdatesArriveWithTheirFields() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.readUntil(Op.VOLUME_UPDATE, WAIT);
            FakeFCastReceiver.Session session = receiver.session(0);

            session.sendPlaybackUpdate(1, 12.5, 200.0, 1.0);
            JSONObject playing = sender.readUntil(Op.PLAYBACK_UPDATE, WAIT).json();
            assertEquals(1, playing.getInt("state"));
            assertEquals(12.5, playing.getDouble("time"), 0.0001);
            assertEquals(200.0, playing.getDouble("duration"), 0.0001);
            assertTrue(playing.has("generationTime"));

            session.sendPlaybackUpdate(0, -1, -1, -1);
            JSONObject idle = sender.readUntil(Op.PLAYBACK_UPDATE, WAIT).json();
            assertEquals(0, idle.getInt("state"));
            assertTrue("optional fields are left out", !idle.has("time") && !idle.has("duration") && !idle.has("speed"));

            session.sendVolumeUpdate(0.25);
            assertEquals(0.25, sender.readUntil(Op.VOLUME_UPDATE, WAIT).json().getDouble("volume"), 0.0001);

            session.sendPlayUpdate("audio/mpeg", "http://192.168.1.2:8080/a b.mp3");
            JSONObject play = sender.readUntil(Op.PLAY_UPDATE, WAIT).json().getJSONObject("playData");
            assertEquals("audio/mpeg", play.getString("container"));
            assertEquals("http://192.168.1.2:8080/a b.mp3", play.getString("url"));

            session.sendPlaybackError("no \"codec\"");
            assertEquals("no \"codec\"", sender.readUntil(Op.PLAYBACK_ERROR, WAIT).json().getString("message"));
        }
    }

    @Test
    public void theReceiverRecordsEverythingFromEveryConnectionInOrder() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start();
             FakeFCastSender first = new FakeFCastSender(receiver.port());
             FakeFCastSender second = new FakeFCastSender(receiver.port())) {
            first.sendVersion(3);
            first.send(Op.PLAY, "{\"container\":\"video/mp4\",\"url\":\"http://x/y.mp4\"}");
            second.sendVersion(3);
            second.send(Op.SET_VOLUME, "{\"volume\":0.5}");
            assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
            assertTrue(receiver.awaitPackets(Op.SET_VOLUME, 1, WAIT));
            assertEquals(2, receiver.acceptedCount());
            assertEquals(2, receiver.received(Op.VERSION).size());
            assertEquals("http://x/y.mp4", receiver.received(Op.PLAY).get(0).json().getString("url"));
            assertEquals(0.5, receiver.received(Op.SET_VOLUME).get(0).json().getDouble("volume"), 0.0001);
        }
    }

    @Test
    public void aBroadcastReachesEverySender() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start();
             FakeFCastSender first = new FakeFCastSender(receiver.port());
             FakeFCastSender second = new FakeFCastSender(receiver.port())) {
            first.sendVersion(3);
            second.sendVersion(3);
            first.readUntil(Op.VOLUME_UPDATE, WAIT);
            second.readUntil(Op.VOLUME_UPDATE, WAIT);
            receiver.broadcast(Op.VOLUME_UPDATE, "{\"generationTime\":1,\"volume\":0.75}");
            assertEquals(0.75, first.readUntil(Op.VOLUME_UPDATE, WAIT).json().getDouble("volume"), 0.0001);
            assertEquals(0.75, second.readUntil(Op.VOLUME_UPDATE, WAIT).json().getDouble("volume"), 0.0001);
        }
    }

    @Test
    public void anOversizePacketFromTheReceiverIsAnnouncedButNotDelivered() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.readUntil(Op.VOLUME_UPDATE, WAIT);
            receiver.session(0).sendOversize(40_000);
            try {
                sender.read(WAIT);
                fail("an oversize packet was read");
            } catch (FakeFCastReceiver.OversizeException expected) {
                assertEquals(40_000, expected.declaredSize);
            }
        }
    }

    @Test
    public void aPacketWithoutOpcodeIsReportedAsAnError() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.readUntil(Op.VOLUME_UPDATE, WAIT);
            receiver.session(0).sendEmptyPacket();
            try {
                sender.read(WAIT);
                fail("a packet of size 0 was accepted");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("opcode"));
            }
        }
    }

    @Test
    public void aStalledReceiverReadsNothingUntilItIsReleased() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().stalled());
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.readUntil(Op.VOLUME_UPDATE, WAIT);
            // the handshake is done: from now on the receiver does not read
            sender.send(Op.PING, null);
            sender.send(Op.PING, null);
            try {
                sender.readUntil(Op.PONG, 250);
                fail("a stalled receiver answered");
            } catch (SocketTimeoutException expected) {
                // as wanted
            }
            assertEquals("only the Version was read", 0, receiver.received(Op.PING).size());

            receiver.release();
            assertTrue(receiver.awaitPackets(Op.PING, 2, WAIT));
            assertNotNull(sender.readUntil(Op.PONG, WAIT));
        }
    }

    @Test
    public void aSilentReceiverRecordsButSaysNothing() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options().silent());
             FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.send(Op.PING, null);
            assertTrue(receiver.awaitPackets(Op.PING, 1, WAIT));
            try {
                sender.read(250);
                fail("a silent receiver sent something");
            } catch (SocketTimeoutException expected) {
                // as wanted
            }
        }
    }

    @Test
    public void closingFromTheReceiverSideEndsTheSenderRead() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.sendVersion(3);
            sender.readUntil(Op.VOLUME_UPDATE, WAIT);
            receiver.closeConnection(0);
            assertTrue(sender.isClosedByPeer(WAIT));
        }
    }

    @Test
    public void aScriptGetsTheSessionOfEveryConnection() throws Exception {
        try (FakeFCastReceiver receiver = FakeFCastReceiver.start(session -> {
            Packet first = session.read();
            session.sendPlaybackError("script saw opcode " + first.opcode);
        }); FakeFCastSender sender = new FakeFCastSender(receiver.port())) {
            sender.send(Op.STOP, null);
            assertEquals("script saw opcode " + Op.STOP, sender.readUntil(Op.PLAYBACK_ERROR, WAIT).json().getString("message"));
        }
    }
}
