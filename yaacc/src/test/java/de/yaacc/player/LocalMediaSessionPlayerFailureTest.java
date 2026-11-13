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

        player.trackFailed("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", "By The Way", true);

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

        player.trackFailed("ERROR_CODE_IO_UNSPECIFIED", title.toString(), true);

        String text = toasts.get(0);
        assertFalse(text, text.contains("\n"));
        assertFalse(text, text.contains("‮"));
        assertTrue(text, text.length() < 140);
        assertTrue(text, text.startsWith("text" + R.string.player_track_skipped + ":evil name"));
    }

    @Test
    public void theToastNeverHoldsTheErrorCodeOrAnAddress() {
        TestLocal player = new TestLocal();

        player.trackFailed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "Song", true);

        assertFalse(toasts.get(0).contains("ERROR_CODE"));
        assertFalse(toasts.get(0).contains("http"));
    }

    @Test
    public void thirdFailureInARowStopsThePlayerWithItsOwnMessage() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", "A", true);
        player.trackFailed("E", "B", true);
        player.trackFailed("E", "C", true);

        assertEquals(Arrays.asList("skip", "skip", "stop"), calls);
        assertEquals("text" + R.string.player_stopped_after_errors, toasts.get(toasts.size() - 1));
        assertEquals("two skip toasts and the stop message", 3, toasts.size());
    }

    @Test
    public void aFailingLastItemStopsThePlayerAtOnce() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", "Last", false);

        assertEquals(Arrays.asList("stop"), calls);
        assertEquals(Arrays.asList("text" + R.string.player_stopped_after_errors), toasts);
    }

    @Test
    public void aTrackThatStartsResetsTheCount() {
        TestLocal player = new TestLocal();
        player.trackFailed("E", "A", true);
        player.trackFailed("E", "B", true);

        player.trackStarted();
        player.trackFailed("E", "C", true);
        player.trackFailed("E", "D", true);

        assertEquals(Arrays.asList("skip", "skip", "skip", "skip"), calls);
    }

    @Test
    public void aStopByTheUserResetsTheCountToo() {
        TestLocal player = new TestLocal();
        player.trackFailed("E", "A", true);
        player.trackFailed("E", "B", true);

        player.stopItem(new PlayableItem());
        player.trackFailed("E", "C", true);

        assertEquals(Arrays.asList("skip", "skip", "skip"), calls);
    }

    @Test
    public void aTitleThatIsNotKnownIsShownAsAQuestionMark() {
        TestLocal player = new TestLocal();

        player.trackFailed("E", null, true);

        assertEquals("text" + R.string.player_track_skipped + ":?", toasts.get(0));
    }

    // ---- 11.11: playlist files do not enter the list of the local player

    private static PlayableItem item(String mime, String title) {
        PlayableItem item = new PlayableItem();
        item.setMimeType(mime);
        item.setTitle(title);
        return item;
    }

    @Test
    public void aPlaylistFileDoesNotEnterTheListOfThePlayer() {
        TestLocal player = new TestLocal();
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem list = item("audio/x-mpegurl", "By The Way");
        PlayableItem two = item("audio/mpeg", "Two");

        player.setItems(one, list, two);

        assertEquals(Arrays.asList(one, two), player.getItems());
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
    public void insertedPlaylistFilesAreLeftOut() {
        TestLocal player = new TestLocal();
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem two = item("audio/mpeg", "Two");
        player.setItems(one, two);
        PlayableItem between = item("audio/mpeg", "Between");

        player.insertItems(1, Arrays.asList(item("audio/x-mpegurl", "L"), between));

        assertEquals(Arrays.asList(one, between, two), player.getItems());
    }

    // ---- the texts

    @Test
    public void theNewTextsExistInEveryLocale() throws Exception {
        for (String locale : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"}) {
            String xml = new String(Files.readAllBytes(new File("src/main/res/" + locale + "/strings.xml").toPath()),
                    StandardCharsets.UTF_8);
            for (String name : new String[]{"player_track_skipped", "player_stopped_after_errors", "player_nothing_playable"}) {
                assertTrue(locale + " lacks " + name, xml.contains("<string name=\"" + name + "\">"));
            }
            int at = xml.indexOf("<string name=\"player_track_skipped\">");
            String text = xml.substring(at, xml.indexOf("</string>", at));
            assertTrue(locale + " must show the name of the track", text.contains("%1$s"));
        }
    }
}
