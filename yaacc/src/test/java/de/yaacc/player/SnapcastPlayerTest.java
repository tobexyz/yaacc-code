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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.feed.FeedError;
import de.yaacc.multiroom.feed.PcmSink;
import de.yaacc.multiroom.feed.SnapcastFeedController;
import de.yaacc.upnp.UpnpClient;

/**
 * The glue of the Snapcast player with a mocked feed controller: ExoPlayer starts only after the feed is open, a failure
 * stops the player and tells the user, stop and destroy reach the controller, the volume is the group's, and the id is
 * the receiver id of the group. ExoPlayer itself is not built here (no device), see the spike checklist in docs/tech.md.
 */
public class SnapcastPlayerTest {

    private Context context;
    private UpnpClient upnpClient;
    private SnapcastFeedController controller;
    private SnapcastFeedController.Listener listener;
    private SnapcastGroupDevice device;
    private final List<String> exoCalls = new ArrayList<>();
    private final List<String> mainThreadWork = new ArrayList<>();
    private final List<String> toasts = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private PcmSink controllerSink;

    /** Records what would go to ExoPlayer and runs main-thread work at once. */
    private class TestPlayer extends SnapcastPlayer {
        TestPlayer(Function<SnapcastFeedController.Listener, SnapcastFeedController> factory) {
            super(upnpClient, device, "Music@Room", "Room", factory);
        }

        @Override
        void startOnExoPlayer(PlayableItem playableItem, Object loadedItem, int index) {
            exoCalls.add("start " + index);
        }

        @Override
        void resumeOnExoPlayer() {
            exoCalls.add("resume");
        }

        @Override
        void postToMain(Runnable work) {
            mainThreadWork.add("work");
            work.run();
        }

        @Override
        void postToMainDelayed(Runnable work, long delayMillis) {
            delayed.add(work);
        }

