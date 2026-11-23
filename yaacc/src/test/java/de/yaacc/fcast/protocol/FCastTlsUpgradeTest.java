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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.yaacc.fcast.FakeFCastReceiver.Op;
import de.yaacc.fcast.FakeFCastReceiver.Packet;
import de.yaacc.fcast.FakeFCastTlsReceiver;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * Task 7.2: the start of a v4 connection against a loopback receiver that serves real TLS 1.3 with a self-signed key: the
 * version choice (a pin and API 29 or later, else v3), the pinned key (right key, wrong key, other key), no fall back to v3
 * after a refusal, a TLS 1.2 server, a silent server, a receiver that answers with an old version, and a working secured
 * connection (ping and pong inside TLS).
 */
public class FCastTlsUpgradeTest {
    private static final long WAIT = 5_000;

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

        @Override
        public void onMessage(FCastMessages.Incoming message) {
            messages.add(message);
        }

        @Override
        public void onClosed(FCastConnection.CloseReason reason, String detail) {
            this.reason = reason;
            closed.countDown();
        }
    }

    private FakeFCastTlsReceiver receiver(FakeFCastTlsReceiver.Keys keys, FakeFCastTlsReceiver.Options options) throws IOException {
        FakeFCastTlsReceiver receiver = new FakeFCastTlsReceiver(keys, options);
        toClose.add(receiver);
        return receiver;
    }

    private static FCastConnection.Config config(FCastPin pin, int sdk) {
        FCastConnection.Config config = new FCastConnection.Config();
        config.connectTimeoutMillis = 1_500;
        config.pingAfterMillis = 60_000;
        config.deadAfterMillis = 120_000;
        config.tickMillis = 20;
        config.pin = pin;
        config.platformSdk = sdk;
        return config;
    }

    private FCastConnection connection(FakeFCastTlsReceiver receiver, FCastPin pin, int sdk, Recorder recorder) {
        FCastConnection connection = new FCastConnection(new InetSocketAddress("127.0.0.1", receiver.port()), recorder,
                config(pin, sdk), MonotonicClock.SYSTEM, "Test", "YAACC", "1");
        toClose.add(connection);
        return connection;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertTrue("the condition did not become true", condition.getAsBoolean());
    }

    private static FCastSecurityException refused(FCastConnection connection) throws Exception {
        try {
            connection.connect();
        } catch (FCastSecurityException expected) {
            return expected;
        }
        fail("the connection should have been refused");
        return null;
    }

    // ------------------------------------------------------------------ the right key

    @Test
    public void theRightKeyGivesATls13ConnectionThatSpeaksVersion4() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        Recorder recorder = new Recorder();
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, recorder);

        connection.connect();

        assertTrue(connection.isSecure());
        assertEquals(4, connection.getNegotiatedVersion());
        awaitTrue(() -> receiver.protocols().size() == 1);
        assertEquals("TLSv1.3", receiver.protocols().get(0));
        assertEquals(Collections.singletonList(4), receiver.senderVersions());
        awaitTrue(() -> !recorder.messages.isEmpty());
        assertEquals(4, ((FCastMessages.Version) recorder.messages.get(0)).getVersion());
    }

    @Test
    public void theSecuredConnectionAnswersAPingInsideTls() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.DISCOVERY), 33, new Recorder());
        connection.connect();
        awaitTrue(() -> receiver.protocols().size() == 1);

        receiver.sendSecure(Op.PING, null);

        awaitTrue(() -> {
            for (Packet packet : receiver.packets()) {
                if (packet.opcode == Op.PONG) {
                    return true;
                }
            }
            return false;
        });
    }

    @Test
    public void aSecuredConnectionSendsNoV3Initial() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder());
        connection.connect();
        awaitTrue(() -> receiver.protocols().size() == 1);
        Thread.sleep(300); // the v3 path sends its Initial after the receiver's Version, at the latest after initialWaitMillis (1 s by default)
        for (Packet packet : receiver.packets()) {
            assertFalse("no JSON packet of v3 inside a v4 connection: " + packet, packet.opcode == Op.INITIAL);
        }
    }

    @Test
    public void aCommandOnASecuredConnectionGoesOutAsAFlatbufPacketNotAsJson() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder());
        connection.connect();
        awaitTrue(() -> receiver.protocols().size() == 1);

        assertTrue(connection.send(FCastOpcode.PAUSE, null));

        awaitTrue(() -> receiver.flatbufs().size() >= 2); // our introduction, then the pause
        for (Packet packet : receiver.packets()) {
            assertEquals("every packet inside TLS is a Flatbuf packet", FakeFCastTlsReceiver.FLATBUF, packet.opcode);
        }
    }

    @Test
    public void anOpcodeThatTheConnectionSendsItselfIsRefusedToACallerOnASecuredConnectionToo() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder());
        connection.connect();
        try {
            connection.send(FCastOpcode.VERSION, "{\"version\":3}");
            fail("the handshake is the connection's own business");
        } catch (FCastProtocolException expected) {
            assertEquals(FCastProtocolException.Kind.UNSUPPORTED, expected.getKind());
        }
    }

    @Test
    public void aSecuredConnectionToAReceiverThatStopsReadingEndsWithAWriteTimeoutAndDoesNotHangInClose() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.stopReadingAfterHandshake = true;
        FakeFCastTlsReceiver receiver = receiver(keys, options);
        Recorder recorder = new Recorder();
        FCastConnection.Config config = config(keys.pin(FCastPin.Source.LINK), 29);
        config.writeTimeoutMillis = 500;
        FCastConnection connection = new FCastConnection(new InetSocketAddress("127.0.0.1", receiver.port()), recorder, config,
                MonotonicClock.SYSTEM, "Test", "YAACC", "1");
        toClose.add(connection);
        connection.connect();
        StringBuilder big = new StringBuilder("http://h/");
        for (int i = 0; i < 400_000; i++) {
            big.append('x');
        }
        byte[] message = de.yaacc.fcast.protocol.v4.V4Writer.load("audio/mpeg", big.toString(), null, null, null, null, null);
        for (int i = 0; i < 60 && !connection.isClosed(); i++) {
            connection.sendV4(message); // 24 MB in all: more than the buffers of the connection take
        }

        assertTrue("the end is reported although the TLS socket is stuck in a write", recorder.closed.await(8, TimeUnit.SECONDS));
        assertTrue(recorder.reason == FCastConnection.CloseReason.WRITE_TIMEOUT || recorder.reason == FCastConnection.CloseReason.WRITE_OVERFLOW);
        assertTrue("all threads end", connection.awaitTermination(5_000));
    }

    // ------------------------------------------------------------------ the wrong key

    @Test
    public void aWrongFingerprintIsRefusedAndThereIsNoSecondConnection() throws Exception {
        FakeFCastTlsReceiver receiver = receiver(FakeFCastTlsReceiver.Keys.get(0), new FakeFCastTlsReceiver.Options());
        FCastPin other = FakeFCastTlsReceiver.Keys.get(1).pin(FCastPin.Source.LINK);
        FCastConnection connection = connection(receiver, other, 29, new Recorder());

        FCastSecurityException e = refused(connection);

        assertEquals(FCastSecurityException.Kind.PIN_MISMATCH, e.getKind());
        Thread.sleep(300);
        assertEquals("no fall back to v3 after a refusal", 1, receiver.accepted());
        assertEquals(Collections.singletonList(4), receiver.senderVersions());
        assertTrue(receiver.packets().isEmpty());
    }

    @Test
    public void aFingerprintThatIsOneBitOffIsRefused() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        byte[] bytes = java.util.Base64.getDecoder().decode(keys.pin(FCastPin.Source.LINK).toBase64());
        bytes[31] ^= 1;
        FCastConnection connection = connection(receiver, FCastPin.of(bytes, FCastPin.Source.LINK), 29, new Recorder());

        assertEquals(FCastSecurityException.Kind.PIN_MISMATCH, refused(connection).getKind());
    }

    @Test
    public void aReceiverThatMadeANewKeyIsRefusedLikeAnyOtherWrongKey() throws Exception {
        // the stored pin is the old key (index 0); the receiver now serves the new key (index 1)
        FakeFCastTlsReceiver receiver = receiver(FakeFCastTlsReceiver.Keys.get(1), new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, FakeFCastTlsReceiver.Keys.get(0).pin(FCastPin.Source.DISCOVERY), 29, new Recorder());

        assertEquals(FCastSecurityException.Kind.PIN_MISMATCH, refused(connection).getKind());
    }

    // ------------------------------------------------------------------ the receiver does not do what it announced

    @Test
    public void aKnownV4ReceiverThatAnswersVersion3IsAnErrorNotAFallback() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.greetingVersion = 3;
        FakeFCastTlsReceiver receiver = receiver(keys, options);
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder());

        FCastSecurityException e = refused(connection);

        assertEquals(FCastSecurityException.Kind.DOWNGRADE, e.getKind());
        Thread.sleep(200);
        assertEquals(1, receiver.accepted());
        assertTrue("nothing but our Version 4 was sent in plaintext", receiver.packets().isEmpty());
    }

    @Test
    public void somethingElseThanAVersionAsTheFirstPacketIsAnError() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.garbageFirst = true;
        FakeFCastTlsReceiver receiver = receiver(keys, options);

        assertEquals(FCastSecurityException.Kind.DOWNGRADE,
                refused(connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder())).getKind());
    }

    @Test
    public void aReceiverThatServesOnlyTls12IsRefused() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.tlsProtocol = "TLSv1.2";
        FakeFCastTlsReceiver receiver = receiver(keys, options);

        assertEquals(FCastSecurityException.Kind.HANDSHAKE_FAILED,
                refused(connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder())).getKind());
        assertTrue(receiver.protocols().isEmpty());
    }

    @Test
    public void aReceiverThatDoesNotAnswerTheHandshakeTimesOut() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.silentAfterVersion = true;
        FakeFCastTlsReceiver receiver = receiver(keys, options);
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder());
        connection.getClass(); // the config has a connect timeout of 1.5 s, which the handshake uses too

        long start = System.nanoTime();
        try {
            connection.connect();
            fail("the handshake can not succeed");
        } catch (IOException expected) {
            // a security exception (handshake failed) or a timeout, both end the attempt
        }
        assertTrue("the attempt ends within the timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4_000);
    }

    @Test
    public void aReceiverThatClosesAfterTheVersionEndsTheAttempt() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.closeAfterVersion = true;
        FakeFCastTlsReceiver receiver = receiver(keys, options);
        try {
            connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder()).connect();
            fail("no connection without an answer");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage() == null ? "" : expected.getMessage());
        }
    }

    // ------------------------------------------------------------------ the version choice

    @Test
    public void withoutAPinTheConnectionSpeaksV3EvenToAV4Receiver() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, null, 34, new Recorder());

        connection.connect();

        assertFalse(connection.isSecure());
        assertEquals(3, connection.getNegotiatedVersion());
        awaitTrue(() -> receiver.senderVersions().size() == 1);
        assertEquals(Collections.singletonList(3), receiver.senderVersions());
        assertTrue(receiver.protocols().isEmpty());
    }

    @Test
    public void belowApi29AKnownPinStillMeansV3() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 28, new Recorder());

        connection.connect();

        assertFalse(connection.isSecure());
        assertEquals(3, connection.getNegotiatedVersion());
        awaitTrue(() -> receiver.senderVersions().size() == 1);
        assertEquals(Collections.singletonList(3), receiver.senderVersions());
    }

    @Test
    public void api29IsTheFirstLevelThatTriesV4() throws Exception {
        assertEquals(29, FCastConnection.MIN_SDK_FOR_V4);
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys, new FakeFCastTlsReceiver.Options());
        FCastConnection connection = connection(receiver, keys.pin(FCastPin.Source.LINK), 29, new Recorder());
        connection.connect();
        assertTrue(connection.isSecure());
    }

    @Test
    public void aPlainV3ReceiverIsStillSpokenToInV3WithoutAPin() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.greetingVersion = 3;
        FakeFCastTlsReceiver receiver = receiver(keys, options);
        Recorder recorder = new Recorder();
        FCastConnection connection = connection(receiver, null, 34, recorder);

        connection.connect();

        awaitTrue(() -> !recorder.messages.isEmpty());
        assertEquals(3, connection.getNegotiatedVersion());
        assertFalse(connection.isSecure());
    }

    // ------------------------------------------------------------------ the trust manager itself

    @Test
    public void theTrustManagerAcceptsOnlyTheLeafWithTheRightKeyAndNoClients() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver.Keys other = FakeFCastTlsReceiver.Keys.get(1);
        FCastTls.PinTrustManager trust = new FCastTls.PinTrustManager(keys.pin(FCastPin.Source.LINK));
        trust.checkServerTrusted(new java.security.cert.X509Certificate[]{keys.certificate()}, "ECDHE_ECDSA");
        try {
            trust.checkServerTrusted(new java.security.cert.X509Certificate[]{other.certificate()}, "ECDHE_ECDSA");
            fail("another key");
        } catch (java.security.cert.CertificateException expected) {
            assertTrue(expected instanceof FCastTls.PinMismatch);
        }
        try {
            trust.checkServerTrusted(new java.security.cert.X509Certificate[0], "ECDHE_ECDSA");
            fail("no certificate");
        } catch (java.security.cert.CertificateException expected) {
            assertTrue(expected instanceof FCastTls.PinMismatch);
        }
        try {
            trust.checkServerTrusted(null, "ECDHE_ECDSA");
            fail("no chain");
        } catch (java.security.cert.CertificateException expected) {
            assertTrue(expected instanceof FCastTls.PinMismatch);
        }
        try {
            trust.checkClientTrusted(new java.security.cert.X509Certificate[]{keys.certificate()}, "ECDHE_ECDSA");
            fail("this side takes no clients");
        } catch (java.security.cert.CertificateException expected) {
            // fine
        }
        assertEquals(0, trust.getAcceptedIssuers().length);
    }
}
