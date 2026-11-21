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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import de.yaacc.R;
import de.yaacc.fcast.FakeFCastReceiver;
import de.yaacc.fcast.FakeFCastReceiver.Op;
import de.yaacc.fcast.discovery.FCastDirectory;
import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.fcast.protocol.FCastConnection;
import de.yaacc.fcast.protocol.FCastReceiverState;
import de.yaacc.fcast.protocol.FCastSession;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.upnp.UpnpClient;

/**
 * Task 4.3: the FCast player against {@link FakeFCastReceiver}: what it sends for an item, how it follows the receiver (the end of
 * an item, a stop from outside, errors), the transport commands, volume and mute, and that leaving stops the item and closes the
 * connection. Plain JVM, no fixed port.
 */
public class FCastPlayerTest {

    private static final long WAIT = 5_000;
    private static final String A = "http://192.168.0.5:8080/proxy/a";
    private static final String B = "http://192.168.0.5:8080/proxy/b";
    private static final String C = "http://192.168.0.5:8080/proxy/c";

    /** The port of the fake receiver; set before a player is made (the session is made in the constructor). */
    private static volatile int port;

    private final List<Integer> toasts = Collections.synchronizedList(new ArrayList<Integer>());
    private FakeFCastReceiver receiver;
    private FCastPlayer player;
    private Context context;

    private final class TestPlayer extends FCastPlayer {
        TestPlayer(UpnpClient client, FCastReceiverDevice device) {
            super(client, device, "name", "short");
        }

        @Override
        protected FCastSession createSession(FCastSession.Listener listener, ScheduledExecutorService executor) {
            FCastConnection.Config connection = new FCastConnection.Config();
            connection.connectTimeoutMillis = 1_000;
            connection.initialWaitMillis = 150;
            connection.tickMillis = 20;
            FCastSession.Config config = new FCastSession.Config();
            config.idleCloseMillis = 60_000; // only the player's own close can end the connection within a test
            config.reconnectDelayMillis = 50;
            config.pendingTimeoutMillis = 300;
            FCastSession.Connector connector;
            try {
                connector = FCastSession.connectorFor(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port),
                        connection, MonotonicClock.SYSTEM, "Test Phone", "YAACC", "9.9");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new FCastSession(connector, listener, config, MonotonicClock.SYSTEM, executor);
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return PlayMode.of(PlayMode.RepeatMode.OFF, false);
        }

        @Override
        protected void showNotificationInternal() {
        }

        @Override
        protected void showToast(int textRes) {
            toasts.add(textRes);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = mock(Context.class);
        // leaving cancels the notification, which asks the system for its manager and the app for its group notification
        when(context.getSystemService("notification")).thenReturn(mock(android.app.NotificationManager.class));
        when(context.getApplicationContext()).thenReturn(mock(de.yaacc.Yaacc.class));
        receiver = FakeFCastReceiver.start(new FakeFCastReceiver.Options());
        port = receiver.port();
        player = newPlayer(port);
    }

