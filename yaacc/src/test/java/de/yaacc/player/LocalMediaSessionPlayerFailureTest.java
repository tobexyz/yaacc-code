/*
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;

/**
 * 11.12 for the local music player: it had no onPlayerError, so a file that can not be read left ExoPlayer idle while the
 * app still showed "playing". Now the track is skipped with a toast that names it, and after three in a row (or a failing
 * last item) the player stops. 11.11: playlist files do not enter the list.
 */
public class LocalMediaSessionPlayerFailureTest {

    private UpnpClient upnpClient;
    private final List<String> toasts = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private class TestLocal extends LocalMediaSessionPlayer {
        TestLocal() {
            super(upnpClient, "Music", "M");
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return PlayMode.NORMAL;
        }

        @Override
        void skipOnExoPlayer() {
            calls.add("skip");
        }

        @Override
        void showToast(String text) {
            toasts.add(text);
        }

        @Override
        void postToMain(Runnable work) {
            work.run();
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        when(context.getString(anyInt())).thenAnswer(call -> "text" + call.getArgument(0));
        when(context.getString(anyInt(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(call -> "text" + call.getArgument(0) + ":" + call.getArgument(1));
        when(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(mock(android.app.NotificationManager.class));
        when(context.getApplicationContext()).thenReturn(mock(de.yaacc.Yaacc.class));
        upnpClient = mock(UpnpClient.class);
        when(upnpClient.getContext()).thenReturn(context);
    }

    @Test
    public void anUnreadableTrackIsSkippedWithAToastThatNamesIt() {
        TestLocal player = new TestLocal();

        player.trackFailed("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", "By The Way", 1, true);

        assertEquals(Arrays.asList("skip"), calls);
        assertEquals(1, toasts.size());
        assertEquals("text" + R.string.player_track_skipped + ":By The Way", toasts.get(0));
    }

    @Test
    public void theNameInTheToastIsCleanedAndCut() {
        TestLocal player = new TestLocal();
        StringBuilder title = new StringBuilder("evil\nname‮");
        for (int i = 0; i < 300; i++) {
            title.append('z');
        }

        player.trackFailed("ERROR_CODE_IO_UNSPECIFIED", title.toString(), 1, true);

        String text = toasts.get(0);
        assertFalse(text, text.contains("\n"));
        assertFalse(text, text.contains("‮"));
        assertTrue(text, text.length() < 140);
        assertTrue(text, text.startsWith("text" + R.string.player_track_skipped + ":evil name"));
    }

    @Test
    public void theToastNeverHoldsTheErrorCodeOrAnAddress() {
        TestLocal player = new TestLocal();

        player.trackFailed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "Song", 1, true);

        assertFalse(toasts.get(0).contains("ERROR_CODE"));
        assertFalse(toasts.get(0).contains("http"));
    }

    @Test
    public void thirdFailureInARowStopsThePlayerWithItsOwnMessage() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", "A", 1, true);
        player.trackFailed("E", "B", 1, true);
        player.trackFailed("E", "C", 1, true);

        assertEquals(Arrays.asList("skip", "skip", "stop"), calls);
        assertEquals("text" + R.string.player_stopped_after_errors, toasts.get(toasts.size() - 1));
        assertEquals("two skip toasts and the stop message", 3, toasts.size());
    }

    @Test
    public void aFailingLastItemStopsThePlayerAtOnce() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", "Last", 1, false);

        assertEquals(Arrays.asList("stop"), calls);
        assertEquals(Arrays.asList("text" + R.string.player_stopped_after_errors), toasts);
    }

    @Test
    public void aTrackThatStartsResetsTheCount() {
        TestLocal player = new TestLocal();
        player.trackFailed("E", "A", 1, true);
        player.trackFailed("E", "B", 1, true);

        player.trackStarted();
        player.trackFailed("E", "C", 1, true);
        player.trackFailed("E", "D", 1, true);

        assertEquals(Arrays.asList("skip", "skip", "skip", "skip"), calls);
    }

    @Test
    public void aStopByTheUserResetsTheCountToo() {
        TestLocal player = new TestLocal();
        player.trackFailed("E", "A", 1, true);
        player.trackFailed("E", "B", 1, true);

        player.stopItem(new PlayableItem());
        player.trackFailed("E", "C", 1, true);

        assertEquals(Arrays.asList("skip", "skip", "skip"), calls);
    }

    @Test
    public void aTitleThatIsNotKnownShowsThePositionOfTheTrack() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", null, 3, true);

        assertEquals("text" + R.string.player_track_skipped + ":text" + R.string.player_track_number + ":3", toasts.get(0));
    }

    @Test
    public void aTitleThatIsNotKnownAndNoPositionShowsAQuestionMark() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", "", 0, true);

        assertEquals("text" + R.string.player_track_skipped + ":?", toasts.get(0));
    }

