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
package de.yaacc.multiroom.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.protocol.ConnectionFixtures.CountingTransport;
import de.yaacc.multiroom.protocol.ConnectionFixtures.Conn;
import de.yaacc.multiroom.protocol.ConnectionFixtures.FakeServer;
import de.yaacc.multiroom.protocol.ConnectionFixtures.GaveUp;
import de.yaacc.multiroom.protocol.ConnectionFixtures.ManualScheduler;
import de.yaacc.multiroom.protocol.ConnectionFixtures.OffsetUpdate;
import de.yaacc.multiroom.protocol.ConnectionFixtures.Recorder;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The connection against a loopback server on the literal 127.0.0.1. Waits are on events and latches; the clock is a
 * {@link ManualClock} wherever time matters, so nothing here depends on how fast the machine is. The hostile cases use
 * the real clock with short limits: they only end sooner or later, never differently.
 */
public class SnapcastConnectionTest {

    private static final long OFFSET = 4_000_000_000L; // the server clock is 4000 s ahead
    private static final HelloMessage HELLO = HelloMessage.create("yaacc-test", "testhost", "YAACC", "Linux", "x86_64", "0.0.1");
    private static final ServerSettingsMessage SETTINGS = new ServerSettingsMessage(800, 20, false, 70);

    private final List<AutoCloseable> toClose = new ArrayList<>();

    @After
    public void closeEverything() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    private FakeServer serve(ConnectionFixtures.Script script) throws Exception {
        FakeServer server = new FakeServer(script);
        toClose.add(server);
        return server;
    }

    private SnapcastConnection connect(SnapcastTransportHolder holder, SnapcastConnection.Config config, MicroClock clock,
                                       ManualScheduler scheduler, Recorder recorder) {
        SnapcastConnection connection = new SnapcastConnection(holder.transport, HELLO, config, clock, scheduler, null);
        connection.addListener(recorder);
        toClose.add(connection);
        return connection;
    }

    /** A transport and the port it was made for. */
    private static final class SnapcastTransportHolder {
        final CountingTransport transport;

        SnapcastTransportHolder(FakeServer server, int failures) {
            transport = new CountingTransport(server.port(), failures);
        }
    }

    private static SnapcastConnection.Config quick() {
        return new SnapcastConnection.Config().readTimeoutMs(25).connectTimeoutMs(5_000);
    }

    private static WireChunkMessage chunk(int number) {
        return new WireChunkMessage(number, 0, new byte[]{(byte) number});
    }

    private static CodecHeaderMessage pcmHeader() {
        return CodecHeaderMessage.of(Codec.PCM, Bytes.WAVE_48000_STEREO_16);
    }

    /** Hello, settings, header: what a snapserver sends a joining client. */
    private static HelloMessage handshake(Conn connection) throws Exception {
        HelloMessage hello = connection.readMessage(HelloMessage.class);
        connection.send(SETTINGS);
        connection.send(pcmHeader());
        return hello;
    }

    // ---- the handshake

