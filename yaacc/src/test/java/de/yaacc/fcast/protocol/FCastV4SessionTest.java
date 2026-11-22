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

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.yaacc.fcast.FakeFCastTlsReceiver;
import de.yaacc.fcast.protocol.v4.V4In;
import de.yaacc.fcast.protocol.v4.V4Reader;
import de.yaacc.fcast.protocol.v4.V4Schema;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * Task 7.3: the session over a secured v4 connection: the same commands and the same state as with a v3 receiver, against a loopback
 * receiver that serves TLS 1.3 and speaks the v4 messages (the reference's own bytes for what it sends; what we send is read back
 * with the reader that is pinned to the reference in {@code V4ReaderTest}).
 */
public class FCastV4SessionTest {
    private static final long WAIT = 5_000;

    private final List<AutoCloseable> toClose = Collections.synchronizedList(new ArrayList<AutoCloseable>());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "v4-session-test");
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

    private static class Events implements FCastSession.Listener {
        final List<FCastMessages.Event> events = Collections.synchronizedList(new ArrayList<FCastMessages.Event>());
        final List<FCastMessages.PlayUpdate> playUpdates = Collections.synchronizedList(new ArrayList<FCastMessages.PlayUpdate>());

        @Override
        public void onStateChanged(FCastReceiverState state) {
        }

        @Override
        public void onPlayUpdate(FCastMessages.PlayUpdate update, FCastReceiverState state) {
            playUpdates.add(update);
        }

        @Override
        public void onMediaItemEvent(FCastMessages.Event event) {
            events.add(event);
        }
    }

    private interface Check {
        boolean test(FCastReceiverState state);
    }

    private static boolean await(FCastSession session, Check check) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT);
        while (System.nanoTime() < end) {
            if (check.test(session.getState())) {
                return true;
            }
            Thread.sleep(5);
        }
        return check.test(session.getState());
    }

    private FakeFCastTlsReceiver receiver(FakeFCastTlsReceiver.Keys keys) throws IOException {
        FakeFCastTlsReceiver.Options options = new FakeFCastTlsReceiver.Options();
        options.welcome = true;
        options.relay = true;
        FakeFCastTlsReceiver receiver = new FakeFCastTlsReceiver(keys, options);
        toClose.add(receiver);
        return receiver;
    }

    private FCastSession session(FakeFCastTlsReceiver receiver, FCastPin pin, Events events) throws IOException {
        final FCastConnection.Config config = new FCastConnection.Config();
        config.connectTimeoutMillis = 2_000;
        config.pingAfterMillis = 60_000;
        config.deadAfterMillis = 120_000;
        config.tickMillis = 20;
        config.pin = pin;
        config.platformSdk = 34;
        FCastSession.Config sessionConfig = new FCastSession.Config();
        sessionConfig.idleCloseMillis = 5_000;
        sessionConfig.pendingTimeoutMillis = 300;
        FCastSession session = new FCastSession(FCastSession.connectorFor(new InetSocketAddress(InetAddress.getLoopbackAddress(), receiver.port()),
                config, MonotonicClock.SYSTEM, "Test Phone", "YAACC", "9.9"), events, sessionConfig, MonotonicClock.SYSTEM, executor);
        toClose.add(session);
        return session;
    }

    private static V4In last(FakeFCastTlsReceiver receiver, V4In.Kind kind) throws Exception {
        V4In found = null;
        for (byte[] message : receiver.flatbufs()) {
            V4In in = V4Reader.read(message);
            if (in.kind == kind) {
                found = in;
            }
        }
        return found;
    }

    private static boolean got(FakeFCastTlsReceiver receiver, V4In.Kind kind) throws Exception {
        return last(receiver, kind) != null;
    }

    private static void awaitReceiver(FakeFCastTlsReceiver receiver, V4In.Kind kind) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT);
        while (!got(receiver, kind) && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertTrue("the receiver got no " + kind, got(receiver, kind));
    }

    @Test
    public void theSessionConnectsSecurelyAndShowsVersion4AndTheReceiversName() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();

        session.connect();

        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.CONNECTED && s.getProtocolVersion() == 4));
        assertTrue("the receiver's introduction names it", await(session, s -> "FCast-pc".equals(s.getReceiverName())));
        assertTrue("its volume at connect", await(session, s -> s.getVolume() != null && Math.abs(s.getVolume() - 0.5) < 1e-6));
        assertEquals("a sender introduction was the first message", FakeFCastTlsReceiver.FLATBUF, receiver.packets().get(0).opcode);
        assertEquals(4, session.getState().getPeerVersion());
        assertEquals(FCastPin.Source.LINK, session.getState().getPinSource());
    }

    @Test
    public void playAnswersWithTheLoadAndPlayingAndAPosition() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        Events events = new Events();
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.DISCOVERY), events);
        session.acquire();

        session.play(new FCastMessages.PlayRequest("video/mp4", "http://192.168.0.5:8080/a.mp4").title("Song"));

        awaitReceiver(receiver, V4In.Kind.LOAD);
        assertEquals("http://192.168.0.5:8080/a.mp4", last(receiver, V4In.Kind.LOAD).item.url);
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING && s.getPosition() != null
                && s.getPending() == FCastReceiverState.Pending.NONE));
        assertEquals(61.0, session.getState().getDuration(), 1e-9);
        assertFalse("the load was ours: the item is not another sender's", session.getState().isItemFromElsewhere());
        assertEquals(1, events.playUpdates.size());
    }

    @Test
    public void pauseResumeSeekVolumeSpeedAndStopAreV4Messages() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();
        session.play(new FCastMessages.PlayRequest("audio/mpeg", "http://h/a.mp3"));
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING));

        session.pause();
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PAUSED));
        assertEquals(Integer.valueOf(V4Schema.STATE_PAUSED), last(receiver, V4In.Kind.STATE).code);

        session.resume();
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING));
        assertEquals(Integer.valueOf(V4Schema.STATE_PLAYING), last(receiver, V4In.Kind.STATE).code);

        session.seek(42.5);
        awaitReceiver(receiver, V4In.Kind.PROGRESS);
        assertEquals(42.5, last(receiver, V4In.Kind.PROGRESS).positionSeconds, 1e-9);

        session.setVolume(0.8);
        awaitReceiver(receiver, V4In.Kind.VOLUME);
        assertEquals(0.8, last(receiver, V4In.Kind.VOLUME).number, 1e-6);
        assertTrue(await(session, s -> s.getVolume() != null && Math.abs(s.getVolume() - 0.8) < 1e-6));

        session.setSpeed(1.5);
        awaitReceiver(receiver, V4In.Kind.SPEED);
        assertEquals(1.5, last(receiver, V4In.Kind.SPEED).number, 1e-6);

        session.stop();
        awaitReceiver(receiver, V4In.Kind.STOP);
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.IDLE));
    }

    @Test
    public void theTracksAndTheCapabilitiesAreInTheState() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();
        session.play(new FCastMessages.PlayRequest("video/mp4", "http://h/a.mp4"));

        assertTrue(await(session, s -> s.getTracks().size() == 4));
        assertNotNull(session.getState().getCapabilities());
        assertTrue(session.getState().getCapabilities().supportsExternalSubtitles());
        assertEquals("eng", session.getState().getTracks().get(0).getLanguage());
    }

    @Test
    public void selectingATrackIsSentAndTheReceiversAnswerIsTheSelection() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();
        session.play(new FCastMessages.PlayRequest("video/mp4", "http://h/a.mp4"));
        assertTrue(await(session, s -> s.getTracks().size() == 4));

        session.changeTrack(FCastTrack.Type.AUDIO, 1L);

        awaitReceiver(receiver, V4In.Kind.TRACK_SELECTED);
        assertEquals(Long.valueOf(1), last(receiver, V4In.Kind.TRACK_SELECTED).trackId);
        assertTrue(await(session, s -> Long.valueOf(1).equals(s.getSelectedAudio())));

        session.changeTrack(FCastTrack.Type.SUBTITLE, 2L);
        assertTrue(await(session, s -> Long.valueOf(2).equals(s.getSelectedSubtitle())));
        session.changeTrack(FCastTrack.Type.SUBTITLE, null);
        assertTrue(await(session, s -> s.getSelectedSubtitle() == null));
    }

    @Test
    public void aRepeatedTrackListKeepsTheSelection() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();
        session.play(new FCastMessages.PlayRequest("video/mp4", "http://h/a.mp4"));
        assertTrue(await(session, s -> s.getTracks().size() == 4));
        session.changeTrack(FCastTrack.Type.AUDIO, 1L);
        session.changeTrack(FCastTrack.Type.SUBTITLE, 2L);
        assertTrue(await(session, s -> Long.valueOf(1).equals(s.getSelectedAudio()) && Long.valueOf(2).equals(s.getSelectedSubtitle())));

        receiver.sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("tracks")); // the same list again, as a receiver does after a subtitle file
        receiver.sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("volume")); // a message behind it: when this is seen, the list was handled

        assertTrue(await(session, s -> s.getVolume() != null && Math.abs(s.getVolume() - 0.5) < 1e-6));
        assertEquals(Long.valueOf(1), session.getState().getSelectedAudio());
        assertEquals(Long.valueOf(2), session.getState().getSelectedSubtitle());
    }

    @Test
    public void anErrorWhileTheItemPlaysIsShownAndDoesNotStopIt() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        Events events = new Events() {
            @Override
            public void onPlaybackError(String message) {
                failures.add(message);
            }
        };
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), events);
        session.acquire();
        session.play(new FCastMessages.PlayRequest("video/mp4", "http://h/a.mp4"));
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING));

        receiver.sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("error"));

        assertTrue(await(session, s -> s.getLastError() != null && s.getLastError().contains("UnsupportedFormat")));
        assertTrue("no failure of the item", failures.isEmpty());
        assertEquals(FCastReceiverState.Playback.PLAYING, session.getState().getPlayback());
    }

    @Test
    public void aSubtitleByAddressIsSentAsTheReferenceMessage() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();

        session.addSubtitle("http://h/a.srt", true, "English");

        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT);
        while (receiver.flatbufs().size() < 2 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        List<byte[]> sent = receiver.flatbufs();
        assertEquals(2, sent.size()); // the introduction, then the subtitle
        assertTrue(java.util.Arrays.equals(de.yaacc.fcast.protocol.v4.V4Writer.addSubtitleSource("http://h/a.srt", true, "English"), sent.get(1)));
    }

    @Test
    public void theEndOfAnItemReachesTheListenerAsTheEndEvent() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        Events events = new Events();
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), events);
        session.acquire();
        session.play(new FCastMessages.PlayRequest("audio/mpeg", "http://h/a.mp3"));
        assertTrue(await(session, s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING));

        receiver.sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("stateEnded"));

        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT);
        while (events.events.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(1, events.events.size());
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_END, events.events.get(0).getType());
        assertEquals("http://h/a.mp3", events.events.get(0).getItemUrl());
    }

    @Test
    public void aLoadFromAnotherSenderIsAnItemFromElsewhere() throws Exception {
        FakeFCastTlsReceiver.Keys keys = FakeFCastTlsReceiver.Keys.get(0);
        FakeFCastTlsReceiver receiver = receiver(keys);
        FCastSession session = session(receiver, keys.pin(FCastPin.Source.LINK), new Events());
        session.acquire();
        session.connect();
        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.CONNECTED));

        receiver.sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("loadFull"));

        assertTrue(await(session, s -> s.isItemFromElsewhere() && "http://192.168.0.5:8080/a.mp4".equals(s.getItemUrl())));
    }

    @Test
    public void aWrongFingerprintFailsTheSessionWithAReasonAndTheReceiverNeverSeesACommand() throws Exception {
        FakeFCastTlsReceiver receiver = receiver(FakeFCastTlsReceiver.Keys.get(0));
        FCastSession session = session(receiver, FakeFCastTlsReceiver.Keys.get(1).pin(FCastPin.Source.LINK), new Events());
        session.acquire();

        session.play(new FCastMessages.PlayRequest("audio/mpeg", "http://h/a.mp3"));

        assertTrue(await(session, s -> s.getLink() == FCastReceiverState.Link.FAILED && s.getLastError() != null));
        assertNotNull(session.getState().getLastError());
        assertEquals(FCastSecurityException.Kind.PIN_MISMATCH, session.getState().getSecurityFailure());
        Thread.sleep(200);
        assertEquals(1, receiver.accepted());
        assertTrue(receiver.flatbufs().isEmpty());
    }
}
