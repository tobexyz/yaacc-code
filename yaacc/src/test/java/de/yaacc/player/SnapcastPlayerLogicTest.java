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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import de.yaacc.R;
import de.yaacc.multiroom.feed.FeedError;
import de.yaacc.multiroom.feed.SnapcastStreams;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** The decisions of the Snapcast player that need no Android. */
public class SnapcastPlayerLogicTest {

    @Test
    public void aPauseOfExoPlayerDiscardsWhatTheFeedHasQueuedWhateverTheFeedDoes() {
        assertEquals(SnapcastPlayerLogic.ExoAction.DISCARD, SnapcastPlayerLogic.onPlayWhenReadyChanged(false, true));
        assertEquals(SnapcastPlayerLogic.ExoAction.DISCARD, SnapcastPlayerLogic.onPlayWhenReadyChanged(false, false));
    }

    @Test
    public void aResumeWithAnOpenFeedNeedsNothing() {
        assertEquals(SnapcastPlayerLogic.ExoAction.NONE, SnapcastPlayerLogic.onPlayWhenReadyChanged(true, true));
    }

    @Test
    public void aResumeFromOutsideWhileTheFeedIsClosedOpensTheFeed() {
        assertEquals(SnapcastPlayerLogic.ExoAction.START_FEED, SnapcastPlayerLogic.onPlayWhenReadyChanged(true, false));
    }

    @Test
    public void everyKindOfErrorHasAText() {
        Set<Integer> texts = new HashSet<>();
        for (FeedError.Kind kind : FeedError.Kind.values()) {
            int text = SnapcastPlayerLogic.textFor(kind);
            assertNotEquals(kind.toString(), 0, text);
            texts.add(text);
        }
        assertTrue("the kinds are told apart where it helps the user: " + texts.size(), texts.size() >= 7);
    }

    @Test
    public void theKindsThatNeedTheSameAdviceShareAText() {
        assertEquals(SnapcastPlayerLogic.textFor(FeedError.Kind.NO_CONTROL), SnapcastPlayerLogic.textFor(FeedError.Kind.SERVER_FAILED));
        assertEquals(SnapcastPlayerLogic.textFor(FeedError.Kind.FEED_FAILED), SnapcastPlayerLogic.textFor(FeedError.Kind.CONTROL_LOST));
        assertEquals(SnapcastPlayerLogic.textFor(FeedError.Kind.REFUSED), SnapcastPlayerLogic.textFor(FeedError.Kind.CONFLICT));
        assertEquals(R.string.snapcast_feed_error_refused, SnapcastPlayerLogic.textFor(FeedError.Kind.NO_FREE_PORT));
        assertEquals(R.string.snapcast_feed_error_unauthorized, SnapcastPlayerLogic.textFor(FeedError.Kind.UNAUTHORIZED));
        assertEquals(R.string.snapcast_feed_error_busy, SnapcastPlayerLogic.textFor(FeedError.Kind.BUSY));
        assertEquals(R.string.snapcast_feed_error_player, SnapcastPlayerLogic.textFor(FeedError.Kind.PLAYER_FAILED));
        assertEquals(R.string.snapcast_feed_error_group_gone, SnapcastPlayerLogic.textFor(FeedError.Kind.GROUP_GONE));
        assertEquals(R.string.snapcast_feed_error_connect, SnapcastPlayerLogic.textFor(FeedError.Kind.CONNECT_FAILED));
    }

    @Test
    public void theHintKeysOfTheStreamHandlingMapToStringsAndAnythingElseToNone() {
        assertEquals(R.string.snapcast_feed_static_source_hint, SnapcastPlayerLogic.hintFor(SnapcastStreams.HINT_STATIC_SOURCE));
        assertEquals(R.string.snapcast_feed_authorization_hint, SnapcastPlayerLogic.hintFor(SnapcastStreams.HINT_AUTHORIZATION));
        assertEquals(0, SnapcastPlayerLogic.hintFor(null));
        assertEquals(0, SnapcastPlayerLogic.hintFor("something else"));
    }

    @Test
    public void theMessageHasTheTextTheServersReasonAndTheHintInThatOrder() {
        FeedError error = new FeedError(FeedError.Kind.REFUSED, "tcp sources are off", SnapcastStreams.HINT_STATIC_SOURCE);

        String message = SnapcastPlayerLogic.errorMessage(error, id -> "[" + id + "]");

        assertEquals("[" + R.string.snapcast_feed_error_refused + "] (tcp sources are off)\n["
                + R.string.snapcast_feed_static_source_hint + "]", message);
    }

    @Test
    public void aMessageWithoutReasonAndHintIsJustTheText() {
        String message = SnapcastPlayerLogic.errorMessage(FeedError.of(FeedError.Kind.BUSY), id -> "text " + id);

        assertEquals("text " + R.string.snapcast_feed_error_busy, message);
    }

    @Test
    public void theTextsOfEveryErrorKindExistInEveryLocale() throws Exception {
        String[] names = {"snapcast_play_audio_only", "snapcast_play_delay_note", "snapcast_feed_error_unreachable",
                "snapcast_feed_error_refused", "snapcast_feed_error_unauthorized", "snapcast_feed_error_group_gone",
                "snapcast_feed_error_connect", "snapcast_feed_error_busy", "snapcast_feed_error_lost", "snapcast_feed_error_player",
                "snapcast_feed_static_source_hint", "snapcast_feed_authorization_hint"};
        for (String locale : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"}) {
            java.io.File file = new java.io.File("src/main/res/" + locale + "/strings.xml");
            String xml = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            for (String name : names) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(locale + " lacks " + name, matcher.find());
                assertTrue(locale + " " + name + " defined twice", !matcher.find());
            }
        }
    }

    @Test
    public void thePlayerIdIsAboveTheFixedIdsOfTheAppAndNeverZero() {
        Random random = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            int id = SnapcastPlayerLogic.playerIdFrom(random.nextInt());
            assertTrue("id " + id, id >= 100);
        }
        for (int raw : new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 1, 10}) {
            assertTrue(SnapcastPlayerLogic.playerIdFrom(raw) >= 100);
        }
        for (de.yaacc.util.NotificationId fixed : de.yaacc.util.NotificationId.values()) {
            assertTrue(fixed.getId() < 100);
        }
    }

    @Test
    public void theGermanTextsAreTranslatedNotCopiedFromEnglish() throws Exception {
        String english = new String(java.nio.file.Files.readAllBytes(new java.io.File("src/main/res/values/strings.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String german = new String(java.nio.file.Files.readAllBytes(new java.io.File("src/main/res/values-de/strings.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String name : new String[]{"snapcast_play_audio_only", "snapcast_feed_error_unreachable", "snapcast_feed_error_lost"}) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>");
            java.util.regex.Matcher en = pattern.matcher(english);
            java.util.regex.Matcher de = pattern.matcher(german);
            assertTrue(en.find() && de.find());
            assertNotEquals(name, en.group(1), de.group(1));
        }
    }
}
