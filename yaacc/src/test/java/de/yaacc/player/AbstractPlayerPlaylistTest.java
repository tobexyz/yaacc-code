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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.player.PlayMode.RepeatMode;
import de.yaacc.upnp.UpnpClient;

/**
 * The playlist screen shows the play order and edits it: with shuffle a move changes the play order (the
 * list is not touched), without shuffle the list; a delete removes the item from both; what plays or came
 * before it is locked.
 */
public class AbstractPlayerPlaylistTest {

    private PlayMode startMode;
    private Context context;
    private final List<String> events = new ArrayList<>();
    private final List<String> hooks = new ArrayList<>();

    private class TestPlayer extends AbstractPlayer {
        TestPlayer() {
            super(upnpClient());
            addPropertyChangeListener(event -> events.add(event.getPropertyName()));
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return startMode;
        }

        @Override
        protected void showNotificationInternal() {
        }

        @Override
        protected int getNotificationId() {
            return 4712;
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

        @Override
        protected void onItemMoved(int fromIndex, int toIndex) {
            hooks.add("moved " + fromIndex + ">" + toIndex);
        }

        @Override
        protected void onItemRemoved(int index) {
            hooks.add("removed " + index);
        }

        @Override
        protected void onPlayOrderMoved() {
            hooks.add("order");
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
        startMode = PlayMode.of(RepeatMode.ALL, false);
    }

    private TestPlayer player(int count) {
        TestPlayer player = new TestPlayer();
        PlayableItem[] items = new PlayableItem[count];
        for (int i = 0; i < count; i++) {
            items[i] = new PlayableItem();
            items[i].setTitle("item " + i);
        }
        player.setItems(items);
        events.clear();
        return player;
    }

    private static List<String> titles(Player player) {
        List<String> titles = new ArrayList<>();
        for (PlayableItem item : player.getItems()) {
            titles.add(item.getTitle());
        }
        return titles;
    }

    /** The titles in the order the playlist screen shows them. */
    private static List<String> shown(Player player) {
        List<String> titles = new ArrayList<>();
        for (int index : player.getPlayOrder()) {
            titles.add(player.getItems().get(index).getTitle());
        }
        return titles;
    }

    // --- what is shown

    @Test
    public void withoutShuffleTheScreenShowsTheListOrder() {
        TestPlayer player = player(4);

        assertArrayEquals(new int[]{0, 1, 2, 3}, player.getPlayOrder());
        assertEquals(titles(player), shown(player));
    }

    @Test
    public void withShuffleTheScreenShowsThePlayOrderAndTheListKeepsItsOrder() {
        startMode = PlayMode.of(RepeatMode.OFF, true);
        TestPlayer player = player(8);

        assertEquals(Arrays.asList("item 0", "item 1", "item 2", "item 3", "item 4", "item 5", "item 6", "item 7"), titles(player));
        int[] order = player.getPlayOrder();
        assertEquals(8, order.length);
        // the order starts with the item that starts, and the next item is the second one shown
        assertEquals(player.getCurrentItemIndex(), order[0]);
        assertEquals(order[1], player.getNextItemIndex());
    }

    @Test
    public void switchingShuffleOnShowsTheOrderStartingWithTheCurrentItemAndOffTheListOrder() {
        TestPlayer player = player(6);
        player.setCurrentIndex(3);

        player.setPlayMode(player.getPlayMode().withShuffle(true));

        assertEquals(3, player.getPlayOrder()[0]);
        assertEquals(6, player.getPlayOrder().length);

        player.setPlayMode(player.getPlayMode().withShuffle(false));

        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5}, player.getPlayOrder());
    }

    // --- move

    @Test
    public void withoutShuffleAMoveChangesTheList() {
        TestPlayer player = player(4);

        assertTrue(player.moveInPlayOrder(3, 1));

        assertEquals(Arrays.asList("item 0", "item 3", "item 1", "item 2"), titles(player));
        assertEquals(titles(player), shown(player));
        assertEquals(Arrays.asList("moved 3>1"), hooks);
        assertTrue(events.contains(Player.PROPERTY_PLAYLIST));
    }

    @Test
    public void withShuffleAMoveChangesThePlayOrderAndNotTheList() {
        startMode = PlayMode.of(RepeatMode.ALL, true);
        TestPlayer player = player(6);
        player.setPlaying(true);
        List<String> listBefore = titles(player);
        List<String> shownBefore = shown(player);

        assertTrue(player.moveInPlayOrder(5, 2));

        assertEquals(listBefore, titles(player));
        List<String> expected = new ArrayList<>(shownBefore);
        expected.add(2, expected.remove(5));
        assertEquals(expected, shown(player));
        assertEquals(Arrays.asList("order"), hooks);
        assertTrue(events.contains(Player.PROPERTY_PLAYLIST));
    }

    @Test
    public void aMovedPlayOrderIsTheOneThatPlays() {
        startMode = PlayMode.of(RepeatMode.OFF, true);
        TestPlayer player = player(5);
        player.setPlaying(true);
        player.moveInPlayOrder(4, 1);
        int[] order = player.getPlayOrder();

        assertEquals(order[1], player.getNextItemIndex());
        assertTrue(player.moveToNext(false));
        assertEquals(order[1], player.getCurrentItemIndex());
        assertTrue(player.moveToNext(false));
        assertEquals(order[2], player.getCurrentItemIndex());
    }