    private FCastPlayer newPlayer(int forPort) {
        port = forPort;
        UpnpClient client = mock(UpnpClient.class);
        when(client.getContext()).thenReturn(context);
        FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(FCastEndpoint.parse("127.0.0.1:" + forPort), "Fake")));
        FCastReceiverDevice device = FCastReceiverDevice.of(directory.snapshot().get(0), null);
        return new TestPlayer(client, device);
    }

    @After
    public void tearDown() throws Exception {
        player.stopWorkers();
        receiver.close();
    }

    private static PlayableItem item(String url, String mime, String title) {
        PlayableItem item = new PlayableItem();
        Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn(url);
        item.setUri(uri);
        item.setMimeType(mime);
        item.setTitle(title);
        return item;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue("condition not reached in time", condition.getAsBoolean());
    }

    private void awaitPlays(int count) throws Exception {
        assertTrue("expected " + count + " Play packets, got " + receiver.received(Op.PLAY).size(), receiver.awaitPackets(Op.PLAY, count, WAIT));
    }

    private String playUrl(int index) throws Exception {
        return receiver.received(Op.PLAY).get(index).json().getString("url");
    }

    /** What a receiver reports when it was told to play: the new item, then Playing. */
    private void receiverPlays(String url, double position, double duration) throws Exception {
        FakeFCastReceiver.Session session = receiver.session(0);
        session.sendPlayUpdate("audio/mpeg", url);
        session.sendPlaybackUpdate(1, position, duration, 1.0);
    }

    private void receiverEnds(String url) throws Exception {
        receiver.session(0).send(Op.EVENT, "{\"generationTime\":1,\"event\":{\"type\":1,\"item\":{\"url\":" + JSONObject.quote(url) + "}}}");
    }

    private void startTwoItems() throws Exception {
        player.setItems(item(A, "audio/mpeg", "First"), item(B, "audio/mpeg", "Second"));
        player.play();
        awaitPlays(1);
        receiverPlays(A, 1.0, 30.0);
        await(() -> player.getSession().getState().getPlayback() == FCastReceiverState.Playback.PLAYING);
    }

    // ---- what is sent for an item

    @Test
    public void anItemIsSentWithItsUrlTypeAndTitleAndWithoutAVolume() throws Exception {
        player.setItems(item(A, "audio/mpeg", "First"));

        player.play();

        awaitPlays(1);
        JSONObject play = receiver.received(Op.PLAY).get(0).json();
        assertEquals(A, play.getString("url"));
        assertEquals("audio/mpeg", play.getString("container"));
        assertEquals("First", play.getJSONObject("metadata").getString("title"));
        assertFalse("the receiver's volume is left alone", play.has("volume"));
        assertFalse(play.has("headers"));
        assertTrue(player.isPlaying());
    }

    @Test
    public void anItemWithoutAnAddressIsSkippedWithAMessage() throws Exception {
        PlayableItem broken = new PlayableItem();
        broken.setTitle("broken");
        player.setItems(broken, item(B, "audio/mpeg", "Second"));

        player.play();

        awaitPlays(1);
        assertEquals(B, playUrl(0));
        assertTrue(toasts.contains(R.string.fcast_play_failed));
    }

    // ---- the end of an item

    @Test
    public void theEventOfTheReceiverStartsTheNextItem() throws Exception {
        startTwoItems();

        receiverEnds(A);

        awaitPlays(2);
        assertEquals(B, playUrl(1));
    }

    @Test
    public void aPausedUpdateAtTheDurationStartsTheNextItemAndTheEventAfterItAddsNothing() throws Exception {
        startTwoItems();

        receiver.session(0).sendPlaybackUpdate(2, 30.0, 30.0, 1.0);
        awaitPlays(2);
        receiverEnds(A);
        Thread.sleep(300);

        assertEquals(2, receiver.received(Op.PLAY).size());
    }

    @Test
    public void theEndOfTheLastItemStopsThePlayer() throws Exception {
        player.setItems(item(A, "audio/mpeg", "Only"));
        player.play();
        awaitPlays(1);
        receiverPlays(A, 1.0, 30.0);
        await(() -> player.getSession().getState().getPlayback() == FCastReceiverState.Playback.PLAYING);

        receiverEnds(A);

        await(() -> !player.isPlaying());
        assertEquals(1, receiver.received(Op.PLAY).size());
    }

    @Test
    public void theSafetyNetEndsAnItemWhoseReceiverSendsNothingMore() throws Exception {
        startTwoItems();

        receiver.session(0).sendPlaybackUpdate(1, 29.8, 30.0, 1.0);

        awaitPlays(2); // two seconds of silence, seen by the one second tick
        assertEquals(B, playUrl(1));
    }

    @Test
    public void aPauseOfOurOwnNearTheEndDoesNotAdvance() throws Exception {
        startTwoItems();
        receiver.session(0).sendPlaybackUpdate(1, 29.5, 30.0, 1.0);
        Thread.sleep(100);

        player.pause();
        await(() -> receiver.received(Op.PAUSE).size() == 1);
        receiver.session(0).sendPlaybackUpdate(2, 29.9, 30.0, 1.0);
        Thread.sleep(3_500);

        assertEquals("no next item", 1, receiver.received(Op.PLAY).size());
        assertTrue(player.isPaused());
    }

    @Test
    public void anEventAboutAnotherItemIsNoEnd() throws Exception {
        startTwoItems();

        receiverEnds("http://elsewhere.example/x");
        Thread.sleep(300);

        assertEquals(1, receiver.received(Op.PLAY).size());
    }

    // ---- the receiver stops, other senders

    @Test
    public void aStopOfTheReceiverStopsThePlayerWithoutAdvancing() throws Exception {
        startTwoItems();

        receiver.session(0).sendPlaybackUpdate(0, 0, 0, 0);

        await(() -> !player.isPlaying());
        Thread.sleep(200);
        assertEquals(1, receiver.received(Op.PLAY).size());
    }

    @Test
    public void aPauseAndAResumeFromAnotherSenderAreShown() throws Exception {
        startTwoItems();

        receiver.session(0).sendPlaybackUpdate(2, 5.0, 30.0, 1.0);
        await(() -> player.isPaused());
        receiver.session(0).sendPlaybackUpdate(1, 5.5, 30.0, 1.0);
        await(() -> player.isPlaying() && !player.isPaused());
        assertEquals(1, receiver.received(Op.PLAY).size());
    }

    // ---- errors

    @Test
    public void aPlaybackErrorSkipsTheItem() throws Exception {
        startTwoItems();

        receiver.session(0).sendPlaybackError("cannot decode");

        awaitPlays(2);
        assertEquals(B, playUrl(1));
        assertTrue(toasts.contains(R.string.fcast_play_failed));
    }

    @Test
    public void threeFailuresInARowStopThePlayer() throws Exception {
        player.setItems(item(A, "audio/mpeg", "1"), item(B, "audio/mpeg", "2"), item(C, "audio/mpeg", "3"), item(A + "x", "audio/mpeg", "4"));
        player.play();
        for (int i = 1; i <= 3; i++) {
            awaitPlays(i);
            receiver.session(0).sendPlaybackError("bad " + i);
        }

        await(() -> !player.isPlaying());
        Thread.sleep(300);
        assertEquals(3, receiver.received(Op.PLAY).size());
    }

    @Test
    public void aReceiverThatCanNotBeReachedStopsThePlayerWithAMessage() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        FCastPlayer lost = newPlayer(closedPort);
        try {
            lost.setItems(item(A, "audio/mpeg", "First"));

            lost.play();

            await(() -> toasts.contains(R.string.fcast_unreachable));
            await(() -> !lost.isPlaying());
        } finally {
            lost.stopWorkers();
        }
    }

    // ---- transport

    @Test
    public void pauseAndResumeGoToTheReceiver() throws Exception {
        startTwoItems();

        player.pause();
        await(() -> receiver.received(Op.PAUSE).size() == 1);
        receiver.session(0).sendPlaybackUpdate(2, 5.0, 30.0, 1.0);
        await(() -> player.getSession().getState().getPlayback() == FCastReceiverState.Playback.PAUSED);
        player.play();

        await(() -> receiver.received(Op.RESUME).size() == 1);
        assertEquals("a resume is no new Play", 1, receiver.received(Op.PLAY).size());
    }

    @Test
    public void stopGoesToTheReceiver() throws Exception {
        startTwoItems();

        player.stop();

        await(() -> receiver.received(Op.STOP).size() == 1);
        assertFalse(player.isPlaying());
    }

    @Test
    public void aSeekIsSentInSecondsAndStaysInsideTheItem() throws Exception {
        startTwoItems();

        player.seekTo(12_500);
        player.seekTo(99_000);

        await(() -> receiver.received(Op.SEEK).size() == 2);
        assertEquals(12.5, receiver.received(Op.SEEK).get(0).json().getDouble("time"), 0.0001);
        assertEquals(30.0, receiver.received(Op.SEEK).get(1).json().getDouble("time"), 0.0001);
    }

    @Test
    public void thePositionAndTheDurationFollowTheReceiver() throws Exception {
        startTwoItems();

        assertEquals("00:00:30", player.getDuration());
        assertTrue(player.getCurrentPosition() >= 1_000);
    }

    // ---- volume and mute

    @Test
    public void theVolumeIsSentAsAFractionAndShownAsPercent() throws Exception {
        startTwoItems();

        player.setVolume(40);
        await(() -> receiver.received(Op.SET_VOLUME).size() == 1);
        assertEquals(0.4, receiver.received(Op.SET_VOLUME).get(0).json().getDouble("volume"), 0.0001);
        receiver.session(0).sendVolumeUpdate(0.4);

        await(() -> player.getVolume() == 40);
        assertTrue(player.hasActionGetVolume());
        assertTrue(player.hasActionGetMute());
    }

    @Test
    public void muteSetsTheVolumeToZeroAndUnmuteRestoresIt() throws Exception {
        startTwoItems();
        receiver.session(0).sendVolumeUpdate(0.7);
        await(() -> player.getVolume() == 70);

        player.setMute(true);
        await(() -> receiver.received(Op.SET_VOLUME).size() == 1);
        assertEquals(0.0, receiver.received(Op.SET_VOLUME).get(0).json().getDouble("volume"), 0.0);
        receiver.session(0).sendVolumeUpdate(0.0);
        await(() -> player.getMute());

        player.setMute(false);
        await(() -> receiver.received(Op.SET_VOLUME).size() == 2);
        assertEquals(0.7, receiver.received(Op.SET_VOLUME).get(1).json().getDouble("volume"), 0.0001);
    }

    // ---- pictures

    @Test
    public void aPictureIsNotPausedOnTheReceiver() throws Exception {
        player.setItems(item(A, "image/jpeg", "Picture"));
        player.play();
        awaitPlays(1);

        player.pause();
        Thread.sleep(300);

        assertEquals(0, receiver.received(Op.PAUSE).size());
        assertEquals("image/jpeg", receiver.received(Op.PLAY).get(0).json().getString("container"));
    }

    // ---- leaving

    @Test
    public void destroyingThePlayerStopsTheItemAndClosesTheConnection() throws Exception {
        startTwoItems();

        player.onDestroy();

        await(() -> receiver.received(Op.STOP).size() >= 1);
        await(() -> player.getSession().getState().getLink() == FCastReceiverState.Link.IDLE);
        assertEquals(1, receiver.acceptedCount());
    }

    @Test
    public void aPlayerThatNeverPlayedMakesNoConnectionAndSendsNoStop() throws Exception {
        player.onDestroy();

        Thread.sleep(400);

        assertEquals(0, receiver.acceptedCount());
        assertEquals(0, receiver.received(Op.STOP).size());
    }
}
