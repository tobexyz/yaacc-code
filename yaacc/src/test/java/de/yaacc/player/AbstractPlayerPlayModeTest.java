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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.player.PlayMode.RepeatMode;
import de.yaacc.upnp.UpnpClient;

/**
 * How {@link AbstractPlayer} (the base of the AVTransport and the multi content player) moves from item to
 * item by the play mode of the player: the settings only give the start mode, the end of an item repeats,
 * wraps or ends, shuffle plays every item once.
 */
public class AbstractPlayerPlayModeTest {

    private Context context;
    private PlayMode startMode;

    /** Records the property changes. */
    private final List<Object[]> modeChanges = new ArrayList<>();

    private class TestPlayer extends AbstractPlayer {
        TestPlayer() {
            super(AbstractPlayerPlayModeTest.this.upnpClient());
            addPropertyChangeListener(event -> {
                if (Player.PROPERTY_PLAY_MODE.equals(event.getPropertyName())) {
                    modeChanges.add(new Object[]{event.getOldValue(), event.getNewValue()});
                }
            });
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return startMode;
        }

        @Override
        protected void showNotificationInternal() {
            // no notification in a unit test
        }

        @Override
        protected int getNotificationId() {
            return 4711;
        }

        @Override
        protected void stopItem(PlayableItem playableItem) {
        }

        @Override
        protected Object loadItem(PlayableItem playableItem) {
            return playableItem;
        }

        @Override
        protected void startItem(PlayableItem playableItem, Object loadedItem, int index) {
        }

        @Override
        public long getCurrentPosition() {
            return 0;
        }

        @Override
        public void seekTo(long millisecondsFromStart) {
        }
    }

    private UpnpClient upnpClient() {
        UpnpClient client = mock(UpnpClient.class);
        when(client.getContext()).thenReturn(context);
        return client;
    }

    @Before
    public void setUp() {
        context = mock(Context.class);
        startMode = PlayMode.fromSettings(true, false);
    }

    private TestPlayer player(int items) {
        TestPlayer player = new TestPlayer();
        player.setItems(titled(0, items));
        return player;
    }

    private static PlayableItem[] titled(int from, int count) {
        PlayableItem[] items = new PlayableItem[count];
        for (int i = 0; i < count; i++) {
            items[i] = new PlayableItem();
            items[i].setTitle("item " + (from + i));
        }
        return items;
    }

    // --- the start mode comes from the settings, once

    @Test
    public void aNewPlayerStartsWithTheModeTheSettingsGive() {
        startMode = PlayMode.fromSettings(false, true);

        assertEquals(PlayMode.of(RepeatMode.OFF, true), new TestPlayer().getPlayMode());
    }

    @Test
    public void theSettingsAreReadOnceNotOnEveryAsk() {
        TestPlayer player = new TestPlayer();
        player.getPlayMode();
        startMode = PlayMode.of(RepeatMode.ONE, true);

        assertEquals(PlayMode.of(RepeatMode.ALL, false), player.getPlayMode());
    }