    @Test
    public void thePlayingItemAndWhatCameBeforeItInTheShownOrderAreLocked() {
        startMode = PlayMode.of(RepeatMode.ALL, true);
        TestPlayer player = player(6);
        player.setPlaying(true);
        player.moveToNext(false);
        player.moveToNext(false); // the current item is at position 2

        assertTrue(player.isPlaylistPositionLocked(0));
        assertTrue(player.isPlaylistPositionLocked(2));
        assertFalse(player.isPlaylistPositionLocked(3));
        int[] before = player.getPlayOrder();
        assertFalse(player.moveInPlayOrder(3, 1));
        assertFalse(player.moveInPlayOrder(1, 4));
        assertFalse(player.removeItem(before[2]));
        assertArrayEquals(before, player.getPlayOrder());
        assertTrue(hooks.isEmpty());
    }

    @Test
    public void aPausedPlayerLocksToo() {
        TestPlayer player = player(4);
        player.setPaused(true);

        assertTrue(player.isPlaylistPositionLocked(0));
        assertFalse(player.moveInPlayOrder(0, 2));
        assertTrue(player.moveInPlayOrder(2, 3));
    }

    @Test
    public void aStoppedPlayerHasNothingLockedAndStartsWithTheFirstShownItem() {
        startMode = PlayMode.of(RepeatMode.ALL, true);
        TestPlayer player = player(5);

        assertFalse(player.isPlaylistPositionLocked(0));
        assertTrue(player.moveInPlayOrder(3, 0));

        assertEquals(player.getPlayOrder()[0], player.getCurrentItemIndex());
    }

    @Test
    public void invalidPositionsMoveNothing() {
        TestPlayer player = player(3);

        assertFalse(player.moveInPlayOrder(1, 1));
        assertFalse(player.moveInPlayOrder(-1, 1));
        assertFalse(player.moveInPlayOrder(0, 3));
        assertTrue(events.isEmpty());
    }

    // --- delete

    @Test
    public void deleteRemovesTheItemFromTheListAndKeepsTheShuffledOrderOfTheOthers() {
        startMode = PlayMode.of(RepeatMode.ALL, true);
        TestPlayer player = player(6);
        List<String> shownBefore = shown(player);
        String removed = shownBefore.get(3);
        int removedIndex = player.getItems().indexOf(findByTitle(player, removed));

        assertTrue(player.removeItem(removedIndex));

        List<String> expected = new ArrayList<>(shownBefore);
        expected.remove(removed);
        assertEquals(expected, shown(player));
        assertEquals(5, player.getItems().size());
        assertFalse(titles(player).contains(removed));
        assertEquals(Arrays.asList("removed " + removedIndex), hooks);
        assertTrue(events.contains(Player.PROPERTY_PLAYLIST));
    }

    private static PlayableItem findByTitle(Player player, String title) {
        for (PlayableItem item : player.getItems()) {
            if (title.equals(item.getTitle())) {
                return item;
            }
        }
        throw new AssertionError(title);
    }

    @Test
    public void deletingAnItemBeforeTheCurrentOneInTheListKeepsTheCurrentItem() {
        // possible with shuffle: it comes after the current item in the play order
        startMode = PlayMode.of(RepeatMode.ALL, true);
        TestPlayer player = player(6);
        player.setPlaying(true);
        // make the play order start with item 4 and have item 1 behind it
        player.setCurrentIndex(4);
        player.setPlayMode(PlayMode.of(RepeatMode.ALL, false));
        player.setPlayMode(PlayMode.of(RepeatMode.ALL, true));
        PlayableItem current = player.getItems().get(4);
        assertEquals(4, player.getPlayOrder()[0]);

        assertTrue(player.removeItem(1));

        assertEquals(3, player.getCurrentItemIndex());
        assertEquals(current, player.getItems().get(player.getCurrentItemIndex()));
        assertEquals(3, player.getPlayOrder()[0]);
    }

    @Test
    public void deleteWithoutShuffleKeepsTheListOrder() {
        TestPlayer player = player(4);

        assertTrue(player.removeItem(1));

        assertEquals(Arrays.asList("item 0", "item 2", "item 3"), titles(player));
        assertArrayEquals(new int[]{0, 1, 2}, player.getPlayOrder());
    }

    @Test
    public void aStoppedPlayerStartsWithTheFirstItemAfterTheFirstWasDeleted() {
        TestPlayer player = player(3);

        assertTrue(player.removeItem(0));

        assertEquals(0, player.getCurrentItemIndex());
        assertEquals("item 1", player.getCurrentItemTitle());
    }

    @Test
    public void deleteOfAnUnknownIndexDoesNothing() {
        TestPlayer player = player(3);

        assertFalse(player.removeItem(3));
        assertFalse(player.removeItem(-1));

        assertEquals(3, player.getItems().size());
        assertTrue(events.isEmpty());
    }

    // --- the screen is told

    @Test
    public void addedItemsAndANewListAreAnnounced() {
        TestPlayer player = player(2);

        player.addItems(Arrays.asList(new PlayableItem()));
        assertEquals(Arrays.asList(Player.PROPERTY_PLAYLIST), events);
        assertEquals(3, player.getPlayOrder().length);

        events.clear();
        player.setItems(new PlayableItem());
        assertTrue(events.contains(Player.PROPERTY_PLAYLIST));
    }

    @Test
    public void addedItemsAreInThePlayOrderBehindTheCurrentItemWithShuffle() {
        startMode = PlayMode.of(RepeatMode.ALL, true);
        TestPlayer player = player(4);
        player.setPlaying(true);
        int current = player.getCurrentItemIndex();

        player.addItems(Arrays.asList(new PlayableItem(), new PlayableItem()));

        int[] order = player.getPlayOrder();
        assertEquals(6, order.length);
        assertEquals(current, order[0]);
    }

    @Test
    public void aModeChangeIsAnnouncedAsPlayMode() {
        TestPlayer player = player(2);

        player.setPlayMode(player.getPlayMode().withShuffle(true));

        assertTrue(events.contains(Player.PROPERTY_PLAY_MODE));
    }
}