    @Test
    public void helloGoesFirstThenSettingsHeaderAndChunksComeToTheListener() throws Exception {
        final AtomicReference<Frame> first = new AtomicReference<>();
        FakeServer server = serve(connection -> {
            Frame frame = connection.readFrame();
            first.set(frame);
            connection.send(SETTINGS);
            connection.send(pcmHeader());
            connection.send(chunk(1));
            connection.send(chunk(2));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();

        assertEquals(SETTINGS, recorder.next(ServerSettingsMessage.class));
        assertEquals(pcmHeader(), recorder.next(CodecHeaderMessage.class));
        assertEquals(chunk(1), recorder.next(WireChunkMessage.class));
        assertEquals(chunk(2), recorder.next(WireChunkMessage.class));
        assertEquals(5, first.get().getHeader().getType());
        HelloMessage sent = (HelloMessage) first.get().getMessage();
        assertEquals("yaacc-test", sent.getId());
        assertEquals("00:00:00:00:00:00", sent.getMac());
        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
    }

    @Test
    public void noChunkIsDeliveredBeforeTheCodecHeader() throws Exception {
        FakeServer server = serve(connection -> {
            connection.readMessage(HelloMessage.class);
            connection.send(chunk(1));           // before everything
            connection.send(SETTINGS);
            connection.send(chunk(2));           // settings but no header yet
            connection.send(pcmHeader());
            connection.send(chunk(3));
            connection.send(chunk(4));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();

        assertEquals(SETTINGS, recorder.next(ServerSettingsMessage.class));
        assertEquals(pcmHeader(), recorder.next(CodecHeaderMessage.class));
        assertEquals(3, recorder.next(WireChunkMessage.class).getTimestampSec());
        assertEquals(4, recorder.next(WireChunkMessage.class).getTimestampSec());
        assertEquals(2, connection.getDroppedBeforeHeader());
    }

    @Test
    public void aNewCodecHeaderOnTheSameConnectionIsDeliveredAgain() throws Exception {
        CodecHeaderMessage flac = CodecHeaderMessage.of(Codec.FLAC, Bytes.FLAC_48000_STEREO_16);
        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.send(chunk(1));
            connection.send(flac);
            connection.send(chunk(2));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();

        recorder.next(ServerSettingsMessage.class);
        assertEquals(pcmHeader(), recorder.next(CodecHeaderMessage.class));
        recorder.next(WireChunkMessage.class);
        assertEquals(flac, recorder.next(CodecHeaderMessage.class));
        recorder.next(WireChunkMessage.class);
    }

    @Test
    public void callbacksRunOffTheSocketThreadAndAThrowingListenerDoesNotStopTheOthers() throws Exception {
        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.send(chunk(1));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        Recorder thrower = new Recorder() {
            @Override
            public void onServerSettings(ServerSettingsMessage settings) {
                throw new IllegalStateException("a broken listener");
            }
        };
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);
        connection.addListener(thrower);

        connection.start();

        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        recorder.next(WireChunkMessage.class);
        thrower.next(CodecHeaderMessage.class);
        for (String thread : recorder.threads) {
            assertTrue(thread, thread.startsWith("snapcast-bin-listener"));
        }
    }

    @Test
    public void clientInfoReachesTheServerAndIsRefusedWhileNotConnected() throws Exception {
        final AtomicReference<Frame> received = new AtomicReference<>();
        final CountDownLatch got = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            while (true) {
                Frame frame = connection.readFrame();
                if (frame.getMessage() instanceof ClientInfoMessage) {
                    received.set(frame);
                    got.countDown();
                    return;
                }
            }
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        assertFalse(connection.sendClientInfo(10, false));
        connection.start();
        recorder.nextOf(CodecHeaderMessage.class);

        assertTrue(connection.sendClientInfo(30, true));
        assertTrue(got.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(new ClientInfoMessage(30, true), received.get().getMessage());
        assertEquals(7, received.get().getHeader().getType());
    }

    @Test
    public void theConnectionNeverGoesToAHostOrPortTheServerNames() throws Exception {
        FakeServer server = serve(connection -> {
            connection.readMessage(HelloMessage.class);
            connection.send(Bytes.frame(3, Bytes.jsonBody(
                    "{\"bufferMs\":800,\"host\":\"203.0.113.9\",\"port\":9,\"url\":\"tcp://203.0.113.9:1704\"}")));
            connection.send(pcmHeader());
            connection.send(chunk(1));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastTransportHolder holder = new SnapcastTransportHolder(server, 0);
        SnapcastConnection connection = connect(holder, quick(), new ManualClock(1_000_000), new ManualScheduler(), recorder);

        connection.start();

        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        recorder.next(WireChunkMessage.class);
        assertEquals(1, holder.transport.opens());
        assertEquals(1, server.accepted());
    }

    // ---- time sync

    private FakeServer timeServer(final ManualClock clock, final java.util.concurrent.BlockingQueue<Long> requestStamps,
                                  final long reportedLatencyAdjust) throws Exception {
        return serve(connection -> {
            handshake(connection);
            while (true) {
                Frame request = connection.readTimeRequest();
                requestStamps.add(request.getHeader().sentMicros());
                long serverNow = clock.nowMicros() + OFFSET;
                long latency = serverNow - request.getHeader().sentMicros() + reportedLatencyAdjust;
                connection.send(TimeMessage.ofMicros(latency), 0, request.getHeader().getId(), serverNow);
            }
        });
    }

    @Test
    public void timeRequestsFollowTheStatedScheduleAndTheOffsetIsFoundExactlyOnAnIdealNetwork() throws Exception {
        ManualClock clock = new ManualClock(10_000_000L);
        java.util.concurrent.BlockingQueue<Long> stamps = new java.util.concurrent.LinkedBlockingQueue<>();
        FakeServer server = timeServer(clock, stamps, 0);
        Recorder recorder = new Recorder();
        SnapcastConnection.Config config = quick().timeSyncBurstCount(5).timeSyncBurstIntervalMs(100).timeSyncIntervalMs(1000);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, new ManualScheduler(), recorder);

        connection.start();
        assertTrue(connection.awaitTimeSamples(1, ConnectionFixtures.WAIT_SECONDS * 1000));
        for (int n = 2; n <= 8; n++) {
            clock.advanceMillis(n <= 5 ? 100 : 1000);
            assertTrue("sample " + n, connection.awaitTimeSamples(n, ConnectionFixtures.WAIT_SECONDS * 1000));
        }

        List<Long> gaps = new ArrayList<>();
        long previous = stamps.take();
        assertEquals("the first request goes out when the settings arrive", 10_000_000L, previous);
        for (int i = 1; i < 8; i++) {
            long stamp = stamps.take();
            gaps.add((stamp - previous) / 1000);
            previous = stamp;
        }
        assertEquals("a burst of 5 requests 100 ms apart, then one per second", java.util.Arrays.asList(100L, 100L, 100L, 100L, 1000L, 1000L, 1000L), gaps);
        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        OffsetUpdate first = recorder.next(OffsetUpdate.class); // the third sample is the first synchronised one
        assertEquals(OFFSET, first.offsetMicros);
        assertEquals(0, first.jitterMicros);
        assertEquals(OFFSET, connection.getOffsetFilter().offsetMicros());
        assertTrue(connection.getOffsetFilter().isSynchronized());
    }

    /**
     * An idle stream (no chunks at all) must not end the connection: the time answers keep it alive (the idle limit is
     * about frames of any kind). Owner's trace 2026-11-12: the connection was lost during an idle phase; whatever did it,
     * it was not this limit as long as the server answers the time requests.
     */
    @Test
    public void aStreamWithoutChunksButWithTimeAnswersIsNotDroppedByTheIdleLimit() throws Exception {
        ManualClock clock = new ManualClock(10_000_000L);
        java.util.concurrent.BlockingQueue<Long> stamps = new java.util.concurrent.LinkedBlockingQueue<>();
        FakeServer server = timeServer(clock, stamps, 0);
        Recorder recorder = new Recorder();
        SnapcastConnection.Config config = quick().timeSyncBurstCount(1).timeSyncIntervalMs(1000).idleTimeoutMs(10_000);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, new ManualScheduler(), recorder);

        connection.start();
        assertTrue(connection.awaitTimeSamples(1, ConnectionFixtures.WAIT_SECONDS * 1000));
        for (int n = 2; n <= 40; n++) { // 39 seconds of an idle stream: four times the idle limit
            clock.advanceMillis(1000);
            assertTrue("sample " + n, connection.awaitTimeSamples(n, ConnectionFixtures.WAIT_SECONDS * 1000));
        }
        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
        connection.close();
    }

    @Test
    public void aTimeAnswerThatIsNotForARequestIsIgnored() throws Exception {
        final ManualClock clock = new ManualClock(10_000_000L);
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int answered = 1; ; answered++) {
                Frame request = connection.readTimeRequest();
                long serverNow = clock.nowMicros() + OFFSET;
                connection.send(TimeMessage.ofMicros(serverNow - request.getHeader().sentMicros()), 0,
                        request.getHeader().getId(), serverNow);
                if (answered == 3) {
                    // nobody asked for this one, and it would move the estimate by 250 ms if it were used
                    long wrong = serverNow + 500_000L;
                    connection.send(TimeMessage.ofMicros(wrong - clock.nowMicros()), 0, 4242, wrong);
                }
            }
        });
        Recorder recorder = new Recorder();
        SnapcastConnection.Config config = quick().timeSyncBurstCount(10).timeSyncBurstIntervalMs(100);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, new ManualScheduler(), recorder);

        connection.start();
        assertTrue(connection.awaitTimeSamples(1, ConnectionFixtures.WAIT_SECONDS * 1000));
        for (int n = 2; n <= 4; n++) {
            clock.advanceMillis(100);
            assertTrue(connection.awaitTimeSamples(n, ConnectionFixtures.WAIT_SECONDS * 1000));
        }

        assertEquals("only the answers to our requests count", 4, connection.getOffsetFilter().sampleCount());
        assertEquals(OFFSET, connection.getOffsetFilter().offsetMicros());
        assertEquals(1, connection.getUnsolicitedTime());
    }

