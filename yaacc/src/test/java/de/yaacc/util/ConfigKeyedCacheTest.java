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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ConfigKeyedCacheTest {

    @Test
    public void createsOncePerKeyWhileTheConfigStays() {
        ConfigKeyedCache<Integer, String> cache = new ConfigKeyedCache<>();
        AtomicInteger created = new AtomicInteger();
        assertEquals("v1", cache.get(0, 1, () -> "v" + created.incrementAndGet()));
        assertEquals("v1", cache.get(0, 1, () -> "v" + created.incrementAndGet()));
        assertEquals("v2", cache.get(0, 2, () -> "v" + created.incrementAndGet()));
        assertEquals(2, created.get());
        assertEquals(2, cache.size());
    }

    @Test
    public void aChangedConfigDropsEverything() {
        ConfigKeyedCache<Integer, String> cache = new ConfigKeyedCache<>();
        AtomicInteger created = new AtomicInteger();
        cache.get(0, 1, () -> "v" + created.incrementAndGet());
        assertEquals("v2", cache.get(32, 1, () -> "v" + created.incrementAndGet()));
        assertEquals(1, cache.size());
        // back to the old config is a new config as well
        assertEquals("v3", cache.get(0, 1, () -> "v" + created.incrementAndGet()));
    }

    @Test
    public void aNullValueIsNotRemembered() {
        ConfigKeyedCache<Integer, String> cache = new ConfigKeyedCache<>();
        AtomicInteger calls = new AtomicInteger();
        assertNull(cache.get(0, 1, () -> {
            calls.incrementAndGet();
            return null;
        }));
        assertNull(cache.get(0, 1, () -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(2, calls.get());
        assertEquals(0, cache.size());
    }
}
