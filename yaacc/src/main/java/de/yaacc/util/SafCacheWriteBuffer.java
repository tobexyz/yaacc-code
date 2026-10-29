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
package de.yaacc.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The writes to the stored SAF cache that were not committed yet, newest wins per key. Not thread safe (the cache
 * manager guards it with its lock). No Android classes.
 * <p>
 * It also answers reads: a value that waits to be written is the value of its key, a key that waits to be removed
 * has none, whatever the stored state still says.
 */
final class SafCacheWriteBuffer {

    /** What {@link #drain()} took out of the buffer, to be committed in one go. */
    static final class Batch {
        /** The stored state is emptied first, the puts below come after it. */
        final boolean clearAll;
        final Map<String, String> puts;
        final Set<String> removes;
        /** The next id counter to store, null if unchanged. */
        final Long counter;

        Batch(boolean clearAll, Map<String, String> puts, Set<String> removes, Long counter) {
            this.clearAll = clearAll;
            this.puts = puts;
            this.removes = removes;
            this.counter = counter;
        }

        boolean isEmpty() {
            return !clearAll && puts.isEmpty() && removes.isEmpty() && counter == null;
        }
    }

    private Map<String, String> puts = new LinkedHashMap<>();
    private Set<String> removes = new HashSet<>();
    private Long counter;
    private boolean clearAll;

    void put(String key, String value) {
        removes.remove(key);
        puts.put(key, value);
    }

    void remove(String key) {
        puts.remove(key);
        removes.add(key);
    }

    void putCounter(long value) {
        counter = counter == null ? value : Math.max(counter, value);
    }

    /** Everything stored is to be deleted; earlier waiting writes are void. */
    void clearAll() {
        puts.clear();
        removes.clear();
        counter = null;
        clearAll = true;
    }

    boolean isEmpty() {
        return !clearAll && puts.isEmpty() && removes.isEmpty() && counter == null;
    }

    /**
     * The value of a key as it will be after the write: a waiting put, else nothing if the key is to be removed
     * or everything is to be cleared, else what {@code stored} says.
     */
    String read(String key, UnaryOperator<String> stored) {
        String pending = puts.get(key);
        if (pending != null) {
            return pending;
        }
        if (clearAll || removes.contains(key)) {
            return null;
        }
        return stored.apply(key);
    }

    /** Empties the buffer and returns what it held. */
    Batch drain() {
        Batch batch = new Batch(clearAll, puts, removes, counter);
        puts = new LinkedHashMap<>();
        removes = new HashSet<>();
        counter = null;
        clearAll = false;
        return batch;
    }

    /**
     * Puts a batch back whose commit failed, behind what was collected meanwhile: newer writes win, a newer clear
     * voids the batch.
     */
    void restore(Batch batch) {
        if (clearAll) {
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>(batch.puts);
        merged.keySet().removeAll(removes);
        merged.putAll(puts);
        Set<String> mergedRemoves = new HashSet<>(batch.removes);
        mergedRemoves.removeAll(puts.keySet());
        mergedRemoves.addAll(removes);
        puts = merged;
        removes = mergedRemoves;
        if (batch.counter != null) {
            putCounter(batch.counter);
        }
        if (batch.clearAll) {
            clearAll = true;
        }
    }

    /** For tests: a copy of the waiting puts. */
    Map<String, String> pendingPuts() {
        return new HashMap<>(puts);
    }
}
