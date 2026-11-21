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

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.fcast.FakeFCastReceiver;
import de.yaacc.fcast.FakeFCastReceiver.Op;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * Task 2.5: the session against {@link FakeFCastReceiver}: the state is what the receiver reported, commands only set pending
 * markers, another sender's changes show, one retry after a lost connection, an idle card has no connection.
 */
public class FCastSessionTest {
    private static final long WAIT = 4_000;

    private final List<AutoCloseable> toClose = Collections.synchronizedList(new ArrayList<AutoCloseable>());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "test-session");
        thread.setDaemon(true);
        return thread;
    });

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
        executor.shutdownNow();
    }

    private static final class Events implements FCastSession.Listener {
        final List<FCastReceiverState> states = Collections.synchronizedList(new ArrayList<FCastReceiverState>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        final List<FCastMessages.PlayUpdate> playUpdates = Collections.synchronizedList(new ArrayList<FCastMessages.PlayUpdate>());
        final List<FCastMessages.Event> events = Collections.synchronizedList(new ArrayList<FCastMessages.Event>());

        @Override
        public void onStateChanged(FCastReceiverState state) {
            states.add(state);
        }

        @Override
        public void onPlaybackError(String message) {
            errors.add(message);
        }

        @Override
        public void onPlayUpdate(FCastMessages.PlayUpdate update, FCastReceiverState state) {
            playUpdates.add(update);
        }

        @Override
        public void onMediaItemEvent(FCastMessages.Event event) {
            events.add(event);
        }

        boolean sawLink(FCastReceiverState.Link link) {
            synchronized (states) {
                for (FCastReceiverState state : states) {
                    if (state.getLink() == link) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private interface Check {
        boolean test(FCastReceiverState state);
    }

    private static boolean await(FCastSession session, Check check, long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            if (check.test(session.getState())) {
                return true;
            }
            Thread.sleep(5);
        }
        return check.test(session.getState());
    }

    private static FCastConnection.Config fastConnection() {
        FCastConnection.Config config = new FCastConnection.Config();
        config.connectTimeoutMillis = 1_000;
        config.pingAfterMillis = 5_000;
        config.deadAfterMillis = 10_000;
        config.initialWaitMillis = 150;
        config.tickMillis = 20;
        return config;
    }

    private static FCastSession.Config fastSession() {
        FCastSession.Config config = new FCastSession.Config();
        config.idleCloseMillis = 300;
        config.reconnectDelayMillis = 50;
        config.stableMillis = 10_000;
        config.pendingTimeoutMillis = 300;
        return config;
    }

    /** Counts the attempts to connect. */
    private static final class CountingConnector implements FCastSession.Connector {
        final AtomicInteger attempts = new AtomicInteger();
        private final FCastSession.Connector inner;

        CountingConnector(FCastSession.Connector inner) {
            this.inner = inner;
        }

        @Override
        public FCastConnection connect(FCastConnection.Listener listener) throws IOException {
            attempts.incrementAndGet();
            return inner.connect(listener);
        }
    }

    private CountingConnector connectorTo(int port) throws IOException {
        return new CountingConnector(FCastSession.connectorFor(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port),
                fastConnection(), MonotonicClock.SYSTEM, "Test Phone", "YAACC", "9.9"));
    }

    private FakeFCastReceiver receiver(FakeFCastReceiver.Options options) throws IOException {
        FakeFCastReceiver receiver = FakeFCastReceiver.start(options);
        toClose.add(receiver);
        return receiver;
    }

    private FCastSession session(CountingConnector connector, Events events, FCastSession.Config config) {
        FCastSession session = new FCastSession(connector, events, config, MonotonicClock.SYSTEM, executor);
        toClose.add(session);
        return session;
    }

    private static FCastMessages.PlayRequest song(String url) {
        return new FCastMessages.PlayRequest("audio/mpeg", url).title("Song");
    }

    // ---- connecting on demand

    @Test
    public void thereIsNoConnectionUntilACommandNeedsOne() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        assertEquals(FCastReceiverState.Link.IDLE, session.getState().getLink());
        session.acquire();
        Thread.sleep(150);
        assertEquals("acquiring alone does not connect", 0, receiver.acceptedCount());
        assertEquals(0, connector.attempts.get());
    }

    @Test
    public void aCommandConnectsAndSendsAndTheReceiverInfoComesFromItsInitial() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().name("Living Room"));
        Events events = new Events();
        FCastSession session = session(connectorTo(receiver.port()), events, fastSession());
        session.acquire();
        session.play(song("http://192.168.1.2:8080/a.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        assertEquals("http://192.168.1.2:8080/a.mp3", receiver.received(Op.PLAY).get(0).json().getString("url"));
        assertTrue(await(session, s -> "Living Room".equals(s.getReceiverName()) && s.getVolume() != null, WAIT));
        FCastReceiverState state = session.getState();
        assertEquals(FCastReceiverState.Link.CONNECTED, state.getLink());
        assertEquals(3, state.getProtocolVersion());
        assertEquals("FakeFCast", state.getReceiverApp());
        assertEquals(1.0, state.getVolume(), 0.0);
        assertTrue(events.sawLink(FCastReceiverState.Link.CONNECTING));
    }

    @Test
    public void connectMakesTheConnectionWithoutSendingACommand() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().name("Living Room"));
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();

        session.connect();

        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.CONNECTED && s.getProtocolVersion() == 3, WAIT));
        assertEquals(1, receiver.acceptedCount());
        assertEquals("nothing was played or changed", 0, receiver.received(Op.PLAY).size());
    }

    @Test
    public void connectToAReceiverThatIsGoneEndsInFailedWithAReason() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        FCastSession session = session(connectorTo(closedPort), new Events(), fastSession());
        session.acquire();

        session.connect();

        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.FAILED && s.getLastError() != null, WAIT));
    }

    // ---- the state is what the receiver says

    @Test
    public void aCommandDoesNotChangeThePlaybackUntilTheReceiverSaysSo() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.play(song("http://h/a.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.PLAY, WAIT));
        assertEquals("still unknown: the receiver has not said", FCastReceiverState.Playback.UNKNOWN, session.getState().getPlayback());

        receiver.session(0).sendPlaybackUpdate(1, 2.5, 200.0, 1.0);
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING, WAIT));
        FCastReceiverState state = session.getState();
        assertEquals(FCastReceiverState.Pending.NONE, state.getPending());
        assertEquals(2.5, state.getPosition(), 0.0);
        assertEquals(200.0, state.getDuration(), 0.0);
    }

    @Test
    public void pauseResumeStopAreConfirmedByTheirOwnStateOnly() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession.Config config = fastSession();
        config.pendingTimeoutMillis = 10_000;
        FCastSession session = session(connectorTo(receiver.port()), new Events(), config);
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.PAUSE, WAIT));
        receiver.session(0).sendPlaybackUpdate(1, 5, 100, 1); // a playing update does not confirm a pause
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING, WAIT));
        assertEquals(FCastReceiverState.Pending.PAUSE, session.getState().getPending());
        receiver.session(0).sendPlaybackUpdate(2, 5, 100, 1);
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.NONE, WAIT));
        assertEquals(FCastReceiverState.Playback.PAUSED, session.getState().getPlayback());

        session.resume();
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.RESUME, WAIT));
        receiver.session(0).sendPlaybackUpdate(1, 6, 100, 1);
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.NONE, WAIT));

        session.stop();
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.STOP, WAIT));
        receiver.session(0).sendPlaybackUpdate(0, -1, -1, -1);
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.NONE, WAIT));
        assertEquals(FCastReceiverState.Playback.IDLE, session.getState().getPlayback());
        assertNull("an idle receiver has no position", session.getState().getPosition());
    }

    @Test
    public void aPendingMarkerEndsAfterTheTimeoutWhenTheReceiverStaysSilent() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.seek(30);
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.SEEK, WAIT));
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.NONE, WAIT));
        assertEquals(0.0, 30.0 - Double.parseDouble(receiver.received(Op.SEEK).get(0).json().get("time").toString()), 0.0);
    }

    @Test
    public void theVolumeFieldChangesOnlyWhenTheReceiverReportsIt() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.setVolume(0.3);
        assertTrue(receiver.awaitPackets(Op.SET_VOLUME, 1, WAIT));
        assertEquals(0.3, receiver.received(Op.SET_VOLUME).get(0).json().getDouble("volume"), 0.0001);
        assertTrue(await(session, s -> s.getVolume() != null, WAIT));
        assertEquals("the receiver's own volume, not ours", 1.0, session.getState().getVolume(), 0.0);
        receiver.session(0).sendVolumeUpdate(0.3);
        assertTrue(await(session, s -> s.getVolume() != null && Math.abs(s.getVolume() - 0.3) < 0.0001, WAIT));
    }

    @Test
    public void anItemOfAnotherSenderIsFlaggedAndOursIsNot() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Events events = new Events();
        FCastSession session = session(connectorTo(receiver.port()), events, fastSession());
        session.acquire();
        session.play(song("http://h/ours.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        receiver.session(0).sendPlayUpdate("audio/mpeg", "http://h/ours.mp3");
        assertTrue(await(session, s -> "http://h/ours.mp3".equals(s.getItemUrl()), WAIT));
        assertFalse(session.getState().isItemFromElsewhere());
        assertEquals("the pending Play ended with the PlayUpdate", FCastReceiverState.Pending.NONE, session.getState().getPending());

        receiver.session(0).sendPlayUpdate("video/mp4", "http://other/movie.mp4");
        assertTrue(await(session, s -> "http://other/movie.mp4".equals(s.getItemUrl()), WAIT));
        assertTrue(session.getState().isItemFromElsewhere());
        assertEquals(2, events.playUpdates.size());
        assertEquals("the session does not fight another sender: no command was sent", 1, receiver.received(Op.PLAY).size());
    }

    @Test
    public void ourOwnPlayClaimsTheItemAfterAnotherSendersItem() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.play(song("http://h/first.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        receiver.session(0).sendPlayUpdate("audio/mpeg", "http://h/first.mp3"); // the answer to our first Play
        assertTrue(await(session, s -> "http://h/first.mp3".equals(s.getItemUrl()), WAIT));
        receiver.session(0).sendPlayUpdate("video/mp4", "http://other/movie.mp4");
        assertTrue(await(session, s -> s.isItemFromElsewhere(), WAIT));

        session.play(song("http://h/ours.mp3"));

        assertTrue(receiver.awaitPackets(Op.PLAY, 2, WAIT));
        assertTrue("the answers to our Play are not foreign", await(session, s -> !s.isItemFromElsewhere(), WAIT));
        assertEquals("http://h/ours.mp3", session.getState().getItemUrl());
    }

    @Test
    public void theFirstPlayUpdateAfterOurPlayIsOursWhateverFormOfTheUrlItReports() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.play(song("http://h/ours.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));

        receiver.session(0).sendPlayUpdate("audio/mpeg", "http://H:80/ours.mp3/"); // another form of our URL

        assertTrue(await(session, s -> "http://H:80/ours.mp3/".equals(s.getItemUrl()), WAIT));
        assertFalse("the answer to our Play is ours", session.getState().isItemFromElsewhere());

        receiver.session(0).sendPlayUpdate("video/mp4", "http://other/movie.mp4"); // no Play of ours is pending any more
        assertTrue(await(session, s -> s.isItemFromElsewhere(), WAIT));
    }

    @Test
    public void aPlaybackErrorIsShownOnceAndEndsThePendingPlay() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Events events = new Events();
        FCastSession.Config config = fastSession();
        config.pendingTimeoutMillis = 10_000;
        FCastSession session = session(connectorTo(receiver.port()), events, config);
        session.acquire();
        session.play(song("http://h/a.mp3"));
        assertTrue(await(session, s -> s.getPending() == FCastReceiverState.Pending.PLAY, WAIT));
        int serial = session.getState().getErrorSerial();
        receiver.session(0).sendPlaybackError("unsupported codec");
        assertTrue(await(session, s -> "unsupported codec".equals(s.getLastError()), WAIT));
        assertEquals(FCastReceiverState.Pending.NONE, session.getState().getPending());
        assertEquals(serial + 1, session.getState().getErrorSerial());
        assertEquals(1, events.errors.size());
        assertEquals("unsupported codec", events.errors.get(0));
    }

    @Test
    public void aMediaItemEventIsPassedOn() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Events events = new Events();
        FCastSession session = session(connectorTo(receiver.port()), events, fastSession());
        session.acquire();
        session.subscribe(FCastMessages.EVENT_MEDIA_ITEM_END);
        assertTrue(receiver.awaitPackets(Op.SUBSCRIBE_EVENT, 1, WAIT));
        receiver.session(0).send(Op.EVENT, "{\"generationTime\":1,\"event\":{\"type\":1,\"item\":{\"url\":\"http://h/a.mp3\"}}}");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (events.events.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(1, events.events.size());
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_END, events.events.get(0).getType());
    }

    // ---- errors

    @Test
    public void aReceiverThatCanNotBeReachedFailsWithATextAndTheNextCommandTriesAgain() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            port = socket.getLocalPort();
        }
        CountingConnector connector = connectorTo(port);
        FCastSession session = session(connector, new Events(), fastSession());
        session.play(song("http://h/a.mp3"));
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.FAILED, WAIT));
        assertTrue(session.getState().getLastError().startsWith("could not connect"));
        session.pause();
        assertTrue(await(session, s -> s.getErrorSerial() >= 2, WAIT));
        assertEquals("every command tries once", 2, connector.attempts.get());
    }

    @Test
    public void aCommandTheVersionDoesNotHaveIsRefusedWithAText() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options().greeting(1).speaks(1));
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        assertTrue(await(session, s -> s.getProtocolVersion() == 1, WAIT));
        session.setSpeed(2);
        assertTrue(await(session, s -> s.getLastError() != null, WAIT));
        assertTrue(session.getState().getLastError(), session.getState().getLastError().contains("protocol version 1"));
        assertEquals(0, receiver.received(Op.SET_SPEED).size());
        assertEquals(FCastReceiverState.Link.CONNECTED, session.getState().getLink());
    }

    @Test
    public void aMessageThatCanNotBeBuiltDoesNotMakeAConnection() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.play(new FCastMessages.PlayRequest("", "http://h/a.mp3"));
        assertTrue(await(session, s -> s.getLastError() != null, WAIT));
        assertEquals(0, connector.attempts.get());
        session.setVolume(Double.NaN);
        assertTrue(await(session, s -> s.getErrorSerial() >= 2, WAIT));
        assertEquals(0, connector.attempts.get());
    }

    // ---- losing the connection

    @Test
    public void aLostConnectionIsRetriedOnceWhileSomebodyUsesTheReceiver() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        Events events = new Events();
        FCastSession session = session(connector, events, fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        receiver.closeConnection(0);
        assertTrue(await(session, s -> events.sawLink(FCastReceiverState.Link.RECONNECTING) && s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        assertEquals(2, connector.attempts.get());
        long acceptEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (receiver.acceptedCount() < 2 && System.nanoTime() < acceptEnd) {
            Thread.sleep(5);
        }
        assertEquals(2, receiver.acceptedCount());
        assertEquals("the playback state is not trusted across a connection", FCastReceiverState.Playback.UNKNOWN, session.getState().getPlayback());

        // a second loss soon after is not retried again
        receiver.closeConnection(1);
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.FAILED, WAIT));
        assertNotNull(session.getState().getLastError());
        Thread.sleep(300);
        assertEquals("no endless loop", 2, connector.attempts.get());
    }

    @Test
    public void aRetryThatFailsEndsInFailedWithoutAThirdTry() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        receiver.close(); // gone for good
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.FAILED, WAIT));
        Thread.sleep(300);
        assertEquals(2, connector.attempts.get());
        assertTrue(session.getState().getLastError().contains("could not"));
    }

    @Test
    public void aConnectionThatWasStableMayBeRetriedAgain() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession.Config config = fastSession();
        config.stableMillis = 150;
        FCastSession session = session(connector, new Events(), config);
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        receiver.closeConnection(0);
        assertTrue(await(session, s -> connector.attempts.get() == 2 && s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        Thread.sleep(400); // longer than stableMillis
        receiver.closeConnection(1);
        assertTrue(await(session, s -> connector.attempts.get() == 3 && s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
    }

    @Test
    public void aLostConnectionWithNobodyUsingTheReceiverJustGoesIdle() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.pause(); // a command without a user
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        receiver.closeConnection(0);
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.IDLE, WAIT));
        Thread.sleep(300);
        assertEquals(1, connector.attempts.get());
        assertNull(session.getState().getLastError());
    }

    // ---- idle

    @Test
    public void withoutAUserTheConnectionClosesAfterTheIdleTimeAndTheNextCommandOpensANewOne() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.setVolume(0.5);
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        assertTrue("closed by the idle timer", await(session, s -> s.getLink() == FCastReceiverState.Link.IDLE, WAIT));
        assertEquals(1, connector.attempts.get());
        session.setVolume(0.6);
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        assertEquals(2, connector.attempts.get());
    }

    @Test
    public void aUserKeepsTheConnectionOpenAndTheIdleTimeStartsAtTheRelease() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.setVolume(0.5);
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        Thread.sleep(700); // more than twice the idle time
        assertEquals(FCastReceiverState.Link.CONNECTED, session.getState().getLink());
        long released = System.nanoTime();
        session.release();
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.IDLE, WAIT));
        assertTrue("not before the idle time", System.nanoTime() - released >= TimeUnit.MILLISECONDS.toNanos(250));
    }

    @Test
    public void activityKeepsAnUnusedConnectionOpenForTheIdleTimeAfterTheLastCommand() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        for (int i = 0; i < 5; i++) {
            session.setVolume(0.1 * i);
            Thread.sleep(120); // less than the idle time of 300 ms, so the connection stays
        }
        assertEquals(FCastReceiverState.Link.CONNECTED, session.getState().getLink());
        assertEquals(1, connector.attempts.get());
    }

    // ---- close

    @Test
    public void closeEndsTheConnectionAndLaterCommandsDoNothing() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        session.close();
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.IDLE, WAIT));
        session.play(song("http://h/a.mp3"));
        Thread.sleep(200);
        assertEquals(1, connector.attempts.get());
        assertEquals(0, receiver.received(Op.PLAY).size());
    }

    // ---- races and leaks (review of Group 2)

    @Test
    public void closeWhileTheConnectionIsBeingMadeLeavesNoConnectionBehind() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        final CountingConnector inner = connectorTo(receiver.port());
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final List<FCastConnection> made = Collections.synchronizedList(new ArrayList<FCastConnection>());
        FCastSession.Connector blocking = listener -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            FCastConnection connection = inner.connect(listener);
            made.add(connection);
            return connection;
        };
        FCastSession session = new FCastSession(blocking, new Events(), fastSession(), MonotonicClock.SYSTEM, executor);
        toClose.add(session);
        session.acquire();
        session.pause();
        assertTrue(entered.await(WAIT, TimeUnit.MILLISECONDS));
        session.close();      // the connect is still blocked
        release.countDown();  // now it returns a live connection to a session that is closed
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (made.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(1, made.size());
        assertTrue("the connection was closed, not left open", made.get(0).awaitTermination(WAIT));
        assertTrue(made.get(0).isClosed());
        assertEquals(FCastReceiverState.Link.IDLE, session.getState().getLink());
        assertEquals("nothing was sent", 0, receiver.received(Op.PAUSE).size());
    }

    @Test
    public void theCloseEventOfAnOldConnectionDoesNotTouchTheNewOne() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        // the session's thread is busy; a command waits behind it, then the first connection closes: the command finds a closed
        // connection and makes a second one, and only after that the close event of the first one reaches the session
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        session.pause();
        receiver.closeConnection(0);
        Thread.sleep(300);
        gate.countDown();
        assertTrue(await(session, s -> connector.attempts.get() == 2 && s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        Thread.sleep(300);
        assertEquals("the stale close event did not open a third connection", 2, connector.attempts.get());
        assertEquals(FCastReceiverState.Link.CONNECTED, session.getState().getLink());
        assertEquals(2, receiver.acceptedCount());
    }

    // ---- the end of an item (task 2.6)

    @Test
    public void aVersion3ReceiverIsSubscribedToTheEndEventOnceAndAnOlderOneNot() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.setVolume(0.5);
        assertTrue(receiver.awaitPackets(Op.SUBSCRIBE_EVENT, 1, WAIT));
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_END, receiver.received(Op.SUBSCRIBE_EVENT).get(0).json().getJSONObject("event").getInt("type"));
        Thread.sleep(200);
        assertEquals("once", 1, receiver.received(Op.SUBSCRIBE_EVENT).size());

        FakeFCastReceiver old = receiver(new FakeFCastReceiver.Options().greeting(2).speaks(2));
        FCastSession oldSession = session(connectorTo(old.port()), new Events(), fastSession());
        oldSession.acquire();
        oldSession.setVolume(0.5);
        assertTrue(old.awaitPackets(Op.SET_VOLUME, 1, WAIT));
        Thread.sleep(300);
        assertEquals("a version 2 receiver has no events", 0, old.received(Op.SUBSCRIBE_EVENT).size());
    }

    @Test
    public void theRealSequenceOfAReceiverEndsWithAPausedUpdateAtTheDurationAndTheEvent() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Events events = new Events();
        FCastSession session = session(connectorTo(receiver.port()), events, fastSession());
        session.acquire();
        session.play(song("http://h/a.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        FakeFCastReceiver.Session wire = receiver.session(0);
        wire.sendPlaybackUpdate(1, 0.0, 30.5, 1.0);   // playing at once, no idle first
        wire.sendPlaybackUpdate(1, 29.9, 30.5, 1.0);
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING && s.getPosition() != null && s.getPosition() > 29, WAIT));
        assertFalse(session.getState().hasEnded());
        wire.sendPlaybackUpdate(2, 30.5, 30.5, 1.0);  // the end, as both reference receivers report it
        assertTrue(await(session, s -> s.hasEnded(), WAIT));
        wire.send(Op.EVENT, "{\"generationTime\":1,\"event\":{\"type\":1}}");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (events.events.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(1, events.events.size());
        // a stop afterwards: all zeros as the desktop receiver sends them, written by hand so that the speed 0 is really on the wire
        wire.send(Op.PLAYBACK_UPDATE, "{\"generationTime\":1,\"state\":0,\"time\":0.0,\"duration\":0.0,\"speed\":0.0}");
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.IDLE, WAIT));
        assertFalse(session.getState().hasEnded());
        assertNull("an idle update keeps no speed", session.getState().getSpeed());
    }

    @Test
    public void theDesktopReceiversSequenceEndsWithOnlyTheEventAndTheStateIsNearTheEnd() throws Exception {
        // what the owner's real run showed (receiver 3.0.4): updates every 0.5 s up to 30.425 of 30.527 s, then the event, no paused update
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        Events events = new Events();
        FCastSession session = session(connectorTo(receiver.port()), events, fastSession());
        session.acquire();
        session.play(song("http://h/a.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        FakeFCastReceiver.Session wire = receiver.session(0);
        wire.sendPlayUpdate("audio/mpeg", "http://h/a.mp3");
        wire.send(Op.EVENT, "{\"generationTime\":1,\"event\":{\"type\":0,\"item\":{\"container\":\"audio/mpeg\",\"url\":\"http://h/a.mp3\"}}}");
        wire.sendPlaybackUpdate(1, 0.0, 30.526666666, 1.0);
        wire.sendPlaybackUpdate(1, 30.425013848, 30.526666666, 1.0);
        assertTrue(await(session, s -> s.getPosition() != null && s.getPosition() > 30, WAIT));
        assertTrue(session.getState().isNearEnd());
        assertFalse("no paused update came, so this is not hasEnded()", session.getState().hasEnded());
        wire.send(Op.EVENT, "{\"generationTime\":2,\"event\":{\"type\":1,\"item\":{\"container\":\"audio/mpeg\",\"url\":\"http://h/a.mp3\"}}}");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (events.events.size() < 2 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(2, events.events.size());
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_START, events.events.get(0).getType());
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_END, events.events.get(1).getType());
        assertEquals("http://h/a.mp3", events.events.get(1).getItemUrl());
    }

    @Test
    public void aFailedRetryIsOneErrorNotTwo() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        int before = session.getState().getErrorSerial();
        receiver.close();
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.FAILED, WAIT));
        Thread.sleep(200);
        assertEquals(before + 1, session.getState().getErrorSerial());
    }

    // ---- review of Group 2, cycle 2

    @Test
    public void aNewItemDoesNotInheritTheEndOfTheOldOne() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.play(song("http://h/a.mp3"));
        assertTrue(receiver.awaitPackets(Op.PLAY, 1, WAIT));
        FakeFCastReceiver.Session wire = receiver.session(0);
        wire.sendPlayUpdate("audio/mpeg", "http://h/a.mp3");
        wire.sendPlaybackUpdate(2, 30.5, 30.5, 1.0);   // an Android style end
        assertTrue(await(session, s -> s.hasEnded(), WAIT));
        assertTrue(session.getState().getPlaybackUpdatedAt() > 0);

        wire.sendPlayUpdate("audio/mpeg", "http://h/a.mp3"); // the same item again (repeat one) must reset too
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.UNKNOWN, WAIT));
        FCastReceiverState fresh = session.getState();
        assertFalse(fresh.hasEnded());
        assertFalse(fresh.isNearEnd());
        assertNull(fresh.getPosition());
        assertNull(fresh.getDuration());

        wire.sendPlayUpdate("audio/mpeg", "http://h/b.mp3");
        wire.sendPlaybackUpdate(1, 0.5, 40.0, 1.0);
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING, WAIT));
        assertEquals("http://h/b.mp3", session.getState().getItemUrl());
        assertEquals(40.0, session.getState().getDuration(), 0.0);
    }

    @Test
    public void aMessageOfAnOldConnectionIsNotAppliedToTheNewOne() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        Events events = new Events();
        FCastSession session = session(connector, events, fastSession());
        session.acquire();
        session.pause();
        assertTrue(receiver.awaitPackets(Op.PAUSE, 1, WAIT));
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        session.pause(); // waits behind the gate and makes the second connection when it runs
        FakeFCastReceiver.Session old = receiver.session(0);
        old.sendPlaybackUpdate(1, 9.0, 30.0, 1.0);                                        // queued behind the command
        old.send(Op.EVENT, "{\"generationTime\":1,\"event\":{\"type\":1,\"item\":{\"url\":\"http://h/old.mp3\"}}}");
        Thread.sleep(300);
        receiver.closeConnection(0);
        Thread.sleep(300);
        gate.countDown();
        assertTrue(await(session, s -> connector.attempts.get() == 2 && s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        Thread.sleep(300);
        assertEquals("what the old connection said is not what the new receiver said", FCastReceiverState.Playback.UNKNOWN,
                session.getState().getPlayback());
        assertEquals("an event of the old connection does not reach the player", 0, events.events.size());
    }

    @Test
    public void theEndEventIsSubscribedAgainAfterAReconnect() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        CountingConnector connector = connectorTo(receiver.port());
        FCastSession session = session(connector, new Events(), fastSession());
        session.acquire();
        session.setVolume(0.5);
        assertTrue(receiver.awaitPackets(Op.SUBSCRIBE_EVENT, 1, WAIT));
        receiver.closeConnection(0);
        assertTrue(await(session, s -> connector.attempts.get() == 2 && s.getLink() == FCastReceiverState.Link.CONNECTED, WAIT));
        assertTrue("the desktop receiver's only end signal must be asked for again on the new connection",
                receiver.awaitPackets(Op.SUBSCRIBE_EVENT, 2, WAIT));
    }

    @Test
    public void aStoppedReceiverShowsNoSpeedNotTheClampedZero() throws Exception {
        FakeFCastReceiver receiver = receiver(new FakeFCastReceiver.Options());
        FCastSession session = session(connectorTo(receiver.port()), new Events(), fastSession());
        session.acquire();
        session.stop();
        assertTrue(receiver.awaitPackets(Op.STOP, 1, WAIT));
        receiver.session(0).sendPlaybackUpdate(1, 5, 30, 1.0);
        assertTrue(await(session, s -> s.getSpeed() != null, WAIT));
        receiver.session(0).send(Op.PLAYBACK_UPDATE, "{\"generationTime\":1,\"state\":0,\"time\":0.0,\"duration\":0.0,\"speed\":0.0}");
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.IDLE, WAIT));
        assertNull(session.getState().getSpeed());
        assertNull(session.getState().getPosition());
    }
}
