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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import de.yaacc.player.PlayMode.RepeatMode;

/** Which item plays after, before and instead of the current one: repeat modes, shuffle and playlist changes. */
public class PlayOrderTest {

    private static final int NONE = PlayOrder.NONE;

    private static PlayOrder order(int size, boolean shuffle) {
        PlayOrder order = new PlayOrder(new Random(42));
        order.reset(size, 0, shuffle);
        return order;
    }

    /** Plays through from the current item until the order says the end and returns the items. */
    private static List<Integer> playThrough(PlayOrder order, int start) {
        List<Integer> played = new ArrayList<>();
        int current = start;
        played.add(current);
        for (int guard = 0; guard < 1000; guard++) {
            int next = order.next(current, RepeatMode.OFF, true);
            if (next == NONE) {
                return played;
            }
            played.add(next);
            current = next;
        }
        throw new AssertionError("never ends");
    }

    // --- without shuffle

    @Test
    public void withoutShuffleTheListOrderIsPlayed() {
        PlayOrder order = order(4, false);

        assertArrayEquals(new int[]{0, 1, 2, 3}, order.toArray());
        assertEquals(Arrays.asList(0, 1, 2, 3), playThrough(order, 0));
    }

    @Test
    public void repeatOffEndsAfterTheLastItem() {
        PlayOrder order = order(3, false);

        assertEquals(NONE, order.next(2, RepeatMode.OFF, true));
        assertEquals(NONE, order.next(2, RepeatMode.OFF, false));
    }

    @Test
    public void repeatAllWrapsAroundToTheFirstItem() {
        PlayOrder order = order(3, false);

        assertEquals(0, order.next(2, RepeatMode.ALL, true));
        assertEquals(0, order.next(2, RepeatMode.ALL, false));
        assertEquals(2, order.next(1, RepeatMode.ALL, true));
    }

    @Test
    public void repeatOneRepeatsAnEndedItem() {
        PlayOrder order = order(3, false);

        assertEquals(1, order.next(1, RepeatMode.ONE, true));
        assertEquals(2, order.next(2, RepeatMode.ONE, true));
    }

    @Test
    public void skippingWithRepeatOneGoesOnLikeRepeatOff() {
        PlayOrder order = order(3, false);

        assertEquals(2, order.next(1, RepeatMode.ONE, false));
        // no wrap around at the end, as ExoPlayer does not
        assertEquals(NONE, order.next(2, RepeatMode.ONE, false));
    }

    @Test
    public void repeatOneRepeatsASingleItem() {
        PlayOrder order = order(1, false);

        assertEquals(0, order.next(0, RepeatMode.ONE, true));
        assertEquals(0, order.next(0, RepeatMode.ALL, true));
        assertEquals(NONE, order.next(0, RepeatMode.OFF, true));
    }

    @Test
    public void anEmptyPlaylistHasNoNextAndNoPrevious() {
        PlayOrder order = order(0, true);

        assertEquals(NONE, order.next(0, RepeatMode.ALL, true));
        assertEquals(NONE, order.previous(0));
        assertEquals(NONE, order.first());
    }

    @Test
    public void previousGoesBackAndWrapsToTheLastItem() {
        PlayOrder order = order(3, false);

        assertEquals(1, order.previous(2));
        assertEquals(0, order.previous(1));
        assertEquals(2, order.previous(0));
    }

    @Test
    public void followingAndPrecedingDoNotWrap() {
        PlayOrder order = order(3, false);

        assertEquals(1, order.following(0));
        assertEquals(NONE, order.following(2));
        assertEquals(NONE, order.preceding(0));
        assertEquals(1, order.preceding(2));
    }

    @Test
    public void anItemThatIsNotInThePlaylistStartsAtTheFirst() {
        PlayOrder order = order(3, false);

        assertEquals(0, order.next(7, RepeatMode.OFF, false));
        assertEquals(2, order.previous(7));
    }

    // --- shuffle

    @Test
    public void shuffleVisitsEveryItemOnceWithTheCurrentItemFirst() {
        for (int start = 0; start < 6; start++) {
            PlayOrder order = new PlayOrder(new Random(start));
            order.reset(6, start, true);

            List<Integer> played = playThrough(order, start);

            assertEquals("starts with the current item", start, (int) played.get(0));
            assertEquals("every item once", 6, played.size());
            assertEquals(6, new HashSet<>(played).size());
        }
    }

    @Test
    public void shuffleIsARandomPermutationNotTheListOrder() {
        PlayOrder order = order(30, true);

        assertFalse("30 items in list order would be a miracle", Arrays.equals(order(30, false).toArray(), order.toArray()));
        int[] sorted = order.toArray();
        Arrays.sort(sorted);
        for (int i = 0; i < 30; i++) {
            assertEquals(i, sorted[i]);
        }
    }

