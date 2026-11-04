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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import de.yaacc.player.PlayMode.RepeatMode;
import de.yaacc.share.QueueShareWindow;
import de.yaacc.upnp.UpnpClient;

/**
 * A player of a picked queue binds the share of an item when it loads the item, holds only that
 * one, finds it by the item itself (the playlist can be edited), lets go of it when it stops or
 * is destroyed, and does not run through a whole list of items that cannot be shared.
 */
public class AbstractPlayerShareLeaseTest {

    private PlayMode startMode;
    private Context context;
    /** Queue indexes of the bound shares. */
    private final List<Integer> bound = new CopyOnWriteArrayList<>();
    private final List<Integer> attempts = new CopyOnWriteArrayList<>();
    private final List<Integer> failing = new ArrayList<>();
    private int maxBound;
    private final java.util.concurrent.atomic.AtomicInteger stopped = new java.util.concurrent.atomic.AtomicInteger();

    private class TestPlayer extends AbstractPlayer {
        TestPlayer() {
            super(upnpClient());
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
            return 4713;
        }

        @Override
        protected void stopItem(PlayableItem playableItem) {
            stopped.incrementAndGet();
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
        startMode = PlayMode.of(RepeatMode.OFF, false);
    }

    private PlayableItem[] queueItems(int count) {
        PlayableItem[] items = new PlayableItem[count];
        for (int i = 0; i < count; i++) {
            items[i] = new PlayableItem();
            items[i].setTitle("item " + i);
        }
        return items;
    }

    private TestPlayer player(PlayableItem[] items, boolean withLease) {
        TestPlayer player = new TestPlayer();
        if (withLease) {
            QueueShareWindow window = new QueueShareWindow(Arrays.asList(items), index -> {
                attempts.add(index);
                if (failing.contains(index)) {
                    return null;
                }
                bound.add(index);
                maxBound = Math.max(maxBound, bound.size());
                return () -> bound.remove(Integer.valueOf(index));
            });
            player.setShareLease(window.newLease());
        }
        player.setItems(items);
        return player;
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("condition not reached in time", condition.getAsBoolean());
    }

    @Test
    public void loadingAnItemBindsOnlyThatItem() {
        TestPlayer player = player(queueItems(5), true);
        assertNotNull(player.loadItem(0));
        assertEquals(Arrays.asList(0), bound);
    }

    @Test
    public void goingThroughTheListHoldsOneShareAtATime() {
        TestPlayer player = player(queueItems(20), true);
        for (int i = 0; i < 20; i++) {
            assertNotNull(player.loadItem(i));
        }
        assertEquals(1, maxBound);
        assertEquals(Arrays.asList(19), bound);
    }

    @Test
    public void anItemThatCannotBeSharedGivesNoLoadedItem() {
        failing.add(2);
        TestPlayer player = player(queueItems(5), true);
        assertNull(player.loadItem(2));
        assertNotNull(player.loadItem(3));
    }

    @Test
    public void withoutALeaseNothingIsBound() {
        TestPlayer player = player(queueItems(5), false);
        assertNotNull(player.loadItem(0));
        assertTrue(bound.isEmpty());
    }

    @Test
    public void removingAnItemFromThePlaylistDoesNotShiftTheShares() {
        TestPlayer player = player(queueItems(4), true);
        player.removeItem(0);
        // the item at position 0 is now the queue's item 1
        assertNotNull(player.loadItem(0));
        assertEquals(Arrays.asList(1), bound);
        assertNotNull(player.loadItem(2));
        assertEquals(Arrays.asList(3), bound);
    }

    @Test
    public void movingAnItemInThePlayOrderDoesNotShiftTheShares() {
        TestPlayer player = player(queueItems(4), true);
        player.moveInPlayOrder(3, 0);
        // without shuffle the list moves: the last item of the queue is now at position 0
        assertNotNull(player.loadItem(0));
        assertEquals(Arrays.asList(3), bound);
    }

    @Test
    public void anItemAddedToThePlaylistLaterNeedsNoShare() {
        TestPlayer player = player(queueItems(2), true);
        PlayableItem added = new PlayableItem();
        added.setTitle("added");
        player.addItems(Arrays.asList(added));
        assertNotNull(player.loadItem(2));
        assertTrue(bound.isEmpty());
        assertNotNull(player.loadItem(1));
        assertEquals(Arrays.asList(1), bound);
    }

    @Test
    public void stoppingReleasesTheShareAndThePlayerCanLoadAgain() throws Exception {
        TestPlayer player = player(queueItems(3), true);
        assertNotNull(player.loadItem(0));
        assertEquals(1, bound.size());
        player.stop();
        waitFor(bound::isEmpty);
        assertNotNull(player.loadItem(1));
        assertEquals(Arrays.asList(1), bound);
    }

    @Test
    public void destroyingReleasesTheShareForGood() throws Exception {
        TestPlayer player = player(queueItems(3), true);
        assertNotNull(player.loadItem(0));
        // onDestroy() calls this (the rest of onDestroy needs a real notification manager)
        player.closeShares();
        assertTrue(bound.isEmpty());
        assertNull(player.loadItem(1));
        assertTrue(bound.isEmpty());
    }

    @Test
    public void aPlayerThatWasDestroyedIsNotReportedAsAFailureOfItsItems() throws Exception {
        for (int i = 0; i < 4; i++) {
            failing.add(i);
        }
        TestPlayer player = player(queueItems(4), true);
        player.closeShares();
        assertNull(player.loadItem(0));
        player.play();
        Thread.sleep(700);
        // no attempt to share, nothing skipped, nobody stopped
        assertTrue(attempts.isEmpty());
        assertEquals(0, stopped.get());
    }

    private void runIntoFailures(RepeatMode repeat) throws Exception {
        startMode = PlayMode.of(repeat, false);
        for (int i = 0; i < 10; i++) {
            failing.add(i);
        }
        TestPlayer player = player(queueItems(10), true);
        player.play();
        waitFor(() -> attempts.size() >= AbstractPlayer.MAX_CONSECUTIVE_BIND_FAILURES);
        Thread.sleep(600);
        // it gave up after the limit and did not run through the whole list (or loop on one item)
        assertEquals(AbstractPlayer.MAX_CONSECUTIVE_BIND_FAILURES, attempts.size());
        assertTrue(bound.isEmpty());
    }

    @Test
    public void aListOfUnsharableItemsStopsAfterThreeAttemptsRepeatOff() throws Exception {
        runIntoFailures(RepeatMode.OFF);
    }

    @Test
    public void aListOfUnsharableItemsStopsAfterThreeAttemptsRepeatAll() throws Exception {
        runIntoFailures(RepeatMode.ALL);
    }

    @Test
    public void aListOfUnsharableItemsStopsAfterThreeAttemptsRepeatOne() throws Exception {
        runIntoFailures(RepeatMode.ONE);
    }

    @Test
    public void anUnsharableItemIsSkippedAndThePlayerGoesOn() throws Exception {
        failing.add(0);
        TestPlayer player = player(queueItems(4), true);
        player.play();
        waitFor(() -> bound.contains(1));
        assertFalse(bound.contains(0));
    }
}