        @Override
        void showToast(String text) {
            toasts.add(text);
        }
    }

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getString(anyInt())).thenAnswer(call -> "text" + call.getArgument(0));
        when(context.getString(anyInt(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(call -> "text" + call.getArgument(0) + ":" + call.getArgument(1));
        when(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(mock(android.app.NotificationManager.class));
        when(context.getApplicationContext()).thenReturn(mock(de.yaacc.Yaacc.class));
        upnpClient = mock(UpnpClient.class);
        when(upnpClient.getContext()).thenReturn(context);
        controller = mock(SnapcastFeedController.class);
        controllerSink = mock(PcmSink.class);
        when(controller.sink()).thenReturn(controllerSink);
        SnapcastClient member = new SnapcastClient("m", "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup("g-a", "Attic", false, "Default", Collections.singletonList(member));
        device = SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    private TestPlayer player() {
        return new TestPlayer(l -> {
            listener = l;
            return controller;
        });
    }

    private static PlayableItem item() {
        return new PlayableItem();
    }

    // ---- identity

    @Test
    public void theDeviceIdIsTheReceiverIdOfTheGroupSoTheRowsFindThePlayer() {
        TestPlayer player = player();

        assertEquals(device.getIdentity().getUdn().getIdentifierString(), player.getDeviceId());
        assertTrue(SnapcastGroupDevice.isSnapcastId(player.getDeviceId()));
    }

    @Test
    public void everyPlayerHasItsOwnIdAboveTheFixedOnesAndNeverTheLocalMusicPlayersId() {
        TestPlayer first = player();
        TestPlayer second = player();

        assertNotEquals(first.getId(), second.getId());
        assertTrue(first.getId() >= 100);
        assertNotEquals(de.yaacc.util.NotificationId.LOCAL_BACKGROUND_MUSIC_PLAYER.getId(), first.getId());
    }

    @Test
    public void thePlayerIsAMusicPlayerOfTheLocalKind() {
        assertTrue(player() instanceof LocalMediaSessionPlayer);
    }

    // ---- starting

    @Test
    public void exoPlayerStartsOnlyAfterTheFeedIsOpen() {
        TestPlayer player = player();
        when(controller.isFeeding()).thenReturn(false);

        player.startItem(item(), null, 3);

        assertTrue("not before the feed is ready", exoCalls.isEmpty());
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());
        callback.getValue().onReady();
        assertEquals(Collections.singletonList("start 3"), exoCalls);
    }

    @Test
    public void anOpenFeedIsStillAskedForSoThatAQueuedStopComesBeforeTheStart() {
        TestPlayer player = player();
        when(controller.isFeeding()).thenReturn(true);

        player.startItem(item(), null, 1);

        assertTrue("ExoPlayer waits for the answer of the controller", exoCalls.isEmpty());
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());
        callback.getValue().onReady();
        assertEquals(Collections.singletonList("start 1"), exoCalls);
    }

    @Test
    public void aResumeOpensTheFeedFirstWhenItWasClosed() {
        TestPlayer player = player();
        when(controller.isFeeding()).thenReturn(false);

        player.doResume();

        assertTrue(exoCalls.isEmpty());
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());
        callback.getValue().onReady();
        assertEquals(Collections.singletonList("resume"), exoCalls);
    }

    @Test
    public void aResumeWithAnOpenFeedIsAlsoAskedFromTheController() {
        TestPlayer player = player();
        when(controller.isFeeding()).thenReturn(true);

        player.doResume();

        assertTrue(exoCalls.isEmpty());
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());
        callback.getValue().onReady();
        assertEquals(Collections.singletonList("resume"), exoCalls);
    }

    @Test
    public void theDelayOfTheServerBufferIsToldOncePerPlayer() {
        TestPlayer player = player();
        SnapcastFeedController.StartCallback first = player.startAnswer(null, null);
        SnapcastFeedController.StartCallback second = player.startAnswer(null, null);

        first.onReady();
        int afterFirst = mainThreadWork.size();
        second.onReady();

        assertEquals("one toast, with the text of the note", Collections.singletonList("text" + R.string.snapcast_play_delay_note), toasts);
        assertEquals("none the second time", afterFirst, mainThreadWork.size());
        assertEquals(1, toasts.size());
    }

    // ---- failures

    @Test
    public void aFailedStartDoesNotStartExoPlayerAndStopsThePlayer() {
        final int[] stops = new int[1];
        TestPlayer player = new TestPlayer(l -> controller) {
            @Override
            public void stop() {
                stops[0]++;
            }
        };
        when(controller.isFeeding()).thenReturn(false);
        player.startItem(item(), null, 0);
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());

        callback.getValue().onFailed(FeedError.of(FeedError.Kind.BUSY));

        assertTrue(exoCalls.isEmpty());
        assertEquals(1, stops[0]);
        assertEquals("the user is told why", Collections.singletonList("text" + R.string.snapcast_feed_error_busy), toasts);
    }

    @Test
    public void aFeedThatIsLostWhilePlayingStopsThePlayerToo() {
        final int[] stops = new int[1];
        new TestPlayer(l -> {
            listener = l;
            return controller;
        }) {
            @Override
            public void stop() {
                stops[0]++;
            }
        };

        listener.onFeedLost(FeedError.of(FeedError.Kind.CONTROL_LOST));

        assertEquals(1, stops[0]);
        assertEquals(Collections.singletonList("text" + R.string.snapcast_feed_error_lost), toasts);
    }

    // ---- stop, destroy

    @Test
    public void stoppingAnItemClosesTheFeed() {
        TestPlayer player = player();

        player.stopItem(item());

        verify(controller).stop();
    }

    @Test
    public void destroyingThePlayerReleasesTheControllerOnceAndTheSinkRefusesAfterwards() {
        TestPlayer player = player();
        PcmSink sink = player.tapSink();
        when(controllerSink.write(any(), anyInt(), anyInt())).thenReturn(true);
        assertTrue(sink.write(new byte[4], 0, 4));

        player.onDestroy();

        verify(controller, times(1)).release();
        assertFalse("nothing reaches a released controller", sink.write(new byte[4], 0, 4));
        verify(controllerSink, times(1)).write(any(), anyInt(), anyInt());
    }

    // ---- the tap's sink

    @Test
    public void theTapWritesToTheControllersSinkAndDiscardsThere() {
        TestPlayer player = player();
        when(controllerSink.write(any(), anyInt(), anyInt())).thenReturn(true);
        byte[] audio = new byte[16];

        assertTrue(player.tapSink().write(audio, 4, 8));
        player.tapSink().discard();

        verify(controllerSink).write(audio, 4, 8);
        verify(controllerSink).discard();
    }

    // ---- the volume is the group's

    @Test
    public void theVolumeAndTheMuteAreThoseOfTheGroupAndThePhonesVolumeIsNeverTouched() {
        TestPlayer player = player();
        when(controller.getVolume()).thenReturn(42);
        when(controller.isMuted()).thenReturn(true);

        assertEquals(42, player.getVolume());
        assertTrue(player.getMute());
        player.setVolume(77);
        player.setMute(false);

        verify(controller).setVolume(77);
        verify(controller).setMuted(false);
        assertTrue(player.hasActionGetVolume());
        assertTrue(player.hasActionGetMute());
        verify(upnpClient, never()).setVolume(anyInt());
        verify(upnpClient, never()).getVolume();
        verify(upnpClient, never()).setMute(org.mockito.ArgumentMatchers.anyBoolean());
        verify(upnpClient, never()).isMute();
    }

    @Test
    public void afterADestroyTheVolumeCallsDoNothing() {
        TestPlayer player = player();
        player.onDestroy();

        assertEquals(0, player.getVolume());
        assertFalse(player.getMute());
        player.setVolume(10);
        player.setMute(true);

        verify(controller, never()).setVolume(anyInt());
        verify(controller, never()).setMuted(org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ---- a pause during the start (W2), with the real pause flag of AbstractPlayer

    private static void awaitPaused(AbstractPlayer player) throws InterruptedException {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!player.isPaused() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertTrue("the real pause() set the flag", player.isPaused());
    }

    @Test
    public void aPauseWhileTheFeedIsOpeningKeepsExoPlayerFromStarting() throws Exception {
        TestPlayer player = player();
        player.startItem(item(), null, 2);
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());

        player.pause(); // the real AbstractPlayer.pause(): sets the paused flag on its command thread
        awaitPaused(player);
        callback.getValue().onReady();

        assertTrue("paused means paused: ExoPlayer does not start", exoCalls.isEmpty());
    }

    @Test
    public void theResumeAfterThatPauseStartsTheItemThatWasHeldBack() throws Exception {
        TestPlayer player = player();
        player.startItem(item(), null, 2);
        ArgumentCaptor<SnapcastFeedController.StartCallback> first =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(first.capture());
        player.pause();
        awaitPaused(player);
        first.getValue().onReady();

        player.setPaused(false); // what AbstractPlayer.play() does before it calls doResume()
        player.doResume();

        ArgumentCaptor<SnapcastFeedController.StartCallback> second =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller, times(2)).start(second.capture());
        second.getAllValues().get(1).onReady();
        assertEquals("the item, not a resume of an ExoPlayer that never started it", Collections.singletonList("start 2"),
                exoCalls);
    }

    @Test
    public void aStopDropsTheItemThatWasHeldBack() throws Exception {
        TestPlayer player = player();
        player.startItem(item(), null, 2);
        ArgumentCaptor<SnapcastFeedController.StartCallback> first =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(first.capture());
        player.pause();
        awaitPaused(player);
        first.getValue().onReady();
        player.stopItem(item());

        player.setPaused(false);
        player.doResume();

        ArgumentCaptor<SnapcastFeedController.StartCallback> second =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller, times(2)).start(second.capture()); // the stop's controller.stop() is separate
        second.getAllValues().get(1).onReady();
        assertEquals("a plain resume", Collections.singletonList("resume"), exoCalls);
    }

    @Test
    public void aPlayerThatIsNotPausedStartsAsBefore() {
        TestPlayer player = player();
        player.startItem(item(), null, 0);
        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());

        assertFalse(player.isPaused());
        callback.getValue().onReady();

        assertEquals(Collections.singletonList("start 0"), exoCalls);
    }

    @Test
    public void aDestroyedPlayerStartsNothing() {
        TestPlayer player = player();
        player.onDestroy();

        player.startItem(item(), null, 0);
        player.doResume();

        assertTrue(exoCalls.isEmpty());
        verify(controller, never()).start(any());
    }

    // ---- a failure stops the player even while a command runs

    @Test
    public void aFailureWhileACommandRunsTriesTheStopAgainAndThenStops() {
        TestPlayer player = new TestPlayer(l -> controller);
        player.setProcessingCommand(true); // the real flag that makes AbstractPlayer.stop() return at once

        player.feedFailed(FeedError.of(FeedError.Kind.CONTROL_LOST));

        assertEquals("the stop is waiting", 1, delayed.size());
        delayed.remove(0).run();
        assertEquals("still busy: tried again", 1, delayed.size());
        assertTrue(player.isProcessingCommand());

        player.setProcessingCommand(false);
        delayed.remove(0).run();

        assertTrue("the real stop() was accepted: it takes the command flag", player.isProcessingCommand());
        assertTrue(delayed.isEmpty());
    }

    @Test
    public void theStopRetryGivesUpAfterAboutASecondAndStopsAnyway() {
        TestPlayer player = new TestPlayer(l -> controller);
        player.setProcessingCommand(true);
        player.feedFailed(FeedError.of(FeedError.Kind.BUSY));

        int retries = 0;
        while (!delayed.isEmpty() && retries < 100) {
            delayed.remove(0).run();
            retries++;
        }

        assertEquals(20, retries); // twenty delayed tries, the last one calls stop() even if the player is still busy
        assertTrue(delayed.isEmpty());
    }

    // ---- ExoPlayer errors

    @Test
    public void anExoPlayerErrorStopsThePlayerWithAToastAndNoDetailsInTheText() {
        TestPlayer player = new TestPlayer(l -> controller);

        player.playerFailed("ERROR_CODE_DECODING_FAILED");

        assertEquals(Collections.singletonList("text" + R.string.snapcast_feed_error_player), toasts);
        assertTrue("stop() was taken", player.isProcessingCommand());
    }

    // ---- 11.12: a track that can not be read is skipped, the feed stays open

    @Test
    public void anUnreadableTrackIsSkippedAndTheFeedIsLeftAlone() {
        TestPlayer player = new TestPlayer(l -> controller);

        player.trackFailed("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", "By The Way", true);

        assertEquals(Collections.singletonList("text" + R.string.player_track_skipped + ":By The Way"), toasts);
        assertFalse("no stop was taken: the player goes on", player.isProcessingCommand());
        verify(controller, never()).stop();
        verify(controller, never()).pause();
        verify(controller, never()).release();
    }

    @Test
    public void afterASkipTheNextItemStartsOnTheSameFeed() {
        TestPlayer player = new TestPlayer(l -> controller);
        when(controller.isFeeding()).thenReturn(true);
        player.trackFailed("E", "Bad", true);

        player.startItem(item(), null, 1);

        ArgumentCaptor<SnapcastFeedController.StartCallback> callback =
                ArgumentCaptor.forClass(SnapcastFeedController.StartCallback.class);
        verify(controller).start(callback.capture());
        callback.getValue().onReady();
        assertEquals(Collections.singletonList("start 1"), exoCalls);
        verify(controller, never()).stop();
    }

    @Test
    public void theThirdUnreadableTrackInARowStopsThePlayerWithTheUsualMessage() {
        TestPlayer player = new TestPlayer(l -> controller);

        player.trackFailed("E", "A", true);
        player.trackFailed("E", "B", true);
        assertFalse(player.isProcessingCommand());
        player.trackFailed("E", "C", true);

        assertEquals("text" + R.string.snapcast_feed_error_player, toasts.get(toasts.size() - 1));
        assertEquals("two skips and the stop", 3, toasts.size());
        assertTrue("stop() was taken", player.isProcessingCommand());
    }

    @Test
    public void anUnreadableLastItemStopsThePlayer() {
        TestPlayer player = new TestPlayer(l -> controller);

        player.trackFailed("E", "Last", false);

        assertEquals(Collections.singletonList("text" + R.string.snapcast_feed_error_player), toasts);
        assertTrue(player.isProcessingCommand());
    }

    @Test
    public void aTrackThatStartsMakesTheCountStartOver() {
        TestPlayer player = new TestPlayer(l -> controller);
        player.trackFailed("E", "A", true);
        player.trackFailed("E", "B", true);

        player.trackStarted();
        player.trackFailed("E", "C", true);
        player.trackFailed("E", "D", true);

        assertFalse(player.isProcessingCommand());
        assertEquals(4, toasts.size());
    }

    @Test
    public void thePlayerNoLongerStopsOnTheFirstExoPlayerErrorByItself() throws Exception {
        String snapcast = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/java/de/yaacc/player/SnapcastPlayer.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        String local = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/java/de/yaacc/player/LocalMediaSessionPlayer.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);

        assertFalse("the one listener is the local player's", snapcast.contains("onPlayerError"));
        assertEquals(1, local.split("public void onPlayerError\\(", -1).length - 1);
    }

    // ---- S39: the last retry of the stop leaves a flag and a log line

    @Test
    public void whenEvenTheLastRetryFindsThePlayerBusyAFlagSaysThatAStopIsOutstanding() {
        TestPlayer player = new TestPlayer(l -> controller);
        player.setProcessingCommand(true);
        assertFalse(player.isStopOutstanding());
        player.feedFailed(FeedError.of(FeedError.Kind.BUSY));

        while (!delayed.isEmpty()) {
            delayed.remove(0).run();
        }

        assertTrue(player.isStopOutstanding());
    }

    @Test
    public void aStopThatWentThroughLeavesNoFlag() {
        TestPlayer player = new TestPlayer(l -> controller);
        player.feedFailed(FeedError.of(FeedError.Kind.BUSY));

        assertFalse(player.isStopOutstanding());
    }

    @Test
    public void theNextUserActionClearsTheFlag() {
        for (int action = 0; action < 3; action++) {
            TestPlayer player = new TestPlayer(l -> controller);
            player.setProcessingCommand(true);
            player.feedFailed(FeedError.of(FeedError.Kind.BUSY));
            while (!delayed.isEmpty()) {
                delayed.remove(0).run();
            }
            assertTrue(player.isStopOutstanding());

            if (action == 0) {
                player.pause();
            } else if (action == 1) {
                player.play();
            } else {
                player.stop();
            }

            assertFalse("action " + action, player.isStopOutstanding());
        }
    }
}
