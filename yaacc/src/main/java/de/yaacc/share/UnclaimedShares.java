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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which registry keys no player has claimed yet. Plain Java, thread-safe, time passed in.
 * <p>
 * Today only a player releases a share, so a share that no player ever picked up (the receiver
 * went away, the player could not be created) would keep its registry slot, and for a file its
 * open descriptor, until the absolute maximum of six hours. The glue calls
 * {@link #registered} right after {@code ShareRegistry.register}, {@link #claimed} when a player
 * took the key, {@link #released} when it is given up in any other way, and from the sweeper
 * {@link #dueForRemoval}; the keys it returns are removed from the registry by the caller (which
 * closes the source).
 * <ul>
 * <li>A key is due when it was registered MORE than the claim window ago (exactly at the window it
 * is not yet due) and was neither claimed nor released. {@link #dueForRemoval} forgets the keys it
 * returns, so each is reported once.</li>
 * <li>A {@code claimed} or {@code released} that arrives before {@code registered} (a player can
 * be fast) is remembered for a bounded number of keys and wins.</li>
 * <li>Bounded: at most {@code maxEntries} keys are tracked. When a registration pushes the oldest
 * one out, that key is not forgotten silently: it is queued and returned as due by the next
 * {@link #dueForRemoval}, so the caller still removes it. (That queue is itself capped at
 * {@value #MAX_OVERFLOW}; the registry holds only a few shares at once, so this is never reached
 * in practice.)</li>
 * <li>All methods are idempotent; null and empty keys are ignored.</li>
 * </ul>
 */
public final class UnclaimedShares {

    /** The claim window used by default, see {@link ShareLimits#CLAIM_WINDOW_MILLIS}. */
    public static final long DEFAULT_CLAIM_WINDOW_MILLIS = ShareLimits.CLAIM_WINDOW_MILLIS;

    /** Tracked keys at most; the registry itself holds only a handful of shares. */
    public static final int DEFAULT_MAX_ENTRIES = 64;

    /** Keys pushed out by the cap that wait to be reported, at most. */
    static final int MAX_OVERFLOW = 1024;

    /** Keys whose claim or release came first, remembered at most. */
    private static final int MAX_EARLY = 256;

    private final long claimWindowMillis;
    private final int maxEntries;

    // all guarded by "this"
    private final Map<String, Long> registeredAt = new LinkedHashMap<>();
    private final Deque<String> overflow = new ArrayDeque<>();
    private final Map<String, Boolean> early = new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_EARLY;
        }
    };

    public UnclaimedShares() {
        this(DEFAULT_CLAIM_WINDOW_MILLIS, DEFAULT_MAX_ENTRIES);
    }

    /**
     * @throws IllegalArgumentException if the window or the cap is not positive
     */
    public UnclaimedShares(long claimWindowMillis, int maxEntries) {
        if (claimWindowMillis <= 0L || maxEntries <= 0) {
            throw new IllegalArgumentException("window and cap must be positive");
        }
        this.claimWindowMillis = claimWindowMillis;
        this.maxEntries = maxEntries;
    }

    /**
     * A key was registered at {@code nowMillis}. Registering a known key again keeps the first
     * time; a key that was claimed or released before is not tracked.
     */
    public synchronized void registered(String key, long nowMillis) {
        if (key == null || key.isEmpty() || registeredAt.containsKey(key)) {
            return;
        }
        if (early.remove(key) != null) {
            return;
        }
        registeredAt.put(key, nowMillis);
        while (registeredAt.size() > maxEntries) {
            Iterator<String> oldest = registeredAt.keySet().iterator();
            String pushedOut = oldest.next();
            oldest.remove();
            if (overflow.size() >= MAX_OVERFLOW) {
                overflow.pollFirst();
            }
            overflow.addLast(pushedOut);
        }
    }

    /** A player took the key: it is never reported. */
    public synchronized void claimed(String key) {
        forget(key);
    }

    /** The key was given up in another way (released, removed): it is never reported. */
    public synchronized void released(String key) {
        forget(key);
    }

    /**
     * @param nowMillis the current time, same clock as {@link #registered}
     * @return the keys the caller must remove from the registry now: registered more than the
     * claim window ago and never claimed or released, plus keys the cap pushed out; each key is
     * returned once. A clock that ran backwards makes nothing due.
     */
    public synchronized List<String> dueForRemoval(long nowMillis) {
        List<String> due = new ArrayList<>(overflow);
        overflow.clear();
        Iterator<Map.Entry<String, Long>> it = registeredAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            long elapsed = nowMillis - entry.getValue();
            if (elapsed > claimWindowMillis) {
                due.add(entry.getKey());
                it.remove();
            }
        }
        return due;
    }

    /** @return the number of keys currently tracked (not counting keys waiting in the overflow queue) */
    public synchronized int size() {
        return registeredAt.size();
    }

    private void forget(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        registeredAt.remove(key);
        // also drop it from the overflow queue: a claimed key must not be reported as due
        overflow.remove(key);
        // remembered, so a registration that comes after the claim (a fast player) does not track it
        early.put(key, Boolean.TRUE);
    }
}