    @Test
    public void theRealDefaultComesFromTheTwoSettings() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getPackageName()).thenReturn("de.yaacc");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(context.getString(de.yaacc.R.string.settings_replay_playlist_chkbx)).thenReturn("replay");
        when(context.getString(de.yaacc.R.string.settings_music_player_shuffle_chkbx)).thenReturn("shuffle");
        when(preferences.getBoolean("replay", false)).thenReturn(false);
        when(preferences.getBoolean("shuffle", false)).thenReturn(true);

        assertEquals(PlayMode.of(RepeatMode.OFF, true), PlayModeSettings.defaultPlayMode(context));
    }

    @Test
    public void aChangedModeIsNeverWrittenBackToTheSettings() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        TestPlayer player = player(3);

        player.setPlayMode(PlayMode.of(RepeatMode.ONE, true));
        player.setPlayMode(PlayMode.NORMAL);

        verify(preferences, never()).edit();
    }

    @Test
    public void twoPlayersKeepTheirOwnModes() {
        TestPlayer first = player(3);
        TestPlayer second = player(3);

        first.setPlayMode(PlayMode.of(RepeatMode.ONE, false));

        assertEquals(PlayMode.of(RepeatMode.ONE, false), first.getPlayMode());
        assertEquals(PlayMode.of(RepeatMode.ALL, false), second.getPlayMode());
    }

    // --- changing the mode

    @Test
    public void changingTheModeFiresOneChangeWithOldAndNewValue() {
        TestPlayer player = player(3);
        PlayMode changed = PlayMode.of(RepeatMode.ONE, false);

        player.setPlayMode(changed);
        player.setPlayMode(changed);
        player.setPlayMode(null);

        assertEquals(1, modeChanges.size());
        assertEquals(PlayMode.of(RepeatMode.ALL, false), modeChanges.get(0)[0]);
        assertEquals(changed, modeChanges.get(0)[1]);
        assertSame(changed, player.getPlayMode());
    }

    @Test
    public void isShufflePlayFollowsTheMode() {
        TestPlayer player = player(3);
        assertFalse(player.isShufflePlay());

        player.setPlayMode(player.getPlayMode().withShuffle(true));

        assertTrue(player.isShufflePlay());
    }

    // --- the end of an item

    @Test
    public void repeatAllWrapsAfterTheLastItem() {
        TestPlayer player = player(3);
        player.setCurrentIndex(2);

        assertTrue(player.moveToNext(true));
        assertEquals(0, player.getCurrentItemIndex());
    }

    @Test
    public void repeatOffEndsAfterTheLastItemAndKeepsTheIndex() {
        startMode = PlayMode.fromSettings(false, false);
        TestPlayer player = player(3);
        player.setCurrentIndex(1);

        assertTrue(player.moveToNext(true));
        assertEquals(2, player.getCurrentItemIndex());
        assertFalse(player.moveToNext(true));
        assertEquals(2, player.getCurrentItemIndex());
    }

    @Test
    public void repeatOneRepeatsAnEndedItemButSkippingGoesOn() {
        startMode = PlayMode.of(RepeatMode.ONE, false);
        TestPlayer player = player(3);
        player.setCurrentIndex(1);

        assertTrue(player.moveToNext(true));
        assertEquals(1, player.getCurrentItemIndex());
        assertTrue(player.moveToNext(false));
        assertEquals(2, player.getCurrentItemIndex());
    }

    @Test
    public void changingRepeatOneToOffOnTheRunEndsTheListAtItsEnd() {
        TestPlayer player = player(2);
        player.setCurrentIndex(1);
        player.setPlayMode(PlayMode.of(RepeatMode.ONE, false));
        assertTrue(player.moveToNext(true));
        assertEquals(1, player.getCurrentItemIndex());

        player.setPlayMode(PlayMode.of(RepeatMode.OFF, false));

        assertFalse(player.moveToNext(true));
    }

    @Test
    public void previousGoesBackAndWrapsToTheLastItem() {
        TestPlayer player = player(3);
        player.setCurrentIndex(1);

        player.moveToPrevious();
        assertEquals(0, player.getCurrentItemIndex());
        player.moveToPrevious();
        assertEquals(2, player.getCurrentItemIndex());
    }

    @Test
    public void previousOfAnEmptyPlaylistStaysAtZero() {
        TestPlayer player = new TestPlayer();

        player.moveToPrevious();

        assertEquals(0, player.getCurrentItemIndex());
    }

    // --- shuffle

    @Test
    public void aShuffledPlaylistKeepsTheListOrderAndStartsAtTheFirstOfTheShuffleOrder() {
        startMode = PlayMode.fromSettings(false, true);
        TestPlayer player = player(8);

        List<String> titles = new ArrayList<>();
        for (PlayableItem item : player.getItems()) {
            titles.add(item.getTitle());
        }
        assertEquals("item 0", titles.get(0));
        assertEquals("item 7", titles.get(7));
        assertEquals(player.getPlayOrder()[0], player.getCurrentItemIndex());
    }

    @Test
    public void shuffleWithRepeatOffPlaysEveryItemOnce() {
        startMode = PlayMode.fromSettings(false, true);
        TestPlayer player = player(8);

        Set<Integer> played = new HashSet<>();
        played.add(player.getCurrentItemIndex());
        while (player.moveToNext(true)) {
            assertTrue("each item once", played.add(player.getCurrentItemIndex()));
        }

        assertEquals(8, played.size());
    }

    @Test
    public void switchingShuffleOnKeepsTheCurrentItemAndPlaysTheRestOnce() {
        startMode = PlayMode.fromSettings(false, false);
        TestPlayer player = player(8);
        player.setCurrentIndex(3);

        player.setPlayMode(player.getPlayMode().withShuffle(true));

        assertEquals(3, player.getCurrentItemIndex());
        assertEquals(3, player.getPlayOrder()[0]);
        Set<Integer> played = new HashSet<>();
        played.add(3);
        while (player.moveToNext(true)) {
            played.add(player.getCurrentItemIndex());
        }
        assertEquals(8, played.size());
    }

    @Test
    public void switchingShuffleOffKeepsTheCurrentItemAndGoesOnInListOrder() {
        startMode = PlayMode.fromSettings(true, true);
        TestPlayer player = player(8);
        player.moveToNext(true);
        int current = player.getCurrentItemIndex();

        player.setPlayMode(player.getPlayMode().withShuffle(false));

        assertEquals(current, player.getCurrentItemIndex());
        assertEquals(Arrays.toString(new int[]{0, 1, 2, 3, 4, 5, 6, 7}), Arrays.toString(player.getPlayOrder()));
    }

    @Test
    public void previousWalksBackThroughTheShuffleOrder() {
        startMode = PlayMode.fromSettings(true, true);
        TestPlayer player = player(8);
        List<Integer> forward = new ArrayList<>();
        forward.add(player.getCurrentItemIndex());
        for (int i = 0; i < 4; i++) {
            player.moveToNext(false);
            forward.add(player.getCurrentItemIndex());
        }

        List<Integer> backward = new ArrayList<>();
        backward.add(player.getCurrentItemIndex());
        for (int i = 0; i < 4; i++) {
            player.moveToPrevious();
            backward.add(player.getCurrentItemIndex());
        }

        java.util.Collections.reverse(backward);
        assertEquals(forward, backward);
    }

    @Test
    public void itemsAddedDuringShuffledPlaybackAreAllPlayed() {
        startMode = PlayMode.fromSettings(false, true);
        TestPlayer player = player(4);
        player.moveToNext(true);
        List<PlayableItem> more = new ArrayList<>(Arrays.asList(titled(4, 3)));

        player.addItems(more);

        Set<Integer> played = new HashSet<>();
        played.add(player.getCurrentItemIndex());
        while (player.moveToNext(true)) {
            played.add(player.getCurrentItemIndex());
        }
        // the one item that was already played is not played again, the three new ones are
        assertTrue(played.containsAll(Arrays.asList(4, 5, 6)));
    }

    @Test
    public void theNextItemTitleFollowsThePlayOrder() {
        startMode = PlayMode.fromSettings(false, true);
        TestPlayer player = player(5);

        int next = player.getPlayOrder()[1];

        assertEquals("item " + next, player.getNextItemTitle());
        assertEquals(next, player.getNextItemIndex());
    }

    @Test
    public void theNextItemTitleIsEmptyAtTheEndOfTheList() {
        TestPlayer player = player(3);
        player.setCurrentIndex(2);

        assertEquals("", player.getNextItemTitle());
        assertEquals(PlayOrder.NONE, player.getNextItemIndex());
    }

    @Test
    public void theNextItemIsTheNextInListOrderWithoutShuffle() {
        TestPlayer player = player(3);

        assertEquals("item 1", player.getNextItemTitle());
        assertEquals(PlayOrder.NONE, player.getPreviousItemIndex());
    }

    @Test
    public void newItemsReplaceTheOldOnesAndTheOrder() {
        TestPlayer player = player(5);
        player.setCurrentIndex(4);

        player.setItems(titled(10, 2));

        assertEquals(2, player.getItems().size());
        assertEquals(2, player.getPlayOrder().length);
        assertEquals("item 10", player.getItems().get(0).getTitle());
    }
}
