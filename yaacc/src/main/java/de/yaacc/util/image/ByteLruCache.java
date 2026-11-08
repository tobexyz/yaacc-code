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
package de.yaacc.util.image;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * A least-recently-used cache bounded by the summed size in bytes of its values, not by their number
 * (the former thumbnail cache counted one unit per bitmap against a byte-sized limit, so it never evicted in
 * practice). Thread safe; no Android classes, so the sizing function is passed in.
 */
public final class ByteLruCache<K, V> {

    /** The smallest and largest default size, whatever the heap. */
    static final long MIN_DEFAULT_BYTES = 8L * 1024 * 1024;
    static final long MAX_DEFAULT_BYTES = 32L * 1024 * 1024;

    private final long maxBytes;
    private final ToLongFunction<V> sizer;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    public ByteLruCache(long maxBytes, ToLongFunction<V> sizer) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.sizer = sizer;
    }

    /**
     * One sixteenth of the heap, but between 8 MB and 32 MB.
     */
    public static long defaultMaxBytes(long maxHeapBytes) {
        return Math.max(MIN_DEFAULT_BYTES, Math.min(MAX_DEFAULT_BYTES, maxHeapBytes / 16));
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    /**
     * Stores the value as the most recently used one and evicts the least recently used ones until the limit
     * holds. A value larger than the whole cache is not stored (and an older value for the key is dropped).
     */
    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }
        V old = map.remove(key);
        if (old != null) {
            bytes -= sizer.applyAsLong(old);
        }
        long size = sizer.applyAsLong(value);
        if (size > maxBytes) {
            return;
        }
        map.put(key, value);
        bytes += size;
        Iterator<Map.Entry<K, V>> eldest = map.entrySet().iterator();
        while (bytes > maxBytes && eldest.hasNext()) {
            Map.Entry<K, V> entry = eldest.next();
            bytes -= sizer.applyAsLong(entry.getValue());
            eldest.remove();
        }
    }

    public synchronized void clear() {
        map.clear();
        bytes = 0;
    }

    public synchronized int count() {
        return map.size();
    }

    public synchronized long bytes() {
        return bytes;
    }
}