    @Test
    public void anAddressAsTheTitleIsNeverInTheToast() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", "http://192.168.1.5:8080/media/a.mp3?token=secret", 2, true);

        String text = toasts.get(0);
        assertFalse(text, text.contains("http"));
        assertFalse(text, text.contains("secret"));
        assertEquals("text" + R.string.player_track_skipped + ":text" + R.string.player_track_number + ":2", text);
    }

    @Test
    public void theFailedTrackIsFoundByItsIndexInTheList() {
        TestLocal player = new TestLocal();
        player.setItems(item("audio/mpeg", "One"), item("audio/mpeg", "http://h/x/two.mp3"), item("audio/mpeg", "Three"));

        player.trackFailedAt("E", 0, true);
        player.trackFailedAt("E", 1, true);

        assertEquals("text" + R.string.player_track_skipped + ":One", toasts.get(0));
        assertEquals("an address is replaced by the position, counted from one",
                "text" + R.string.player_track_skipped + ":text" + R.string.player_track_number + ":2", toasts.get(1));
    }

    @Test
    public void anIndexOutsideTheListStillSkipsWithTheQuestionMark() {
        TestLocal player = new TestLocal();

        player.trackFailedAt("E", -1, true);
        player.trackFailedAt("E", 7, true);

        assertEquals(Arrays.asList("skip", "skip"), calls);
        assertEquals("text" + R.string.player_track_skipped + ":?", toasts.get(0));
    }

    // ---- review S5: the user's own choice of a track starts the count again

    @Test
    public void aManualNextOrPreviousStartsTheCountAgain() {
        for (int action = 0; action < 2; action++) {
            calls.clear();
            TestLocal player = new TestLocal();
            player.trackFailed("E", "A", 1, true);
            player.trackFailed("E", "B", 2, true);

            if (action == 0) {
                player.next();
            } else {
                player.previous();
            }
            player.trackFailed("E", "C", 3, true);

            assertEquals("action " + action, Arrays.asList("skip", "skip", "skip"), calls);
        }
    }

    // ---- 11.11: playlist files do not enter the list of the local player

    private static PlayableItem item(String mime, String title) {
        PlayableItem item = new PlayableItem();
        item.setMimeType(mime);
        item.setTitle(title);
        return item;
    }

    @Test
    public void theListOfSetItemsIsKeptAsGivenBecauseTheCallerIndexesIt() {
        // the queue builders of PlayerService filter before; the OpenHome renderer keeps one id per entry of this list
        TestLocal player = new TestLocal();
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem list = item("audio/x-mpegurl", "By The Way");
        PlayableItem two = item("audio/mpeg", "Two");

        player.setItems(one, list, two);

        assertEquals(Arrays.asList(one, list, two), player.getItems());
    }

    @Test
    public void addedPlaylistFilesAreLeftOutAndTheUserHearsItWhenNothingIsLeft() {
        TestLocal player = new TestLocal();
        PlayableItem one = item("audio/mpeg", "One");
        player.setItems(one);

        player.addItems(Arrays.asList(item("audio/x-scpls", "A"), item("audio/mpeg", "B.pls")));

        assertEquals(Arrays.asList(one), player.getItems());
        assertEquals(Arrays.asList("text" + R.string.player_nothing_playable), toasts);
    }

    @Test
    public void insertedItemsAreKeptAsGivenBecauseTheCallerIndexesTheList() {
        TestLocal player = new TestLocal();
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem two = item("audio/mpeg", "Two");
        player.setItems(one, two);
        PlayableItem list = item("audio/x-mpegurl", "L");
        PlayableItem between = item("audio/mpeg", "Between");

        player.insertItems(1, Arrays.asList(list, between));

        assertEquals(Arrays.asList(one, list, between, two), player.getItems());
    }

    // ---- review W2: the stop after the failures must not be lost while a command runs

    private final List<Runnable> delayed = new ArrayList<>();

    /** A player whose stop() behaves like AbstractPlayer.stop(): it is ignored while a command runs. */
    private class BusyLocal extends TestLocal {
        @Override
        void postToMainDelayed(Runnable work, long delayMillis) {
            delayed.add(work);
        }

        @Override
        public void stop() {
            if (isProcessingCommand()) {
                return;
            }
            calls.add("stop");
        }
    }

    @Test
    public void aStopThatIsIgnoredWhileACommandRunsIsRetriedUntilItGoesThrough() {
        BusyLocal player = new BusyLocal();
        player.setProcessingCommand(true);

        player.trackFailed("E", "Last", 1, false);

        assertEquals("nothing stopped yet", Collections.<String>emptyList(), calls);
        assertTrue("and the user is not told that playback stopped", toasts.isEmpty());
        assertEquals(1, delayed.size());

        delayed.remove(0).run();
        assertEquals("still busy: tried again", 1, delayed.size());
        assertTrue(calls.isEmpty());

        player.setProcessingCommand(false);
        delayed.remove(0).run();

        assertEquals(Arrays.asList("stop"), calls);
        assertEquals(Arrays.asList("text" + R.string.player_stopped_after_errors), toasts);
        assertTrue(delayed.isEmpty());
        assertFalse(player.isStopOutstanding());
    }

    @Test
    public void aThirdFailureWhileACommandRunsIsRetriedToo() {
        BusyLocal player = new BusyLocal();
        player.trackFailed("E", "A", 1, true);
        player.trackFailed("E", "B", 2, true);
        player.setProcessingCommand(true);

        player.trackFailed("E", "C", 3, true);
        player.setProcessingCommand(false);
        delayed.remove(0).run();

        assertEquals(Arrays.asList("skip", "skip", "stop"), calls);
    }

    @Test
    public void whenEvenTheLastRetryFindsThePlayerBusyAFlagSaysThatAStopIsOutstanding() {
        BusyLocal player = new BusyLocal();
        player.setProcessingCommand(true);
        player.trackFailed("E", "Last", 1, false);

        int retries = 0;
        while (!delayed.isEmpty() && retries < 100) {
            delayed.remove(0).run();
            retries++;
        }

        assertEquals("twenty delayed tries, as the Snapcast player", 20, retries);
        assertTrue(player.isStopOutstanding());
        assertTrue("the user is not told a stop that did not happen", toasts.isEmpty());
    }

    @Test
    public void theNextUserActionClearsTheFlag() {
        for (int action = 0; action < 4; action++) { // stop() is the test double's own here; the Snapcast player test covers it
            delayed.clear();
            BusyLocal player = new BusyLocal();
            player.setProcessingCommand(true);
            player.trackFailed("E", "Last", 1, false);
            while (!delayed.isEmpty()) {
                delayed.remove(0).run();
            }
            assertTrue(player.isStopOutstanding());
            player.setProcessingCommand(false);

            if (action == 0) {
                player.pause();
            } else if (action == 1) {
                player.play();
            } else if (action == 2) {
                player.next();
            } else {
                player.previous();
            }

            assertFalse("action " + action, player.isStopOutstanding());
        }
    }

    // ---- the texts

    @Test
    public void theNewTextsExistInEveryLocale() throws Exception {
        for (String locale : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"}) {
            String xml = new String(Files.readAllBytes(new File("src/main/res/" + locale + "/strings.xml").toPath()),
                    StandardCharsets.UTF_8);
            for (String name : new String[]{"player_track_skipped", "player_stopped_after_errors", "player_nothing_playable",
                    "player_track_number"}) {
                assertTrue(locale + " lacks " + name, xml.contains("<string name=\"" + name + "\">"));
            }
            int at = xml.indexOf("<string name=\"player_track_skipped\">");
            String text = xml.substring(at, xml.indexOf("</string>", at));
            assertTrue(locale + " must show the name of the track", text.contains("%1$s"));
            int number = xml.indexOf("<string name=\"player_track_number\">");
            assertTrue(locale + " must show the position", xml.substring(number, xml.indexOf("</string>", number)).contains("%1$d"));
        }
    }
}