    @Test
    public void theSameSeedGivesTheSameOrder() {
        PlayOrder a = new PlayOrder(new Random(7));
        PlayOrder b = new PlayOrder(new Random(7));
        a.reset(10, 3, true);
        b.reset(10, 3, true);

        assertArrayEquals(a.toArray(), b.toArray());
    }

    @Test
    public void previousWalksBackThroughTheSameOrder() {
        PlayOrder order = order(8, true);
        List<Integer> forward = playThrough(order, order.first());

        List<Integer> backward = new ArrayList<>();
        int current = forward.get(forward.size() - 1);
        backward.add(current);
        for (int i = 1; i < forward.size(); i++) {
            current = order.previous(current);
            backward.add(current);
        }

        java.util.Collections.reverse(backward);
        assertEquals(forward, backward);
    }

    @Test
    public void theOrderIsKeptNotCreatedAgainAtEveryStep() {
        PlayOrder order = order(8, true);
        int[] before = order.toArray();

        order.next(before[0], RepeatMode.ALL, true);
        order.previous(before[3]);
        order.following(before[2]);

        assertArrayEquals(before, order.toArray());
    }

    @Test
    public void repeatAllWrapsAlongTheShuffledOrder() {
        PlayOrder order = order(5, true);
        int[] sequence = order.toArray();

        assertEquals(sequence[0], order.next(sequence[4], RepeatMode.ALL, true));
        assertEquals(NONE, order.next(sequence[4], RepeatMode.OFF, true));
    }

    @Test
    public void repeatOneStaysOnTheItemInShuffle() {
        PlayOrder order = order(5, true);

        assertEquals(3, order.next(3, RepeatMode.ONE, true));
    }

    @Test
    public void aNewShuffledPlaylistMayStartWithAnyItem() {
        Set<Integer> firsts = new HashSet<>();
        for (int seed = 0; seed < 40; seed++) {
            PlayOrder order = new PlayOrder(new Random(seed));
            order.reset(5, NONE, true);
            firsts.add(order.first());
        }

        assertTrue("random first items, got " + firsts, firsts.size() > 1);
    }

    @Test
    public void anOutOfRangeFirstItemStillGivesAPermutation() {
        PlayOrder order = new PlayOrder(new Random(1));
        order.reset(4, 9, true);

        assertEquals(4, order.size());
        assertEquals(4, Arrays.stream(order.toArray()).distinct().count());
    }

    // --- switching shuffle

    @Test
    public void switchingShuffleOnKeepsTheCurrentItemAndPlaysTheRestOnce() {
        PlayOrder order = order(7, false);

        order.setShuffle(true, 4);

        assertTrue(order.isShuffle());
        assertEquals(4, order.first());
        assertEquals(7, playThrough(order, 4).size());
    }

