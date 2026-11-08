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
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remembers values that depend on a configuration (for example the night mode of the theme): when the
 * configuration changes, everything is dropped. Not thread safe, meant for the main thread. No Android classes.
 */
public final class ConfigKeyedCache<K, V> {

    private final Map<K, V> values = new HashMap<>();
    private int config;
    private boolean configKnown;

    /**
     * @param currentConfig a value that changes whenever the cached values would be stale
     * @param creator       builds the value on a miss; a null result is not remembered
     */
    public V get(int currentConfig, K key, Supplier<V> creator) {
        if (!configKnown || currentConfig != config) {
            values.clear();
            config = currentConfig;
            configKnown = true;
        }
        V value = values.get(key);
        if (value == null) {
            value = creator.get();
            if (value != null) {
                values.put(key, value);
            }
        }
        return value;
    }

    public int size() {
        return values.size();
    }
}
