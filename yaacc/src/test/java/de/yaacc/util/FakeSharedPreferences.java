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

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link SharedPreferences} for tests: counts commits and applies, and can hold {@code getAll()} back.
 */
class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger applies = new AtomicInteger();
    final AtomicInteger commitThreadIsMain = new AtomicInteger();
    /** When set, getAll() waits for it (to keep the cache's load from finishing). */
    volatile CountDownLatch holdGetAll;
    boolean failCommits;

    synchronized FakeSharedPreferences with(String key, Object value) {
        values.put(key, value);
        return this;
    }

    synchronized Map<String, Object> snapshot() {
        return new HashMap<>(values);
    }

    @Override
    public Map<String, ?> getAll() {
        CountDownLatch hold = holdGetAll;
        if (hold != null) {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return snapshot();
    }

    @Override
    public synchronized String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override
    public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        return defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    private class FakeEditor implements Editor {
        private final Map<String, Object> puts = new HashMap<>();
        private final Set<String> removes = new HashSet<>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            puts.put(key, values);
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removes.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            commits.incrementAndGet();
            if (failCommits) {
                return false;
            }
            write();
            return true;
        }

        @Override
        public void apply() {
            applies.incrementAndGet();
            write();
        }

        private void write() {
            synchronized (FakeSharedPreferences.this) {
                if (clear) {
                    values.clear();
                }
                values.keySet().removeAll(removes);
                values.putAll(puts);
            }
        }
    }
}