    @Test
    public void aTimeAnswerThatClaimsMoreNetworkTimeThanWeMeasuredIsRefused() throws Exception {
        ManualClock clock = new ManualClock(10_000_000L);
        // the answer says the round trip took 200 ms while our own clock saw no time pass at all
        FakeServer server = timeServer(clock, new java.util.concurrent.LinkedBlockingQueue<Long>(), 200_000L);
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), clock, new ManualScheduler(), recorder);

        connection.start();
        assertTrue(connection.awaitTimeSamples(1, ConnectionFixtures.WAIT_SECONDS * 1000));

        assertEquals(0, connection.getOffsetFilter().sampleCount());
        assertEquals(1, connection.getRejectedTimeSamples());
        assertFalse(connection.getOffsetFilter().isSynchronized());
    }

    // ---- hostile servers

    private SnapcastConnection.Loss lostAfterHostile(ConnectionFixtures.Script script, SnapcastConnection.Config config,
                                                     ManualScheduler scheduler) throws Exception {
        FakeServer server = serve(script);
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, MicroClock.monotonic(), scheduler, recorder);
        connection.start();
        return recorder.nextOf(SnapcastConnection.Loss.class);
    }

    @Test
    public void anOversizedMessageDropsTheConnection() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        final CountDownLatch closed = new CountDownLatch(1);

        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            connection.send(Bytes.header(2, 0, 0, 0, 0, 0, 0, 8L * 1024 * 1024 + 1));
            connection.awaitClientClose();
            closed.countDown();
        }, quick(), scheduler);

        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, loss.getReason());
        assertTrue("the client hung up", closed.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("it will try again later", 1, scheduler.pending());
    }

    @Test
    public void garbageInsteadOfAMessageDropsTheConnection() throws Exception {
        byte[] garbage = new byte[200];
        java.util.Arrays.fill(garbage, (byte) 0xff);
        SnapcastConnection.Loss ones = lostAfterHostile(connection -> {
            handshake(connection);
            connection.send(garbage);
            connection.awaitClientClose();
        }, quick(), new ManualScheduler());
        SnapcastConnection.Loss text = lostAfterHostile(connection -> {
            connection.readFrame();
            connection.send(Bytes.ascii("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
            connection.awaitClientClose();
        }, quick(), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, ones.getReason());
        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, text.getReason());
    }

    @Test
    public void aServerThatSendsOneByteAndStopsHitsTheReadTimeout() throws Exception {
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            connection.send(Bytes.of(0x02)); // the first byte of a header, then silence for as long as the client waits
            connection.awaitClientClose();
        }, quick().readTimeoutMs(100).messageDeadlineMs(300).idleTimeoutMs(60_000), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.TIMEOUT, loss.getReason());
    }

    @Test
    public void aPauseInsideAChunkIsNotCutShortByTheTimerOfTheNextTimeRequest() throws Exception {
        // the clock stands still, so the next time request is "due in 100 ms" for the whole test: the loop wakes every
        // 100 ms to look at it, which must not decide how long a stall inside a message is tolerated
        ManualClock clock = new ManualClock(10_000_000L);
        final byte[] frame = SnapcastMessageCodec.encode(new WireChunkMessage(7, 0, new byte[200]), 0, 0, 0);
        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.send(java.util.Arrays.copyOfRange(frame, 0, 40));
            Thread.sleep(300); // the pause of a retransmission, pacing of the sender and not a synchronisation
            connection.send(java.util.Arrays.copyOfRange(frame, 40, frame.length));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection.Config config = new SnapcastConnection.Config().readTimeoutMs(5_000).timeSyncBurstCount(1)
                .timeSyncIntervalMs(100);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, new ManualScheduler(), recorder);

        connection.start();

        assertEquals(7, recorder.nextOf(WireChunkMessage.class).getTimestampSec());
        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
    }

    @Test
    public void aStallInsideAChunkBeyondTheMessageDeadlineStillDropsTheConnection() throws Exception {
        final byte[] frame = SnapcastMessageCodec.encode(new WireChunkMessage(7, 0, new byte[200]), 0, 0, 0);
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            connection.send(java.util.Arrays.copyOfRange(frame, 0, 40));
            connection.awaitClientClose();
        }, new SnapcastConnection.Config().readTimeoutMs(5_000).messageDeadlineMs(300).idleTimeoutMs(60_000), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.TIMEOUT, loss.getReason());
    }

    @Test
    public void aServerThatDripsBytesFasterThanTheReadTimeoutStillHitsTheMessageDeadline() throws Exception {
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            byte[] frame = SnapcastMessageCodec.encode(chunk(1), 0, 0, 0);
            for (byte b : frame) {
                connection.send(new byte[]{b});
                Thread.sleep(40); // pacing of the attacker, not a synchronisation of the test
            }
            connection.awaitClientClose();
        }, quick().readTimeoutMs(1_000).messageDeadlineMs(300).idleTimeoutMs(60_000), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.TIMEOUT, loss.getReason());
    }

    @Test
    public void aServerThatNeverCompletesTheHandshakeIsGivenUp() throws Exception {
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            connection.readFrame();
            connection.awaitClientClose();
        }, quick().handshakeTimeoutMs(200), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.TIMEOUT, loss.getReason());
    }

    @Test
    public void aServerThatGoesSilentAfterTheHandshakeIsGivenUp() throws Exception {
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            connection.awaitClientClose();
        }, quick().idleTimeoutMs(300), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.TIMEOUT, loss.getReason());
    }

    @Test
    public void aLongRunOfFramesOfUnknownTypesDropsTheConnectionButAFewAreSkipped() throws Exception {
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            for (int i = 0; i < 500; i++) {
                connection.send(Bytes.frame(6, Bytes.of(1, 2, 3, 4)));
            }
            connection.awaitClientClose();
        }, quick(), new ManualScheduler());
        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, loss.getReason());

        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int i = 0; i < 50; i++) {
                connection.send(Bytes.frame(6, Bytes.of(1, 2, 3, 4)));
            }
            connection.send(chunk(9));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), new ManualScheduler(), recorder).start();
        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        assertEquals(9, recorder.next(WireChunkMessage.class).getTimestampSec());
    }

    @Test
    public void settingsThatAreNotJsonDropTheConnection() throws Exception {
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            connection.readFrame();
            connection.send(Bytes.frame(3, Bytes.jsonBody("this is not json")));
            connection.awaitClientClose();
        }, quick(), new ManualScheduler());

        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, loss.getReason());
    }

    @Test
    public void aCodecThatIsNotInTheAllowListEndsTheConnectionForGoodWithATypedReason() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> {
            connection.readFrame();
            connection.send(SETTINGS);
            connection.send(Bytes.frame(1, Bytes.concat(Bytes.lengthPrefixed(Bytes.ascii("opus")),
                    Bytes.lengthPrefixed(Bytes.of(0x4f, 0x50, 0x55, 0x53, 0x80, 0xbb, 0, 0, 16, 0, 2, 0)))));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();

        recorder.next(ServerSettingsMessage.class);
        SnapcastConnection.Loss lost = recorder.next(SnapcastConnection.Loss.class);
        GaveUp gaveUp = recorder.next(GaveUp.class);
        assertEquals(SnapcastConnection.LossReason.UNSUPPORTED_CODEC, lost.getReason());
        assertTrue(lost.isPermanent());
        assertEquals(SnapcastConnection.LossReason.UNSUPPORTED_CODEC, gaveUp.loss.getReason());
        assertEquals(SnapcastConnection.State.GAVE_UP, connection.getState());
        assertEquals("a retry would meet the same codec", 0, scheduler.pending());
    }

    @Test
    public void aServerThatWantsAuthorizationIsRefusedWithATypedReasonAndNotRetried() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> {
            connection.readFrame();
            connection.send(new ErrorMessage(401, "Unauthorized", "send\nBasic auth"));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();

        SnapcastConnection.Loss lost = recorder.next(SnapcastConnection.Loss.class);
        recorder.next(GaveUp.class);
        assertEquals(SnapcastConnection.LossReason.UNAUTHORIZED, lost.getReason());
        assertTrue(lost.isPermanent());
        assertFalse(lost.getDetail().contains("\n"));
        assertEquals(SnapcastConnection.State.GAVE_UP, connection.getState());
        assertEquals(0, scheduler.pending());
    }

    @Test
    public void anotherErrorOfTheServerIsALossThatIsRetried() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            connection.readFrame();
            connection.send(new ErrorMessage(500, "Internal", "oops"));
            connection.awaitClientClose();
        }, quick(), scheduler);

        assertEquals(SnapcastConnection.LossReason.SERVER_ERROR, loss.getReason());
        assertFalse(loss.isPermanent());
        assertEquals(1, scheduler.pending());
    }

    private static String describe(List<Object> events) {
        StringBuilder text = new StringBuilder();
        for (Object event : events) {
            text.append(event.getClass().getSimpleName());
            if (event instanceof SnapcastConnection.Loss) {
                text.append('(').append(((SnapcastConnection.Loss) event).getReason()).append(')');
            }
            text.append(' ');
        }
        return text.toString();
    }

    /**
     * The server sends {@code total} chunks of 2000 bytes at full speed, the listener is stuck on the first one until
     * the connection has read every frame. Returns {delivered, dropped}.
     */
    private int[] flood(SnapcastConnection.Config config, final int total) throws Exception {
        final CountDownLatch allSent = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            byte[] payload = new byte[2000];
            for (int i = 0; i < total; i++) {
                connection.send(new WireChunkMessage(i, 0, payload), 0, 0, 0);
            }
            allSent.countDown();
            finish.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            connection.shutdownOutput(); // a close with the client's time requests unread would reset the connection
            connection.awaitClientClose();
        });
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blocked = new CountDownLatch(1);
        final List<Integer> delivered = java.util.Collections.synchronizedList(new ArrayList<Integer>());
        Recorder recorder = new Recorder() {
            @Override
            public void onChunk(WireChunkMessage chunk) {
                blocked.countDown();
                try {
                    // only the test (or close(), which interrupts) ends the wait: a timeout under load would let more chunks in
                    release.await(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.add(chunk.getTimestampSec());
            }
        };
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, MicroClock.monotonic(),
                new ManualScheduler(), recorder);

        connection.start();

        assertTrue(blocked.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        boolean readAll = connection.awaitFramesRead(total + 2, ConnectionFixtures.WAIT_SECONDS * 1000);
        assertTrue("the socket thread kept reading while the listener was stuck; state " + connection.getState()
                + ", dropped " + connection.getDroppedChunks() + ", events " + describe(recorder.drain()), readAll);
        assertTrue(allSent.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        finish.countDown();
        release.countDown();
        recorder.nextOf(SnapcastConnection.Loss.class); // the server ends the stream; the event comes after the last chunk
        assertEquals(total, delivered.size() + connection.getDroppedChunks());
        return new int[]{delivered.size(), (int) connection.getDroppedChunks()};
    }

    @Test
    public void aFloodOfChunksIsReadAtFullSpeedAndOnlyABoundedNumberOfThemWaitsForASlowListener() throws Exception {
        int[] result = flood(quick().maxPendingChunks(64).maxPendingChunkBytes(1024 * 1024).maxFramesPerSecond(1_000_000), 3000);

        assertEquals("64 admitted: one inside the stuck callback and 63 waiting", 64, result[0]);
        assertEquals(3000 - 64, result[1]);
    }

    @Test
    public void theBytesWaitingForASlowListenerAreBoundedToo() throws Exception {
        int[] result = flood(quick().maxPendingChunks(1000).maxPendingChunkBytes(10_000).maxFramesPerSecond(1_000_000), 3000);

        assertEquals("five chunks of 2000 bytes fit into 10 000", 5, result[0]);
        assertEquals(3000 - 5, result[1]);
    }

    // ---- floods of small frames (security review W1)

    @Test
    public void unknownFramesAreNotForgivenByUnsolicitedTimeAnswers() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int round = 0; round < 100; round++) {
                for (int i = 0; i < 99; i++) {
                    connection.send(Bytes.frame(9, new byte[0]));
                }
                connection.send(TimeMessage.ofMicros(0), 0, 4242, 0); // nobody asked
            }
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();

        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, recorder.nextOf(SnapcastConnection.Loss.class).getReason());
        assertTrue("frames handled " + connection.framesHandled(), connection.framesHandled() <= 2 + 100);
    }

    @Test
    public void aFloodOfValidChunksEndsAtTheFrameBudgetOfTheSecond() throws Exception {
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int i = 0; i < 5000; i++) {
                connection.send(new WireChunkMessage(i, 0, new byte[1]), 0, 0, 0);
            }
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        // the clock stands still: all frames fall into one window, whatever the speed of the machine
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();

        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, recorder.nextOf(SnapcastConnection.Loss.class).getReason());
        assertTrue("frames handled " + connection.framesHandled(), connection.framesHandled() <= 2 + 2000);
    }

    @Test
    public void aServerAtTheFastestSensibleRateIsNeverCutOffByTheBudget() throws Exception {
        // a chunk every 20 ms and a Time answer every second, for a minute of the injected clock: 3000 chunks, but never
        // more than about 50 frames in a second
        final ManualClock clock = new ManualClock(10_000_000L);
        final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(0);
        FakeServer server = serve(connection -> {
            handshake(connection);
            Thread answers = new Thread(() -> {
                try {
                    while (true) {
                        Frame request = connection.readTimeRequest();
                        long serverNow = clock.nowMicros() + OFFSET;
                        connection.send(TimeMessage.ofMicros(serverNow - request.getHeader().sentMicros()), 0,
                                request.getHeader().getId(), serverNow);
                    }
                } catch (Exception endOfTest) {
                    // the connection is gone
                }
            }, "fake-time-answers");
            answers.setDaemon(true);
            answers.start();
            for (int i = 0; ; i++) {
                permits.acquire();
                connection.send(chunk(i));
            }
        });
        Recorder recorder = new Recorder();
        SnapcastConnection.Config config = quick().timeSyncBurstCount(1).timeSyncIntervalMs(1000);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, new ManualScheduler(), recorder);

        connection.start();
        recorder.nextOf(CodecHeaderMessage.class);
        for (int i = 0; i < 3000; i++) {
            clock.advanceMillis(20);
            permits.release();
            assertEquals(i, recorder.nextOf(WireChunkMessage.class).getTimestampSec());
        }

        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
        for (Object event : recorder.drain()) {
            assertFalse("no loss: " + event, event instanceof SnapcastConnection.Loss);
        }
    }

    @Test
    public void framesOfTypesAServerNeverSendsAreSkippedWithoutParsingThem() throws Exception {
        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.send(Bytes.frame(5, Bytes.jsonBody("this is not json")));
            connection.send(Bytes.frame(7, Bytes.jsonBody("[[[")));
            connection.send(chunk(5));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();

        assertEquals(5, recorder.nextOf(WireChunkMessage.class).getTimestampSec());
        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
    }

    @Test
    public void aChunkLargerThanTheBacklogBudgetCouldEverBeDropsTheConnection() throws Exception {
        SnapcastConnection.Config config = quick().maxPendingChunkBytes(10_000);
        SnapcastConnection.Loss loss = lostAfterHostile(connection -> {
            handshake(connection);
            connection.send(new WireChunkMessage(1, 0, new byte[20_000]), 0, 0, 0);
            connection.awaitClientClose();
        }, config, new ManualScheduler());
        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, loss.getReason());

        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.send(new WireChunkMessage(2, 0, new byte[9_000]), 0, 0, 0);
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        connect(new SnapcastTransportHolder(server, 0), config, new ManualClock(1_000_000), new ManualScheduler(), recorder).start();
        assertEquals(2, recorder.nextOf(WireChunkMessage.class).getTimestampSec());
    }

    @Test
    public void headersOneByOneToAListenerThatKeepsUpAreAllDeliveredWhateverTheirNumber() throws Exception {
        final java.util.concurrent.Semaphore heard = new java.util.concurrent.Semaphore(0);
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int i = 0; i < 20; i++) {
                heard.acquire();
                connection.send(i % 2 == 0 ? CodecHeaderMessage.of(Codec.FLAC, Bytes.FLAC_48000_STEREO_16) : pcmHeader());
            }
            connection.awaitClientClose();
        });
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        Recorder recorder = new Recorder() {
            @Override
            public void onCodecHeader(CodecHeaderMessage header) {
                count.incrementAndGet();
                super.onCodecHeader(header);
                heard.release();
            }
        };
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();
        for (int i = 0; i < 21; i++) {
            recorder.nextOf(CodecHeaderMessage.class);
        }

        assertEquals(21, count.get());
        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
    }

    // ---- writes cannot hold anybody (security review S57)

    @Test
    public void sendClientInfoNeverBlocksEvenWhenTheServerStoppedReading() throws Exception {
        final CountDownLatch endOfTest = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            endOfTest.await(2, TimeUnit.MINUTES); // reads nothing more: the client's send buffer fills up
        });
        Recorder recorder = new Recorder();
        final SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);
        connection.start();
        recorder.nextOf(CodecHeaderMessage.class);
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        toClose.add(caller::shutdownNow);
        try {
            java.util.concurrent.Future<?> calls = caller.submit(() -> {
                for (int i = 0; i < 400_000; i++) {
                    connection.sendClientInfo(i % 101, i % 2 == 0);
                }
            });

            calls.get(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS); // a blocked caller times out here
        } finally {
            endOfTest.countDown();
        }
    }

    /** A connection whose input is fed by the test and whose output blocks until the connection is closed. */
    private static final class StalledWriteTransport implements SnapcastTransport {
        final java.util.concurrent.BlockingQueue<byte[]> incoming = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.Semaphore reads = new java.util.concurrent.Semaphore(0);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile RuntimeException writeFailure;

        @Override
        public Connection open(int connectTimeoutMillis) {
            return new Connection() {
                private volatile int timeoutMillis = 1000;
                private byte[] current = new byte[0];
                private int position;

                @Override
                public java.io.InputStream getInputStream() {
                    return new java.io.InputStream() {
                        @Override
                        public int read() throws IOException {
                            byte[] one = new byte[1];
                            int n = read(one, 0, 1);
                            return n < 0 ? -1 : one[0] & 0xff;
                        }

                        @Override
                        public int read(byte[] buffer, int offset, int length) throws IOException {
                            reads.release();
                            try {
                                while (position >= current.length) {
                                    byte[] next = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                                    if (closed.getCount() == 0) {
                                        throw new IOException("closed");
                                    }
                                    if (next == null) {
                                        throw new java.net.SocketTimeoutException("scripted");
                                    }
                                    current = next;
                                    position = 0;
                                }
                            } catch (InterruptedException e) {
                                throw new IOException("interrupted");
                            }
                            int count = Math.min(length, current.length - position);
                            System.arraycopy(current, position, buffer, offset, count);
                            position += count;
                            return count;
                        }
                    };
                }

                @Override
                public java.io.OutputStream getOutputStream() {
                    return new java.io.OutputStream() {
                        @Override
                        public void write(int b) throws IOException {
                            write(new byte[]{(byte) b}, 0, 1);
                        }

                        @Override
                        public void write(byte[] buffer, int offset, int length) throws IOException {
                            if (writeFailure != null) {
                                throw writeFailure;
                            }
                            try {
                                closed.await(); // the server never reads: a write never finishes
                            } catch (InterruptedException e) {
                                // ends the write
                            }
                            throw new IOException("closed");
                        }
                    };
                }

                @Override
                public void setReadTimeout(int millis) {
                    timeoutMillis = millis;
                }

                @Override
                public java.net.InetAddress getPeerAddress() {
                    return ConnectionFixtures.loopback();
                }

                @Override
                public void close() {
                    closed.countDown();
                    incoming.add(new byte[0]);
                }
            };
        }
    }

    @Test
    public void aWriteThatNeverFinishesDoesNotStopTheReaderAndEndsTheConnectionOnceTheQueueIsFull() throws Exception {
        ManualClock clock = new ManualClock(10_000_000L);
        StalledWriteTransport transport = new StalledWriteTransport();
        transport.incoming.add(Bytes.concat(SnapcastMessageCodec.encode(SETTINGS, 1, 0, 0), SnapcastMessageCodec.encode(pcmHeader(), 2, 0, 0)));
        ManualScheduler scheduler = new ManualScheduler();
        Recorder recorder = new Recorder();
        // the idle limit is out of the way: only the full write queue may end this connection
        SnapcastConnection.Config config = quick().timeSyncBurstCount(10_000).timeSyncBurstIntervalMs(100)
                .idleTimeoutMs(600_000).handshakeTimeoutMs(600_000);
        SnapcastConnection connection = new SnapcastConnection(transport, HELLO, config, clock, scheduler, null);
        connection.addListener(recorder);
        toClose.add(connection);

        connection.start();
        // the Hello is stuck in the write; the handshake still goes through because the reader does not write
        recorder.nextOf(CodecHeaderMessage.class);
        for (int i = 0; i < 200 && scheduler.pending() == 0; i++) {
            clock.advanceMillis(100);
            // two reads started after the advance: the loop looked at the clock (none when it gave up in that round)
            if (!transport.reads.tryAcquire(2, 5, TimeUnit.SECONDS)) {
                break;
            }
        }

        assertEquals(SnapcastConnection.LossReason.TIMEOUT, recorder.nextOf(SnapcastConnection.Loss.class).getReason());
    }

    @Test
    public void theHelloIsAlwaysTheFirstThingTheServerSeesWhateverAnotherThreadSends() throws Exception {
        final java.util.concurrent.BlockingQueue<Integer> firstTypes = new java.util.concurrent.LinkedBlockingQueue<>();
        FakeServer server = serve(connection -> {
            firstTypes.add(connection.readFrame().getHeader().getType());
            connection.awaitClientClose();
        });
        for (int round = 0; round < 150; round++) {
            final SnapcastConnection connection = new SnapcastConnection(new SnapcastTransportHolder(server, 0).transport, HELLO,
                    quick(), new ManualClock(1_000_000), new ManualScheduler(), null);
            final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            Thread sender = new Thread(() -> {
                // as early as possible: from before the connection is started until a write is accepted
                while (!stop.get() && !connection.sendClientInfo(10, false)) {
                    Thread.yield();
                }
            }, "early-client-info");
            sender.setDaemon(true);
            sender.start();
            connection.start();

            Integer first = firstTypes.poll(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            stop.set(true);
            sender.join(ConnectionFixtures.WAIT_SECONDS * 1000);
            connection.close();

            assertNotNull("round " + round, first);
            assertEquals("round " + round + ": the first message is the Hello", 5, first.intValue());
        }
    }

    @Test
    public void aWriterThatThrowsAnythingEndsTheAttemptAsATypedLossAndNeverReachesTheUncaughtHandler() throws Exception {
        final java.util.List<Throwable> uncaught = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
        try {
            StalledWriteTransport transport = new StalledWriteTransport();
            transport.writeFailure = new IllegalStateException("a bug in the output stream");
            transport.incoming.add(Bytes.concat(SnapcastMessageCodec.encode(SETTINGS, 1, 0, 0), SnapcastMessageCodec.encode(pcmHeader(), 2, 0, 0)));
            Recorder recorder = new Recorder();
            SnapcastConnection connection = new SnapcastConnection(transport, HELLO, quick(), new ManualClock(1_000_000),
                    new ManualScheduler(), null);
            connection.addListener(recorder);
            toClose.add(connection);

            connection.start();

            assertEquals(SnapcastConnection.LossReason.INTERNAL, recorder.nextOf(SnapcastConnection.Loss.class).getReason());
            assertTrue("uncaught: " + uncaught, uncaught.isEmpty());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    // ---- the target (security review S60)

    @Test
    public void forAddressRefusesTheWildcardMulticastAndBadPorts() throws Exception {
        java.net.InetAddress[] refused = {java.net.InetAddress.getByName("0.0.0.0"), java.net.InetAddress.getByName("::"),
                java.net.InetAddress.getByName("224.0.0.1"), java.net.InetAddress.getByName("ff02::1"), null};
        for (java.net.InetAddress address : refused) {
            try {
                SnapcastConnection.forAddress(address, 1704, HELLO).close();
                org.junit.Assert.fail("accepted " + address);
            } catch (IllegalArgumentException expected) {
                // not a place to send audio requests to
            }
        }
        for (int port : new int[]{0, -1, 65536}) {
            try {
                SnapcastConnection.forAddress(ConnectionFixtures.loopback(), port, HELLO).close();
                org.junit.Assert.fail("accepted port " + port);
            } catch (IllegalArgumentException expected) {
                // out of range
            }
        }
        SnapcastConnection.forAddress(ConnectionFixtures.loopback(), SnapcastConnection.DEFAULT_PORT, HELLO).close();
    }

    // ---- losing and finding the server again

    @Test
    public void aLostServerIsReportedOnceThenRetriedWithBackoffUntilItGivesUpAndRetryStartsAgain() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> {
            handshake(connection);
            // the server closes at once
        });
        Recorder recorder = new Recorder();
        SnapcastTransportHolder holder = new SnapcastTransportHolder(server, 0);
        SnapcastConnection connection = connect(holder, quick().maxFailures(5).backoffInitialMs(1000).backoffMaxMs(30_000),
                new ManualClock(1_000_000), scheduler, recorder);

        connection.start();
        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        SnapcastConnection.Loss first = recorder.next(SnapcastConnection.Loss.class);
        assertEquals(SnapcastConnection.LossReason.SERVER_CLOSED, first.getReason());

        // from now on every attempt fails to connect: no second report of the loss, only the final give up
        holder.transport.failFromNow(100);
        for (int i = 0; i < 4; i++) {
            scheduler.runNext();
        }
        GaveUp gaveUp = recorder.next(GaveUp.class);

        assertEquals(SnapcastConnection.LossReason.CONNECT_FAILED, gaveUp.loss.getReason());
        assertEquals(java.util.Arrays.asList(1000L, 2000L, 4000L, 8000L), scheduler.delays);
        assertEquals(SnapcastConnection.State.GAVE_UP, connection.getState());
        assertEquals(0, scheduler.pending());
        assertNull("the loss was reported once", recorder.events.poll());

        holder.transport.failFromNow(0);
        connection.retry();
        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        assertNotEquals(SnapcastConnection.State.GAVE_UP, connection.getState());
    }

    @Test
    public void aServerThatComesBackIsFoundAgainAndItsNextLossIsReportedAgain() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> handshake(connection));
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();
        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        assertEquals(SnapcastConnection.LossReason.SERVER_CLOSED, recorder.next(SnapcastConnection.Loss.class).getReason());

        scheduler.runNext();
        recorder.next(ServerSettingsMessage.class);
        recorder.next(CodecHeaderMessage.class);
        assertEquals(SnapcastConnection.LossReason.SERVER_CLOSED, recorder.next(SnapcastConnection.Loss.class).getReason());
        assertEquals(2, server.accepted());
    }

    @Test
    public void aServerThatNeverAnswersIsReportedLostOnceEvenThoughEveryAttemptFails() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> { });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 100), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();
        SnapcastConnection.Loss lost = recorder.next(SnapcastConnection.Loss.class);
        for (int i = 0; i < 4; i++) {
            scheduler.runNext();
        }
        GaveUp gaveUp = recorder.next(GaveUp.class);

        assertEquals(SnapcastConnection.LossReason.CONNECT_FAILED, lost.getReason());
        assertEquals(SnapcastConnection.LossReason.CONNECT_FAILED, gaveUp.loss.getReason());
        assertNull(recorder.events.poll());
    }

    @Test
    public void aHeaderTheEngineCannotPlayEndsTheConnectionForGoodWithATypedReason() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        byte[] wave8Bit = Bytes.WAVE_48000_STEREO_16.clone();
        wave8Bit[34] = 8;  // bits per sample
        wave8Bit[32] = 2;  // block align stays channels * bits / 8 = 2
        FakeServer server = serve(connection -> {
            connection.readFrame();
            connection.send(SETTINGS);
            connection.send(Bytes.frame(1, Bytes.concat(Bytes.lengthPrefixed(Bytes.ascii("pcm")), Bytes.lengthPrefixed(wave8Bit))));
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();

        recorder.next(ServerSettingsMessage.class);
        SnapcastConnection.Loss lost = recorder.next(SnapcastConnection.Loss.class);
        recorder.next(GaveUp.class);
        assertEquals(SnapcastConnection.LossReason.UNSUPPORTED_FORMAT, lost.getReason());
        assertTrue(lost.isPermanent());
        assertEquals(SnapcastConnection.State.GAVE_UP, connection.getState());
        assertEquals(0, scheduler.pending());
    }

    // ---- every kind of event is bounded for a stuck listener

    /** A recorder whose first call of a kind blocks until the test lets it go. */
    private static class StuckRecorder extends Recorder {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stuck = new CountDownLatch(1);
        final List<Object> delivered = java.util.Collections.synchronizedList(new ArrayList<Object>());
        private final java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);

        void block() {
            if (first.getAndSet(false)) {
                stuck.countDown();
                try {
                    // only the test (or close(), which interrupts) ends the wait
                    release.await(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    public void aFloodOfSettingsWithAStuckListenerKeepsTheLatestOnly() throws Exception {
        final int total = 3000;
        final CountDownLatch finish = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int i = 0; i < total; i++) {
                connection.send(new ServerSettingsMessage(i, 0, false, 50));
            }
            finish.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            connection.shutdownOutput();
            connection.awaitClientClose();
        });
        StuckRecorder recorder = new StuckRecorder() {
            @Override
            public void onServerSettings(ServerSettingsMessage settings) {
                block();
                delivered.add(settings);
            }
        };
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick().maxFramesPerSecond(1_000_000), MicroClock.monotonic(),
                new ManualScheduler(), recorder);

        connection.start();

        assertTrue(recorder.stuck.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue("the socket thread kept reading", connection.awaitFramesRead(total + 2, ConnectionFixtures.WAIT_SECONDS * 1000));
        assertEquals(SnapcastConnection.State.CONNECTED, connection.getState());
        finish.countDown();
        recorder.release.countDown();
        recorder.nextOf(SnapcastConnection.Loss.class); // after the last settings callback
        assertTrue("delivered " + recorder.delivered.size(), recorder.delivered.size() <= 2);
        assertEquals("the latest value wins", total - 1,
                ((ServerSettingsMessage) recorder.delivered.get(recorder.delivered.size() - 1)).getBufferMs());
    }

    @Test
    public void aLongRunOfOffsetUpdatesWithAStuckListenerKeepsTheLatestOnly() throws Exception {
        ManualClock clock = new ManualClock(10_000_000L);
        FakeServer server = timeServer(clock, new java.util.concurrent.LinkedBlockingQueue<Long>(), 0);
        final int requests = 40;
        StuckRecorder recorder = new StuckRecorder() {
            @Override
            public void onOffsetUpdate(long offsetMicros, long jitterMicros) {
                block();
                delivered.add(new OffsetUpdate(offsetMicros, jitterMicros));
            }
        };
        SnapcastConnection.Config config = quick().timeSyncBurstCount(1000).timeSyncBurstIntervalMs(100);
        // an executor of our own, so that a task put behind the events shows when all of them have run
        java.util.concurrent.ExecutorService events = java.util.concurrent.Executors.newSingleThreadExecutor();
        toClose.add(events::shutdownNow);
        SnapcastConnection connection = new SnapcastConnection(new SnapcastTransportHolder(server, 0).transport, HELLO, config,
                clock, new ManualScheduler(), events);
        connection.addListener(recorder);
        toClose.add(connection);

        connection.start();
        assertTrue(connection.awaitTimeSamples(1, ConnectionFixtures.WAIT_SECONDS * 1000));
        for (int n = 2; n <= requests; n++) {
            clock.advanceMillis(100);
            assertTrue(connection.awaitTimeSamples(n, ConnectionFixtures.WAIT_SECONDS * 1000));
        }
        assertTrue("the first update is stuck in the listener", recorder.stuck.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        recorder.release.countDown();
        events.submit(() -> { }).get(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS); // behind every event queued so far

        assertTrue("delivered " + recorder.delivered.size(), recorder.delivered.size() <= 2);
    }

    @Test
    public void aFloodOfCodecHeadersWithAStuckListenerDropsTheConnectionWithATypedReason() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        final CountDownLatch finish = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int i = 0; i < 40; i++) {
                connection.send(pcmHeader());
            }
            finish.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            connection.shutdownOutput();
            connection.awaitClientClose();
        });
        StuckRecorder recorder = new StuckRecorder() {
            @Override
            public void onCodecHeader(CodecHeaderMessage header) {
                block();
                delivered.add(header);
            }
        };
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);

        connection.start();

        assertTrue(recorder.stuck.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        scheduler.take(); // the connection was dropped and a retry is set up, while the listener is still stuck
        assertEquals(SnapcastConnection.State.WAITING_RETRY, connection.getState());
        finish.countDown();
        recorder.release.countDown();
        SnapcastConnection.Loss loss = recorder.nextOf(SnapcastConnection.Loss.class);
        assertEquals(SnapcastConnection.LossReason.PROTOCOL_ERROR, loss.getReason());
        assertEquals("the bound of pending headers", 8, recorder.delivered.size());
    }

    @Test
    public void codecHeadersWithinTheBoundAreAllDeliveredInOrderToASlowListener() throws Exception {
        CodecHeaderMessage flac = CodecHeaderMessage.of(Codec.FLAC, Bytes.FLAC_48000_STEREO_16);
        final CountDownLatch finish = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            for (int i = 0; i < 5; i++) {
                connection.send(i % 2 == 0 ? flac : pcmHeader());
                connection.send(chunk(i));
            }
            finish.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            connection.shutdownOutput();
            connection.awaitClientClose();
        });
        StuckRecorder recorder = new StuckRecorder() {
            @Override
            public void onCodecHeader(CodecHeaderMessage header) {
                block();
                delivered.add(header);
            }
        };
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), new ManualScheduler(), recorder);

        connection.start();

        assertTrue(recorder.stuck.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(connection.awaitFramesRead(2 + 10, ConnectionFixtures.WAIT_SECONDS * 1000));
        finish.countDown();
        recorder.release.countDown();
        recorder.nextOf(SnapcastConnection.Loss.class);
        assertEquals(java.util.Arrays.<Object>asList(pcmHeader(), flac, pcmHeader(), flac, pcmHeader(), flac), recorder.delivered);
        assertEquals(0, connection.getDroppedChunks());
    }

    // ---- the failure count and the outstanding time requests

    @Test
    public void aConnectionThatHeldLongEnoughStartsTheFailureCountAgain() throws Exception {
        ManualClock clock = new ManualClock(1_000_000);
        ManualScheduler scheduler = new ManualScheduler();
        final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch[] closeNow = {new CountDownLatch(1), new CountDownLatch(1)};
        FakeServer server = serve(connection -> {
            int mine = index.getAndIncrement();
            handshake(connection);
            closeNow[mine].await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
        });
        Recorder recorder = new Recorder();
        SnapcastConnection.Config config = quick().maxFailures(2).stableConnectionMs(5_000).idleTimeoutMs(600_000)
                .handshakeTimeoutMs(600_000);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, scheduler, recorder);

        connection.start();
        recorder.nextOf(CodecHeaderMessage.class);
        closeNow[0].countDown(); // the first connection fails at once: one failure
        recorder.nextOf(SnapcastConnection.Loss.class);
        scheduler.runNext();
        recorder.nextOf(CodecHeaderMessage.class);
        clock.advanceMillis(5_000); // the second one holds for the stable time
        closeNow[1].countDown();
        scheduler.take(); // a retry is set up: the failure was counted from zero, so one failure is below the maximum of two

        assertEquals(SnapcastConnection.State.WAITING_RETRY, connection.getState());
        assertEquals(SnapcastConnection.LossReason.SERVER_CLOSED, recorder.nextOf(SnapcastConnection.Loss.class).getReason());
    }

    @Test
    public void onlySixteenTimeRequestsStayOutstanding() throws Exception {
        ManualClock clock = new ManualClock(10_000_000L);
        final java.util.concurrent.BlockingQueue<Frame> requests = new java.util.concurrent.LinkedBlockingQueue<>();
        final CountDownLatch answerNow = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            List<Frame> seen = new ArrayList<>();
            while (seen.size() < 20) {
                Frame request = connection.readTimeRequest();
                seen.add(request);
                requests.add(request);
            }
            answerNow.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            // the oldest request was forgotten (20 sent, 16 kept): its answer is not one we are waiting for; the newest is
            for (Frame request : new Frame[]{seen.get(0), seen.get(19)}) {
                long serverNow = clock.nowMicros() + OFFSET;
                connection.send(TimeMessage.ofMicros(serverNow - request.getHeader().sentMicros()), 0,
                        request.getHeader().getId(), serverNow);
            }
            connection.awaitClientClose();
        });
        SnapcastConnection.Config config = quick().timeSyncBurstCount(1000).timeSyncBurstIntervalMs(100);
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), config, clock, new ManualScheduler(), new Recorder());

        connection.start();
        assertNotNull(requests.poll(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        for (int n = 2; n <= 20; n++) {
            clock.advanceMillis(100);
            assertNotNull(requests.poll(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        }
        answerNow.countDown();

        assertTrue(connection.awaitTimeSamples(1, ConnectionFixtures.WAIT_SECONDS * 1000));
        assertEquals(1, connection.getUnsolicitedTime());
        assertEquals(1, connection.getOffsetFilter().sampleCount());
    }

    // ---- closing

    @Test
    public void closeEndsTheThreadAndTheSocketEvenWhileTheReaderIsBlockedInARead() throws Exception {
        final CountDownLatch serverSawClose = new CountDownLatch(1);
        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.awaitClientClose();
            serverSawClose.countDown();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0),
                quick().readTimeoutMs(600_000).idleTimeoutMs(600_000).handshakeTimeoutMs(600_000), new ManualClock(1_000_000),
                new ManualScheduler(), recorder);

        connection.start();
        recorder.nextOf(CodecHeaderMessage.class);
        assertTrue(connection.isReaderRunning());

        connection.close();

        assertFalse("the reader thread is gone", connection.isReaderRunning());
        assertTrue("the socket was closed", serverSawClose.await(ConnectionFixtures.WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(SnapcastConnection.State.CLOSED, connection.getState());
        connection.close(); // twice is fine
    }

    @Test
    public void nothingIsReportedAfterCloseAndAClosedConnectionDoesNotStartAgain() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        FakeServer server = serve(connection -> {
            handshake(connection);
            connection.awaitClientClose();
        });
        Recorder recorder = new Recorder();
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(1_000_000), scheduler, recorder);
        connection.start();
        recorder.nextOf(CodecHeaderMessage.class);

        connection.close();
        connection.start();
        connection.retry();

        assertEquals(SnapcastConnection.State.CLOSED, connection.getState());
        assertEquals(0, scheduler.pending());
        assertFalse(connection.isReaderRunning());
        for (Object event : recorder.drain()) {
            assertFalse("no loss after close: " + event, event instanceof SnapcastConnection.Loss);
        }
        assertFalse(connection.sendClientInfo(1, false));
    }

    @Test
    public void closingBeforeStartIsFine() throws Exception {
        FakeServer server = serve(connection -> { });
        SnapcastConnection connection = connect(new SnapcastTransportHolder(server, 0), quick(), new ManualClock(0),
                new ManualScheduler(), new Recorder());

        connection.close();

        assertEquals(SnapcastConnection.State.CLOSED, connection.getState());
    }
}
