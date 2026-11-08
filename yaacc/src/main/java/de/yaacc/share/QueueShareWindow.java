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

package de.yaacc.share;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shares of one picked queue, held just in time: a file is reachable by a receiver only
 * while a player holds it. The window counts the holders of each item; the first holder binds
 * the share ({@link Binder}), the last one releases it. A player takes a {@link Lease}.
 *
 * <p>Items are identified by the object of the queue (the placeholder item), not by their
 * position in a player's list: a player's playlist can be edited (remove, move, add) after the
 * queue was set. An item that is not part of the queue is not this window's business.
 *
 * <p>Binding a share (open the file, register it, build the url) can take seconds, so it runs
 * outside every lock: {@link Lease#close()} and {@link #releaseAll()} never wait for it, and a
 * bind that finishes after the close releases its share again at once.
 */
public final class QueueShareWindow {

    /** No index: a lease with no next item. */
    public static final int NONE = -1;

    /** A bound share; {@link #release()} makes the file unreachable again. */
    public interface Bound {
        void release();
    }

    /** Creates the share of one item. */
    public interface Binder {
        /** @return the bound share, or null when it could not be bound (registry full, file gone) */
        Bound bind(int index);
    }

    private final int itemCount;
    private final Binder binder;
    private final Map<Object, Integer> indexOf = new IdentityHashMap<>();
    private final Map<Integer, Bound> bound = new HashMap<>();
    private final Map<Integer, Integer> holders = new HashMap<>();
    private final Set<Integer> binding = new HashSet<>();
    private boolean closed;

    /** A window for items that are only known by their index (tests). */
    QueueShareWindow(int itemCount, Binder binder) {
        this.itemCount = itemCount;
        this.binder = binder;
    }

    /** A window for the given queue items: {@link Lease#moveToItems} finds their index by identity. */
    public QueueShareWindow(List<?> items, Binder binder) {
        this(items.size(), binder);
        for (int i = 0; i < items.size(); i++) {
            indexOf.put(items.get(i), i);
        }
    }

    /** The number of items whose share is bound right now. */
    synchronized int activeCount() {
        return bound.size();
    }

    /** True when the share of the item is bound right now. */
    synchronized boolean isBound(int index) {
        return bound.containsKey(index);
    }

    /**
     * Counts one more holder of the item and binds its share if it has none yet.
     *
     * @return false when the index is out of range, the window is closed or the share could not be
     * bound (no holder is counted then)
     */
    boolean acquire(int index) {
        synchronized (this) {
            while (true) {
                if (closed || index < 0 || index >= itemCount) {
                    return false;
                }
                if (bound.containsKey(index)) {
                    holders.merge(index, 1, Integer::sum);
                    return true;
                }
                if (!binding.contains(index)) {
                    binding.add(index);
                    break;
                }
                // another thread binds this item right now: wait for its result
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        Bound share = null;
        Bound late = null;
        boolean result = false;
        try {
            share = binder.bind(index);
        } finally {
            synchronized (this) {
                binding.remove(index);
                if (share != null) {
                    if (closed) {
                        late = share;
                    } else {
                        bound.put(index, share);
                        holders.merge(index, 1, Integer::sum);
                        result = true;
                    }
                }
                notifyAll();
            }
            if (late != null) {
                late.release();
            }
        }
        return result;
    }

    /** Counts one holder less; the last one releases the share. Too many releases are ignored. */
    void release(int index) {
        Bound toRelease = null;
        synchronized (this) {
            Integer count = holders.get(index);
            if (count == null) {
                return;
            }
            if (count > 1) {
                holders.put(index, count - 1);
                return;
            }
            holders.remove(index);
            toRelease = bound.remove(index);
        }
        if (toRelease != null) {
            toRelease.release();
        }
    }

    /** Releases every share and refuses new ones (the queue ended or the app exits). */
    void releaseAll() {
        Set<Bound> all;
        synchronized (this) {
            closed = true;
            holders.clear();
            all = new HashSet<>(bound.values());
            bound.clear();
            notifyAll();
        }
        for (Bound share : all) {
            share.release();
        }
    }

    /** A new holder, one per player. */
    public Lease newLease() {
        return new Lease();
    }

    private int indexOfItem(Object item) {
        if (item == null) {
            return NONE;
        }
        Integer index = indexOf.get(item);
        return index == null ? NONE : index;
    }

    /**
     * What one player holds: the shares of the items it plays. {@link #drop()} lets go of
     * everything and the lease can be used again (the player was stopped and may start again);
     * {@link #close()} is final (the player is gone).
     */
    public final class Lease {
        private final Object moveMonitor = new Object();
        private final Object heldMonitor = new Object();
        private final Set<Integer> held = new LinkedHashSet<>();
        private boolean closedLease;

        private Lease() {
        }

        /**
         * Holds exactly the shares of the two items from now on: what was held before and is not
         * wanted any more is released first, then what is missing is bound. An item that is not
         * part of the queue needs no share (true); {@code null} means no item.
         *
         * @return true when the current item is shared or needs no share; false when its share
         * could not be bound or the lease is closed
         */
        public boolean moveToItems(Object current, Object next) {
            return moveTo(indexOfItem(current), indexOfItem(next), current != null && indexOfItem(current) == NONE);
        }

        /** Same by index: {@code next} may be {@link #NONE}. */
        boolean moveTo(int current, int next) {
            return moveTo(current, next, false);
        }

        private boolean moveTo(int current, int next, boolean currentNeedsNoShare) {
            synchronized (moveMonitor) {
                Set<Integer> wanted = new LinkedHashSet<>();
                if (current != NONE) {
                    wanted.add(current);
                }
                if (next != NONE) {
                    wanted.add(next);
                }
                Set<Integer> drop;
                synchronized (heldMonitor) {
                    if (closedLease) {
                        return false;
                    }
                    drop = new LinkedHashSet<>(held);
                    drop.removeAll(wanted);
                    held.removeAll(drop);
                }
                for (int index : drop) {
                    QueueShareWindow.this.release(index);
                }
                boolean currentBound = currentNeedsNoShare || current == NONE;
                for (int index : wanted) {
                    boolean already;
                    synchronized (heldMonitor) {
                        already = held.contains(index);
                    }
                    boolean ok = already || QueueShareWindow.this.acquire(index);
                    if (ok && !already) {
                        boolean keep;
                        synchronized (heldMonitor) {
                            keep = !closedLease;
                            if (keep) {
                                held.add(index);
                            }
                        }
                        if (!keep) {
                            QueueShareWindow.this.release(index);
                            ok = false;
                        }
                    }
                    if (index == current && ok) {
                        currentBound = true;
                    }
                }
                return currentBound;
            }
        }

        /** Releases everything this lease holds; the lease can be used again. Never waits for a bind. */
        public void drop() {
            Set<Integer> toRelease;
            synchronized (heldMonitor) {
                toRelease = new LinkedHashSet<>(held);
                held.clear();
            }
            for (int index : toRelease) {
                QueueShareWindow.this.release(index);
            }
        }

        /** True once {@link #close()} was called: the player is gone, a move that fails is no failure of an item. */
        public boolean isClosed() {
            synchronized (heldMonitor) {
                return closedLease;
            }
        }

        /** Releases everything and refuses further moves; calling it again does nothing. */
        public void close() {
            synchronized (heldMonitor) {
                closedLease = true;
            }
            drop();
        }
    }
}
