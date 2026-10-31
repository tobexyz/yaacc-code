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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The order in which the items of a playlist are played, and which item follows or precedes another.
 * Plain JVM, thread safe (the timer thread, the UI and the UPnP threads all ask).
 * <p>
 * Without shuffle the order is the list order. With shuffle it is a random permutation that is created
 * once (when the playlist is set, shuffle is switched on, or the player is stopped) and then kept, so
 * previous walks back through the order next walked along. A created order starts with the item that is
 * playing, so every other item is still ahead: the list is played through once whatever the item was.
 * <p>
 * The position in the order is never stored: it is looked up from the index of the current item, which the
 * player owns (an engine can change it too). A playlist that grew or shrank is adapted by {@link #fit}.
 */
public final class PlayOrder {

    /** Result of {@link #next}, {@link #previous} and {@link #following} when there is no such item. */
    public static final int NONE = -1;

    private final Random random;
    private int[] order = new int[0];
    private boolean shuffle;

    /** @param random the source of the shuffle; a seeded one makes the order reproducible in tests */
    public PlayOrder(Random random) {
        this.random = random;
    }

    /**
     * Creates the order of a new playlist.
     *
     * @param size       the number of items
     * @param firstIndex the item that comes first, {@link #NONE} (or out of range) for a random first one
     *                   (a shuffled playlist that starts playing); ignored without shuffle
     * @param shuffle    true for a random order
     */
    public synchronized void reset(int size, int firstIndex, boolean shuffle) {
        this.shuffle = shuffle;
        int count = Math.max(0, size);
        List<Integer> indexes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indexes.add(i);
        }
        if (shuffle) {
            Collections.shuffle(indexes, random);
            if (firstIndex >= 0 && firstIndex < count) {
                indexes.remove(Integer.valueOf(firstIndex));
                indexes.add(0, firstIndex);
            }
        }
        order = toArray(indexes);
    }

    /**
     * Switches shuffle on or off. The current item stays the current one: switched on, it becomes the
     * first of a new random order; switched off, the items go on in list order after it.
     */
    public synchronized void setShuffle(boolean shuffle, int currentIndex) {
        if (this.shuffle == shuffle) {
            return;
        }
        reset(order.length, currentIndex, shuffle);
    }

    /**
     * Adapts the order to a playlist of another size without touching the part that is already played.
     * New items (added to the end of the list) are appended, or with shuffle inserted at a random place
     * behind the current item. A smaller list creates the order again, current item first.
     */
    public synchronized void fit(int size, int currentIndex) {
        int count = Math.max(0, size);
        if (count == order.length) {
            return;
        }
        if (count < order.length) {
            reset(count, currentIndex, shuffle);
            return;
        }
        List<Integer> indexes = new ArrayList<>(count);
        for (int index : order) {
            indexes.add(index);
        }
        for (int added = order.length; added < count; added++) {
            if (shuffle) {
                int from = Math.min(positionOf(currentIndex) + 1, indexes.size());
                indexes.add(from + random.nextInt(indexes.size() - from + 1), added);
            } else {
                indexes.add(added);
            }
        }
        order = toArray(indexes);
    }

    public synchronized boolean isShuffle() {
        return shuffle;
    }

    public synchronized int size() {
        return order.length;
    }

    /** @return the item that plays first, {@link #NONE} for an empty playlist */
    public synchronized int first() {
        return order.length == 0 ? NONE : order[0];
    }

    /** @return a copy of the order: the item index played at each position */
    public synchronized int[] toArray() {
        return order.clone();
    }

    /**
     * Which item plays after the current one.
     *
     * @param currentIndex the item that plays now
     * @param repeat       the repeat mode
     * @param autoAdvance  true when the current item ended by itself, false when somebody skips to the next
     *                     one. Repeat one only repeats an ended item; skipping goes on (and, like in
     *                     ExoPlayer, treats repeat one as off: it does not wrap around at the end)
     * @return the item index; {@link #NONE} at the end of the order unless the repeat mode is all
     */
    public synchronized int next(int currentIndex, PlayMode.RepeatMode repeat, boolean autoAdvance) {
        if (order.length == 0) {
            return NONE;
        }
        if (autoAdvance && repeat == PlayMode.RepeatMode.ONE && positionOf(currentIndex) >= 0) {
            return currentIndex;
        }
        int following = following(currentIndex);
        if (following != NONE) {
            return following;
        }
        return repeat == PlayMode.RepeatMode.ALL ? order[0] : NONE;
    }

    /**
     * @return the item behind the current one in the order, {@link #NONE} at the end of the order (no
     * wrap around); the first item for a current item that is not in the playlist
     */
    public synchronized int following(int currentIndex) {
        if (order.length == 0) {
            return NONE;
        }
        int position = positionOf(currentIndex);
        if (position < 0) {
            return order[0];
        }
        return position + 1 < order.length ? order[position + 1] : NONE;
    }

    /**
     * @return the item before the current one in the order, {@link #NONE} before the first one (no wrap
     * around)
     */
    public synchronized int preceding(int currentIndex) {
        int position = positionOf(currentIndex);
        return position > 0 ? order[position - 1] : NONE;
    }

    /**
     * Which item plays when somebody skips back. Before the first item it goes to the last one (what
     * the players always did); {@link #NONE} only for an empty playlist.
     */
    public synchronized int previous(int currentIndex) {
        if (order.length == 0) {
            return NONE;
        }
        int preceding = preceding(currentIndex);
        return preceding != NONE ? preceding : order[order.length - 1];
    }

    /**
     * @return the position of the item in the order, -1 if the item is not in it. With shuffle off the
     * position of an item is its index.
     */
    public synchronized int positionOf(int index) {
        for (int position = 0; position < order.length; position++) {
            if (order[position] == index) {
                return position;
            }
        }
        return -1;
    }

    /** @return the item that plays at the position, {@link #NONE} for a position outside the order */
    public synchronized int itemAt(int position) {
        return position >= 0 && position < order.length ? order[position] : NONE;
    }

    /**
     * Moves the item at {@code fromPosition} to {@code toPosition}; the items in between move up or down
     * by one (what a drag in a list does). Only the order changes, the items keep their indexes.
     *
     * @return false (and nothing changed) if a position is outside the order or both are the same
     */
    public synchronized boolean move(int fromPosition, int toPosition) {
        if (fromPosition == toPosition || fromPosition < 0 || toPosition < 0
                || fromPosition >= order.length || toPosition >= order.length) {
            return false;
        }
        int item = order[fromPosition];
        int step = toPosition > fromPosition ? 1 : -1;
        for (int position = fromPosition; position != toPosition; position += step) {
            order[position] = order[position + step];
        }
        order[toPosition] = item;
        return true;
    }

    /**
     * Takes an item that was removed from the playlist out of the order. The order of the other items is
     * kept (unlike {@link #fit}, which creates a shuffled order again), their indexes behind the removed
     * one move down by one like the list does.
     *
     * @param index the index of the removed item
     * @return false (and nothing changed) if the item is not in the order
     */
    public synchronized boolean remove(int index) {
        int removedAt = positionOf(index);
        if (removedAt < 0) {
            return false;
        }
        int[] rest = new int[order.length - 1];
        int target = 0;
        for (int position = 0; position < order.length; position++) {
            if (position != removedAt) {
                rest[target++] = order[position] > index ? order[position] - 1 : order[position];
            }
        }
        order = rest;
        return true;
    }

    /**
     * @return true if the position is the one of the current item or before it: what has been played (or
     * plays) is not edited. False for a current item that is not in the order.
     */
    public synchronized boolean isAtOrBeforeCurrent(int position, int currentIndex) {
        int current = positionOf(currentIndex);
        return current >= 0 && position <= current;
    }

    private static int[] toArray(List<Integer> indexes) {
        int[] result = new int[indexes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = indexes.get(i);
        }
        return result;
    }
}
