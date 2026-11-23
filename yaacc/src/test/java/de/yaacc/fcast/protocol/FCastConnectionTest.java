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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.yaacc.fcast.FakeFCastReceiver;
import de.yaacc.fcast.FakeFCastReceiver.Op;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * Tasks 2.3 and 2.4: the connection against {@link FakeFCastReceiver} (a loopback server on a free port): handshake order,
 * version negotiation, older receivers, pings, silence, flooding and stalled receivers, hostile packets, no thread left.
 */
public class FCastConnectionTest {
    private static final long WAIT = 4_000;

    private final List<AutoCloseable> toClose = Collections.synchronizedList(new ArrayList<AutoCloseable>());

    @After
    public void closeEverything() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    private static final class Recorder implements FCastConnection.Listener {
        final List<FCastMessages.Incoming> messages = Collections.synchronizedList(new ArrayList<FCastMessages.Incoming>());
        final CountDownLatch closed = new CountDownLatch(1);
        volatile FCastConnection.CloseReason reason;
        volatile String detail;
        volatile int closeCalls;

        @Override
        public void onMessage(FCastMessages.Incoming message) {
            messages.add(message);
        }

        @Override
        public void onClosed(FCastConnection.CloseReason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
            closeCalls++;
            closed.countDown();
        }

        boolean awaitClosed(long millis) throws InterruptedException {
            return closed.await(millis, TimeUnit.MILLISECONDS);
        }

        <T extends FCastMessages.Incoming> T await(Class<T> type, long millis) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < end) {
                synchronized (messages) {
                    for (FCastMessages.Incoming message : messages) {
                        if (type.isInstance(message)) {
                            return type.cast(message);
                        }
                    }
                }
                Thread.sleep(5);
            }
            return null;
        }

        int count(Class<? extends FCastMessages.Incoming> type) {
            int n = 0;
            synchronized (messages) {
                for (FCastMessages.Incoming message : messages) {
                    if (type.isInstance(message)) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    private static FCastConnection.Config fast() {
        FCastConnection.Config config = new FCastConnection.Config();
        config.connectTimeoutMillis = 2_000;
        config.pingAfterMillis = 100;
        config.deadAfterMillis = 400;
        config.writeTimeoutMillis = 60_000;
        config.initialWaitMillis = 150;
        config.tickMillis = 20;
        return config;
    }

    private FCastConnection connect(FakeFCastReceiver receiver, Recorder recorder, FCastConnection.Config config) throws IOException {
        FCastConnection connection = new FCastConnection(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), receiver.port()),
                recorder, config, MonotonicClock.SYSTEM, "Test Phone", "YAACC", "9.9");
        toClose.add(connection);
        connection.connect();
        return connection;
    }

    private FakeFCastReceiver receiver(FakeFCastReceiver.Options options) throws IOException {
        FakeFCastReceiver receiver = FakeFCastReceiver.start(options);
        toClose.add(receiver);
        return receiver;
    }

    // ---- handshake

    @Test
    public void theSenderSaysVersion3FirstAndThenItsInitial() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());

        assertTrue(receiver.awaitPackets(Op.INITIAL, 1, WAIT));
        List<FakeFCastReceiver.Packet> packets = receiver.received();
        assertEquals(Op.VERSION, packets.get(0).opcode);
        assertEquals(3, packets.get(0).json().getInt("version"));
        assertEquals(Op.INITIAL, packets.get(1).opcode);
        assertEquals("Test Phone", packets.get(1).json().getString("displayName"));
        assertEquals("YAACC", packets.get(1).json().getString("appName"));
        assertEquals("9.9", packets.get(1).json().getString("appVersion"));
        assertEquals(3, connection.getNegotiatedVersion());
        assertEquals(3, connection.getPeerVersion());
    }

    @Test
    public void theReceiversMessagesArriveInOrder() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        synchronized (recorder.messages) {
            assertTrue(recorder.messages.get(0) instanceof FCastMessages.Version);
            assertTrue(recorder.messages.get(1) instanceof FCastMessages.Initial);
            assertTrue(recorder.messages.get(2) instanceof FCastMessages.VolumeUpdate);
            assertEquals("Fake Receiver", ((FCastMessages.Initial) recorder.messages.get(1)).getDisplayName());
        }
    }

    @Test
    public void aReceiverOfVersion4IsSpokenToInVersion3AndNeverGetsAVersion4() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().greeting(4));
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertTrue(receiver.awaitPackets(Op.INITIAL, 1, WAIT));
        assertEquals(4, connection.getPeerVersion());
        assertEquals(3, connection.getNegotiatedVersion());
        for (FakeFCastReceiver.Packet version : receiver.received(Op.VERSION)) {
            assertEquals(3, version.json().getInt("version"));
        }
        assertFalse("the fixture would have ended the session on a Version 4", recorder.awaitClosed(300));
    }

    @Test
    public void aReceiverThatWaitsForTheSenderStillGetsTheVersionAtOnceAndTheInitialAfterTheWait() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().greeting(0));
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        // the fixture answers a Version with an Initial, so the Version it got is what made it speak
        assertTrue(receiver.awaitPackets(Op.VERSION, 1, WAIT));
        assertNotNull(recorder.await(FCastMessages.Initial.class, WAIT));
        assertTrue(receiver.awaitPackets(Op.INITIAL, 1, WAIT));
    }

    @Test
    public void aReceiverThatNeverSaysVersionStillGetsOurInitialAfterTheWait() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().silent());
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertTrue(receiver.awaitPackets(Op.VERSION, 1, WAIT));
        assertEquals("not yet", 0, receiver.received(Op.INITIAL).size());
        assertTrue(receiver.awaitPackets(Op.INITIAL, 1, WAIT));
        assertEquals(0, connection.getPeerVersion());
        assertEquals(3, connection.getNegotiatedVersion());
    }

    @Test
    public void theInitialIsSentOnlyOnce() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        connect(receiver, new Recorder(), fast());
        assertTrue(receiver.awaitPackets(Op.INITIAL, 1, WAIT));
        Thread.sleep(400); // longer than initialWaitMillis
        assertEquals(1, receiver.received(Op.INITIAL).size());
    }

    // ---- older receivers (task 2.4)

    @Test
    public void aVersion2ReceiverGetsNoInitialAndNothingFromVersion3() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().greeting(2).speaks(2));
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        Thread.sleep(400);
        assertEquals("no Initial for a receiver that has none", 0, receiver.received(Op.INITIAL).size());
        assertEquals(2, connection.getNegotiatedVersion());
        assertTrue("SetSpeed exists from version 2", connection.send(FCastOpcode.SET_SPEED, "{\"speed\":1.5}"));
        assertTrue(receiver.awaitPackets(Op.SET_SPEED, 1, WAIT));
        try {
            connection.send(FCastOpcode.SUBSCRIBE_EVENT, FCastMessages.subscribeEvent(1));
            fail("an event subscription was sent to a version 2 receiver");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.UNSUPPORTED, e.getKind());
            assertTrue(e.getMessage().contains("version 2"));
        }
        assertEquals(0, receiver.received(Op.SUBSCRIBE_EVENT).size());
    }

    @Test
    public void aVersion1ReceiverGetsOnlyTheMessagesOfVersion1() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().greeting(1).speaks(1));
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        assertEquals(1, connection.getNegotiatedVersion());
        assertTrue(connection.send(FCastOpcode.PAUSE, null));
        assertTrue(connection.send(FCastOpcode.SEEK, FCastMessages.seek(5)));
        assertTrue(connection.send(FCastOpcode.SET_VOLUME, FCastMessages.setVolume(0.5)));
        for (FCastOpcode opcode : new FCastOpcode[]{FCastOpcode.SET_SPEED, FCastOpcode.PING, FCastOpcode.PONG, FCastOpcode.INITIAL}) {
            try {
                connection.send(opcode, null);
                fail(opcode + " was sent to a version 1 receiver");
            } catch (FCastProtocolException e) {
                assertEquals(FCastProtocolException.Kind.UNSUPPORTED, e.getKind());
            }
        }
        Thread.sleep(900); // twice deadAfterMillis: a version 1 receiver is never pinged (the desktop receiver ends such a session) and silence proves nothing
        assertEquals(0, receiver.received(Op.PING).size());
        assertEquals(0, receiver.received(Op.INITIAL).size());
        assertFalse("closed: " + recorder.reason, recorder.awaitClosed(50));
    }

    // ---- heartbeat

    @Test
    public void everyPingOfTheReceiverIsAnsweredWithAPong() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().pingEvery(40));
        connect(receiver, new Recorder(), fast());
        assertTrue(receiver.awaitPackets(Op.PONG, 3, WAIT));
    }

    @Test
    public void aPingIsAnsweredEvenWhileTheListenerIsBlocked() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().pingEvery(40));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        FCastConnection.Listener blocking = new FCastConnection.Listener() {
            @Override
            public void onMessage(FCastMessages.Incoming message) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onClosed(FCastConnection.CloseReason reason, String detail) {
            }
        };
        FCastConnection connection = new FCastConnection(new InetSocketAddress("127.0.0.1", receiver.port()), blocking, fast(),
                MonotonicClock.SYSTEM, "p", "a", "1");
        toClose.add(connection);
        connection.connect();
        assertTrue(entered.await(WAIT, TimeUnit.MILLISECONDS));
        assertTrue("pongs while the listener is stuck", receiver.awaitPackets(Op.PONG, 3, WAIT));
        release.countDown();
    }

    @Test
    public void aSilentReceiverIsAskedWithAPingAndThenGivenUp() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().silent());
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.SILENT, recorder.reason);
        assertTrue("it was pinged before", receiver.received(Op.PING).size() >= 1);
        assertTrue(connection.isClosed());
    }

    @Test
    public void aReceiverThatAnswersPingsIsKeptAlive() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        Thread.sleep(1_200); // three times deadAfterMillis, with no traffic but our pings and its pongs
        assertFalse("closed: " + recorder.reason, recorder.awaitClosed(10));
        assertTrue(receiver.received(Op.PING).size() >= 2);
    }

    // ---- bad input from the receiver

    @Test
    public void anOversizePacketEndsTheConnection() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        receiver.session(0).sendOversize(40_000);
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.PROTOCOL_ERROR, recorder.reason);
    }

    @Test
    public void aPacketWithoutOpcodeEndsTheConnection() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        receiver.session(0).sendEmptyPacket();
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.PROTOCOL_ERROR, recorder.reason);
    }

    @Test
    public void aBodyThatDoesNotParseIsDroppedAndTheConnectionGoesOn() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        FakeFCastReceiver.Session session = receiver.session(0);
        session.send(Op.PLAYBACK_UPDATE, "{bad json");
        session.send(Op.PLAYBACK_UPDATE, "{\"state\":9}");
        session.send(Op.VOLUME_UPDATE, "{\"volume\":0.25}");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (recorder.count(FCastMessages.VolumeUpdate.class) < 2 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(2, recorder.count(FCastMessages.VolumeUpdate.class));
        assertEquals(0, recorder.count(FCastMessages.PlaybackUpdate.class));
        assertFalse(recorder.awaitClosed(50));
    }

    @Test
    public void aBodyThatTheJsonGuardRefusesDoesNotKillTheReader() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        StringBuilder hostile = new StringBuilder("{'a':'\"','b':");
        for (int i = 0; i < 20_000; i++) {
            hostile.append('[');
        }
        FakeFCastReceiver.Session session = receiver.session(0);
        session.send(Op.PLAYBACK_UPDATE, hostile.toString());
        session.send(Op.INITIAL, hostile.toString());
        session.sendVolumeUpdate(0.25);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (recorder.count(FCastMessages.VolumeUpdate.class) < 2 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals("the reader is alive and read on", 2, recorder.count(FCastMessages.VolumeUpdate.class));
        assertFalse(recorder.awaitClosed(50));
    }

    @Test
    public void theHandshakeAndHeartbeatMessagesCanNotBeSentByACaller() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        int versions = receiver.received(Op.VERSION).size();
        for (FCastOpcode opcode : new FCastOpcode[]{FCastOpcode.VERSION, FCastOpcode.INITIAL, FCastOpcode.PING, FCastOpcode.PONG,
                FCastOpcode.PLAYBACK_UPDATE, FCastOpcode.VOLUME_UPDATE, FCastOpcode.NONE}) {
            try {
                connection.send(opcode, "{}");
                fail(opcode + " was sent by a caller");
            } catch (FCastProtocolException e) {
                assertEquals(FCastProtocolException.Kind.UNSUPPORTED, e.getKind());
            }
        }
        assertEquals("no second Version reached the receiver (it would end the session)", versions, receiver.received(Op.VERSION).size());
    }

    @Test
    public void anUnknownOpcodeIsSkipped() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        receiver.session(0).send(99, "{\"x\":1}");
        receiver.session(0).sendPlaybackError("after");
        assertNotNull(recorder.await(FCastMessages.PlaybackError.class, WAIT));
        assertFalse(recorder.awaitClosed(50));
    }

    @Test
    public void aListenerThatThrowsDoesNotStopTheDelivery() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        FCastConnection connection = new FCastConnection(new InetSocketAddress("127.0.0.1", receiver.port()), new FCastConnection.Listener() {
            @Override
            public void onMessage(FCastMessages.Incoming message) {
                seen.add(message.getClass().getSimpleName());
                if (message instanceof FCastMessages.Version) {
                    throw new IllegalStateException("a bug in the listener");
                }
                if (message instanceof FCastMessages.VolumeUpdate) {
                    done.countDown();
                }
            }

            @Override
            public void onClosed(FCastConnection.CloseReason reason, String detail) {
            }
        }, fast(), MonotonicClock.SYSTEM, "p", "a", "1");
        toClose.add(connection);
        connection.connect();
        assertTrue(done.await(WAIT, TimeUnit.MILLISECONDS));
        assertEquals("Version", seen.get(0));
    }

    // ---- stalled and flooding peers

    private static String bigBody() {
        StringBuilder sb = new StringBuilder("{\"container\":\"a/b\",\"url\":\"http://h/");
        while (sb.length() < 30_000) {
            sb.append("0123456789");
        }
        return sb.append("\"}").toString();
    }

    @Test
    public void aReceiverThatDoesNotReadFillsTheQueueAndTheConnectionIsClosed() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().stalled());
        Recorder recorder = new Recorder();
        FCastConnection.Config config = fast();
        config.writeQueueCapacity = 4;
        config.deadAfterMillis = 60_000;
        config.pingAfterMillis = 30_000;
        FCastConnection connection = connect(receiver, recorder, config);
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        String body = bigBody();
        boolean refused = false;
        for (int i = 0; i < 5_000 && !refused; i++) {
            refused = !connection.send(FCastOpcode.PLAY, body);
        }
        assertTrue("the queue overflowed", refused);
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.WRITE_OVERFLOW, recorder.reason);
        assertFalse("sending after the close is refused", connection.send(FCastOpcode.PAUSE, null));
    }

    @Test
    public void aWriteThatBlocksTooLongClosesTheConnection() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().stalled());
        Recorder recorder = new Recorder();
        FCastConnection.Config config = fast();
        config.writeQueueCapacity = 100_000;
        config.writeTimeoutMillis = 300;
        config.deadAfterMillis = 60_000;
        config.pingAfterMillis = 30_000;
        FCastConnection connection = connect(receiver, recorder, config);
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        String body = bigBody();
        for (int i = 0; i < 3_000 && !connection.isClosed(); i++) {
            connection.send(FCastOpcode.PLAY, body);
        }
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.WRITE_TIMEOUT, recorder.reason);
    }

    @Test
    public void closingWhileAWriteIsBlockedEndsAllThreads() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().stalled());
        Recorder recorder = new Recorder();
        FCastConnection.Config config = fast();
        config.writeQueueCapacity = 100_000;
        config.deadAfterMillis = 60_000;
        config.pingAfterMillis = 30_000;
        FCastConnection connection = connect(receiver, recorder, config);
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        String body = bigBody();
        for (int i = 0; i < 500; i++) {
            connection.send(FCastOpcode.PLAY, body);
        }
        Thread.sleep(200); // the writer is now blocked in a write
        connection.close();
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.CLOSED_BY_US, recorder.reason);
        assertTrue("all threads ended", connection.awaitTermination(WAIT));
    }

    @Test
    public void aListenerThatCanNotKeepUpIsCutOff() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final List<FCastConnection.CloseReason> reasons = Collections.synchronizedList(new ArrayList<FCastConnection.CloseReason>());
        final CountDownLatch closed = new CountDownLatch(1);
        FCastConnection.Config config = fast();
        config.listenerQueueCapacity = 8;
        config.deadAfterMillis = 60_000;
        config.pingAfterMillis = 30_000;
        FCastConnection connection = new FCastConnection(new InetSocketAddress("127.0.0.1", receiver.port()), new FCastConnection.Listener() {
            @Override
            public void onMessage(FCastMessages.Incoming message) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onClosed(FCastConnection.CloseReason reason, String detail) {
                reasons.add(reason);
                closed.countDown();
            }
        }, config, MonotonicClock.SYSTEM, "p", "a", "1");
        toClose.add(connection);
        connection.connect();
        assertTrue(entered.await(WAIT, TimeUnit.MILLISECONDS));
        try {
            for (int i = 0; i < 100; i++) {
                receiver.session(0).sendVolumeUpdate(0.5);
            }
        } catch (IOException closedByTheFlood) {
            // the connection was cut off while the receiver was still sending: that is the point of the test
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!connection.isClosed() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertTrue("closed as a flood", connection.isClosed());
        release.countDown();
        assertTrue(closed.await(WAIT, TimeUnit.MILLISECONDS));
        assertEquals(FCastConnection.CloseReason.FLOOD, reasons.get(0));
        assertEquals(1, reasons.size());
    }

    // ---- closing

    @Test
    public void whenTheReceiverClosesTheListenerHearsItOnceAfterTheLastMessage() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        receiver.session(0).sendPlaybackError("bye");
        receiver.closeConnection(0);
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.PEER_CLOSED, recorder.reason);
        assertEquals("the message before the close was delivered", 1, recorder.count(FCastMessages.PlaybackError.class));
        Thread.sleep(100);
        assertEquals(1, recorder.closeCalls);
    }

    @Test
    public void closeIsOrderlyRepeatableAndLeavesNoThread() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        assertNotNull(recorder.await(FCastMessages.VolumeUpdate.class, WAIT));
        connection.close();
        connection.close();
        assertTrue(recorder.awaitClosed(WAIT));
        assertEquals(FCastConnection.CloseReason.CLOSED_BY_US, recorder.reason);
        assertTrue(connection.awaitTermination(WAIT));
        Thread.sleep(50);
        assertEquals(1, recorder.closeCalls);
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse("a thread of the connection is still alive: " + thread.getName(),
                    thread.getName().startsWith("fcast-") && thread.isAlive());
        }
        assertFalse(connection.send(FCastOpcode.PAUSE, null));
    }

    @Test
    public void aConnectionThatCanNotBeMadeFailsWithAnIoException() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            port = socket.getLocalPort();
        }
        Recorder recorder = new Recorder();
        FCastConnection connection = new FCastConnection(new InetSocketAddress("127.0.0.1", port), recorder, fast(), MonotonicClock.SYSTEM, "p", "a", "1");
        try {
            connection.connect();
            fail("connected to a closed port");
        } catch (IOException expected) {
            // as wanted
        }
        connection.close();
        assertNull("nobody listens when it never connected", recorder.reason);
    }

    @Test
    public void aMessageThatDoesNotFitIsRefusedWithoutClosing() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Recorder recorder = new Recorder();
        FCastConnection connection = connect(receiver, recorder, fast());
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            huge.append('a');
        }
        try {
            connection.send(FCastOpcode.PLAY, huge.toString());
            fail();
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.TOO_LARGE, e.getKind());
        }
        assertFalse(connection.isClosed());
        assertTrue(connection.send(FCastOpcode.PAUSE, null));
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
    }
}
