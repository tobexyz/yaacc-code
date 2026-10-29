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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ByteLruCacheTest {

    private static ByteLruCache<String, String> cache(long maxBytes) {
        // the size of a value is its length
        return new ByteLruCache<>(maxBytes, String::length);
    }

    @Test
    public void evictsTheLeastRecentlyUsedWhenTheBytesExceedTheLimit() {
        ByteLruCache<String, String> cache = cache(10);
        cache.put("a", "1234");
        cache.put("b", "1234");
        cache.get("a"); // b is now the eldest
        cache.put("c", "1234");
        assertNull(cache.get("b"));
        assertEquals("1234", cache.get("a"));
        assertEquals("1234", cache.get("c"));
        assertEquals(8, cache.bytes());
        assertEquals(2, cache.count());
    }

    @Test
    public void countsBytesNotEntries() {
        ByteLruCache<String, String> cache = cache(10);
        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, "x");
        }
        assertEquals(10, cache.count());
        assertEquals(10, cache.bytes());
        assertEquals("x", cache.get("k99"));
        assertNull(cache.get("k89"));
    }

    @Test
    public void replacingAKeyAdjustsTheBytes() {
        ByteLruCache<String, String> cache = cache(10);
        cache.put("a", "12345678");
        cache.put("a", "12");
        assertEquals(2, cache.bytes());
        assertEquals(1, cache.count());
    }

    @Test
    public void aValueLargerThanTheCacheIsNotStoredAndDropsTheOldOne() {
        ByteLruCache<String, String> cache = cache(5);
        cache.put("a", "123");
        cache.put("a", "123456");
        assertNull(cache.get("a"));
        assertEquals(0, cache.bytes());
    }

    @Test
    public void nullKeyOrValueIsIgnoredAndClearEmpties() {
        ByteLruCache<String, String> cache = cache(5);
        cache.put(null, "a");
        cache.put("a", null);
        assertEquals(0, cache.count());
        cache.put("a", "12");
        cache.clear();
        assertEquals(0, cache.count());
        assertEquals(0, cache.bytes());
    }

    @Test
    public void defaultSizeIsASixteenthOfTheHeapBetween8And32Megabytes() {
        long mb = 1024L * 1024;
        assertEquals(8 * mb, ByteLruCache.defaultMaxBytes(64 * mb));
        assertEquals(16 * mb, ByteLruCache.defaultMaxBytes(256 * mb));
        assertEquals(32 * mb, ByteLruCache.defaultMaxBytes(512 * mb));
        assertEquals(32 * mb, ByteLruCache.defaultMaxBytes(4096 * mb));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANonPositiveLimit() {
        cache(0);
    }
}