    @Test
    public void switchingShuffleOffKeepsTheCurrentItemAndGoesOnInListOrder() {
        PlayOrder order = order(7, true);
        int current = order.toArray()[3];

        order.setShuffle(false, current);

        assertFalse(order.isShuffle());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, order.toArray());
        assertEquals(current + 1, order.following(current));
    }

    @Test
    public void switchingToTheSameShuffleStateKeepsTheOrder() {
        PlayOrder order = order(7, true);
        int[] before = order.toArray();

        order.setShuffle(true, before[2]);

        assertArrayEquals(before, order.toArray());
    }

    // --- playlist changes

    @Test
    public void itemsAddedWithoutShuffleComeAtTheEnd() {
        PlayOrder order = order(3, false);

        order.fit(5, 1);

        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, order.toArray());
    }

    @Test
    public void itemsAddedDuringShuffledPlaybackPlayAfterTheCurrentItemAndKeepThePlayedPart() {
        for (int seed = 0; seed < 30; seed++) {
            PlayOrder order = new PlayOrder(new Random(seed));
            order.reset(6, 0, true);
            int[] before = order.toArray();
            int current = before[2];

            order.fit(9, current);

            int[] after = order.toArray();
            assertEquals(9, after.length);
            assertEquals(9, Arrays.stream(after).distinct().count());
            // the part up to and including the current item is untouched
            assertArrayEquals(Arrays.copyOfRange(before, 0, 3), Arrays.copyOfRange(after, 0, 3));
            // the new items (6, 7, 8) play behind the current item
            for (int added = 6; added < 9; added++) {
                assertTrue("seed " + seed, positionOf(after, added) > 2);
            }
        }
    }

    private static int positionOf(int[] order, int item) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == item) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void aSameSizeFitChangesNothing() {
        PlayOrder order = order(6, true);
        int[] before = order.toArray();

        order.fit(6, 2);

        assertArrayEquals(before, order.toArray());
    }

    @Test
    public void aSmallerPlaylistCreatesTheOrderAgainWithTheCurrentItemFirst() {
        PlayOrder order = order(6, true);

        order.fit(4, 2);

        assertEquals(4, order.size());
        assertEquals(2, order.first());
        assertEquals(4, Arrays.stream(order.toArray()).distinct().count());
    }

    @Test
    public void aSmallerPlaylistWithoutShuffleIsTheListOrder() {
        PlayOrder order = order(6, false);

        order.fit(2, 1);

        assertArrayEquals(new int[]{0, 1}, order.toArray());
    }

    @Test
    public void theOrderOfAnEmptyThenFilledPlaylistIsCreatedByFit() {
        PlayOrder order = new PlayOrder(new Random(3));

        order.fit(3, 0);

        assertArrayEquals(new int[]{0, 1, 2}, order.toArray());
    }

    @Test
    public void toArrayReturnsACopy() {
        PlayOrder order = order(3, false);

        order.toArray()[0] = 99;

        assertEquals(0, order.first());
    }

    // --- positions, move and remove: what the playlist screen edits

    @Test
    public void positionAndItemAtAreInverseOfEachOther() {
        PlayOrder order = order(6, true);

        for (int position = 0; position < 6; position++) {
            assertEquals(position, order.positionOf(order.itemAt(position)));
        }
        assertEquals(NONE, order.itemAt(-1));
        assertEquals(NONE, order.itemAt(6));
        assertEquals(-1, order.positionOf(6));
    }

    @Test
    public void withoutShuffleThePositionIsTheIndex() {
        PlayOrder order = order(4, false);

        assertEquals(2, order.positionOf(2));
        assertEquals(3, order.itemAt(3));
    }

    @Test
    public void moveShiftsTheItemsInBetweenForwards() {
        PlayOrder order = order(5, false);

        assertTrue(order.move(1, 3));

        assertArrayEquals(new int[]{0, 2, 3, 1, 4}, order.toArray());
    }

    @Test
    public void moveShiftsTheItemsInBetweenBackwards() {
        PlayOrder order = order(5, false);

        assertTrue(order.move(4, 1));

        assertArrayEquals(new int[]{0, 4, 1, 2, 3}, order.toArray());
    }

    @Test
    public void moveRefusesSamePositionAndPositionsOutsideTheOrder() {
        PlayOrder order = order(3, false);

        assertFalse(order.move(1, 1));
        assertFalse(order.move(-1, 1));
        assertFalse(order.move(0, 3));
        assertFalse(order.move(3, 0));

        assertArrayEquals(new int[]{0, 1, 2}, order.toArray());
    }

    @Test
    public void aMovedShuffleOrderIsTheOrderThatIsPlayed() {
        PlayOrder order = order(5, true);
        int[] before = order.toArray();

        assertTrue(order.move(4, 1));

        int[] after = order.toArray();
        assertEquals(before[4], after[1]);
        assertEquals(before[0], after[0]);
        // the items are the same, only the order changed
        Set<Integer> items = new HashSet<>();
        for (int item : after) {
            items.add(item);
        }
        assertEquals(5, items.size());
        // next follows the moved order
        assertEquals(after[2], order.next(after[1], RepeatMode.OFF, true));
        assertEquals(after[1], order.following(after[0]));
    }

    @Test
    public void removeKeepsTheOrderOfTheOthersAndMovesTheIndexesDown() {
        PlayOrder order = new PlayOrder(new Random(1));
        order.reset(5, 0, false);
        order.move(0, 4); // 1 2 3 4 0

        assertTrue(order.remove(2)); // the list lost item 2: 3 and 4 are 2 and 3 now

        assertArrayEquals(new int[]{1, 2, 3, 0}, order.toArray());
        assertEquals(4, order.size());
    }

    @Test
    public void removeInAShuffledOrderKeepsTheRelativeOrder() {
        PlayOrder order = order(6, true);
        int[] before = order.toArray();
        int removed = before[3];

        assertTrue(order.remove(removed));

        List<Integer> expected = new ArrayList<>();
        for (int item : before) {
            if (item != removed) {
                expected.add(item > removed ? item - 1 : item);
            }
        }
        int[] after = order.toArray();
        assertEquals(5, after.length);
        for (int position = 0; position < after.length; position++) {
            assertEquals(expected.get(position).intValue(), after[position]);
        }
    }

    @Test
    public void removeOfAnUnknownItemChangesNothing() {
        PlayOrder order = order(3, false);

        assertFalse(order.remove(3));
        assertFalse(order.remove(-1));

        assertArrayEquals(new int[]{0, 1, 2}, order.toArray());
    }

    @Test
    public void everythingUpToTheCurrentItemIsLockedInTheOrder() {
        PlayOrder order = order(5, false);
        order.move(4, 1); // 0 4 1 2 3

        assertTrue(order.isAtOrBeforeCurrent(0, 1));
        assertTrue(order.isAtOrBeforeCurrent(2, 1)); // the current item 1 is at position 2
        assertFalse(order.isAtOrBeforeCurrent(3, 1));
        assertFalse(order.isAtOrBeforeCurrent(0, 9)); // a current item that is not in the order locks nothing
    }
}
