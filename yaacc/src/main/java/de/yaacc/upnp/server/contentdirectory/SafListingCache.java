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
package de.yaacc.upnp.server.contentdirectory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.LongSupplier;

/**
 * Small in-memory cache of folder listings, so the several passes of one Browse (count, containers,
 * items) and the consecutive chunk requests of the same folder share ONE listing instead of asking
 * the document provider again each time.
 * <ul>
 *   <li>bounded: at most {@code maxFolders} listings and {@code maxTotalWeight} summed weight, the
 *   least recently used completed listing goes first;</li>
 *   <li>a listing older than {@code ttlMillis} is reloaded by the next request;</li>
 *   <li>single flight: while a folder is being listed, other requests for it wait for that listing
 *   (for at most {@code maxWaitMillis}) instead of starting their own;</li>
 *   <li>values the {@code cacheable} predicate rejects (failed listings) are handed to the waiting
 *   requests but not kept.</li>
 * </ul>
 * Thread-safe. No Android types and an injectable clock, so it is testable on the plain JVM.
 *
 * @param <V> the listing type
 * @author Tobias Schoene (tobexyz)
 */
public final class SafListingCache<V> {

    /** Produces the value for a key; runs on the calling thread, outside of the cache's lock. */
    public interface Loader<V> {
        V load() throws Exception;
    }

    /** The value and where it came from. */
    public static final class Result<V> {
        public final V value;
        /** false if this request ran the loader, true if it reused (or waited for) another request's listing */
        public final boolean fromCache;

        Result(V value, boolean fromCache) {
            this.value = value;
            this.fromCache = fromCache;
        }
    }

    private static final class Slot<V> {
        final CompletableFuture<V> future = new CompletableFuture<>();
        boolean completed;
        long loadedAt;
        int weight;
    }

    private final int maxFolders;
    private final long maxTotalWeight;
    private final long ttlMillis;
    private final long maxWaitMillis;
    private final LongSupplier clock;
    private final ToIntFunction<V> weigher;
    private final Predicate<V> cacheable;

    private final Object lock = new Object();
    // access order: the eldest entry is the least recently used one
    private final LinkedHashMap<String, Slot<V>> slots = new LinkedHashMap<>(16, 0.75f, true);
    private long totalWeight;

    public SafListingCache(int maxFolders, long maxTotalWeight, long ttlMillis, long maxWaitMillis,
                           LongSupplier clock, ToIntFunction<V> weigher, Predicate<V> cacheable) {
        this.maxFolders = Math.max(1, maxFolders);
        this.maxTotalWeight = maxTotalWeight;
        this.ttlMillis = ttlMillis;
        this.maxWaitMillis = maxWaitMillis;
        this.clock = clock;
        this.weigher = weigher;
        this.cacheable = cacheable;
    }

    /**
     * @return the listing for {@code key}: a still valid cached one, the one another thread is just
     * loading, or - if there is none - the one {@code loader} produces now
     * @throws TimeoutException if another thread's listing of the same key did not finish in time
     */
    public Result<V> get(String key, Loader<V> loader) throws Exception {
        Slot<V> slot;
        boolean owner = false;
        synchronized (lock) {
            slot = slots.get(key);
            if (slot != null && slot.completed && clock.getAsLong() - slot.loadedAt >= ttlMillis) {
                remove(key, slot);
                slot = null;
            }
            if (slot == null) {
                slot = new Slot<>();
                slots.put(key, slot);
                owner = true;
            }
        }
        if (!owner) {
            return new Result<>(await(slot), true);
        }
        V value;
        try {
            value = loader.load();
        } catch (Exception | Error e) {
            synchronized (lock) {
                remove(key, slot);
                slot.future.completeExceptionally(e);
            }
            throw e;
        }
        synchronized (lock) {
            // a clear() while loading has already dropped the slot: the value is for the waiters only
            if (slots.get(key) == slot) {
                if (cacheable.test(value)) {
                    slot.completed = true;
                    slot.loadedAt = clock.getAsLong();
                    slot.weight = weigher.applyAsInt(value);
                    totalWeight += slot.weight;
                    evictFor(slot);
                } else {
                    slots.remove(key);
                }
            }
            slot.future.complete(value);
        }
        return new Result<>(value, false);
    }

    private V await(Slot<V> slot) throws Exception {
        try {
            return slot.future.get(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Forgets everything; listings still being loaded are handed to their waiters but not kept. */
    public void clear() {
        synchronized (lock) {
            slots.clear();
            totalWeight = 0;
        }
    }

    /** @return the number of folders held, including those still being listed */
    public int size() {
        synchronized (lock) {
            return slots.size();
        }
    }

    // caller holds the lock
    private void remove(String key, Slot<V> slot) {
        if (slots.get(key) == slot) {
            slots.remove(key);
            if (slot.completed) {
                totalWeight -= slot.weight;
            }
        }
    }

    // caller holds the lock; the slot just completed is never the victim, so the newest listing survives
    private void evictFor(Slot<V> justCompleted) {
        while (slots.size() > maxFolders || totalWeight > maxTotalWeight) {
            String victim = null;
            for (Map.Entry<String, Slot<V>> entry : slots.entrySet()) {
                if (entry.getValue().completed && entry.getValue() != justCompleted) {
                    victim = entry.getKey();
                    break;
                }
            }
            if (victim == null) {
                return;
            }
            remove(victim, slots.get(victim));
        }
    }
}
