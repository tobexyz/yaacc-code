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
import java.util.Map;
import java.util.Set;

/**
 * Moves the SAF cache out of the default preferences into its own file. The cache used to live there: one
 * {@code saf_cache_<document uri>} string per file (the serialized {@link SAFMetadata}, which holds the short id and the
 * duration) and {@code saf_id_counter}. Every write of the default preferences rewrites the whole XML file, which the
 * cache made large. The short id maps are not stored anywhere else: they are rebuilt from the metadata entries.
 * <p>
 * {@link #plan} is a pure function of the two states, so the migration is idempotent and safe if interrupted: the
 * stored copy never overwrites a value that is already in the new file, the counter takes the larger of the two, and
 * the old keys are only removed after the new file was committed. Running it again after any step ends in the same
 * state, and when no old key is left the plan is empty. No Android classes.
 */
final class SafCacheMigration {

    /** Name of the preferences file of the cache. */
    static final String STORE_NAME = "saf_cache";
    static final String CACHE_PREFIX = "saf_cache_";
    static final String ID_COUNTER_KEY = "saf_id_counter";

    private SafCacheMigration() {
    }

    /** @return true if the key of the default preferences belongs to the SAF cache */
    static boolean isCacheKey(String key) {
        return key != null && (key.startsWith(CACHE_PREFIX) || key.equals(ID_COUNTER_KEY));
    }

    static final class Plan {
        /** Metadata entries to copy into the new file (only keys it does not have yet). */
        final Map<String, String> entriesToWrite;
        /** The counter to store in the new file, null if the new file's one is the larger or there is none. */
        final Long counterToWrite;
        /** Keys to delete from the default preferences once the new file is committed. */
        final Set<String> legacyKeysToRemove;

        Plan(Map<String, String> entriesToWrite, Long counterToWrite, Set<String> legacyKeysToRemove) {
            this.entriesToWrite = entriesToWrite;
            this.counterToWrite = counterToWrite;
            this.legacyKeysToRemove = legacyKeysToRemove;
        }

        boolean isEmpty() {
            return legacyKeysToRemove.isEmpty();
        }

        boolean needsWrite() {
            return !entriesToWrite.isEmpty() || counterToWrite != null;
        }
    }

    /**
     * @param legacy  all entries of the default preferences
     * @param current all entries of the new file
     */
    static Plan plan(Map<String, ?> legacy, Map<String, ?> current) {
        Map<String, String> entries = new HashMap<>();
        Set<String> remove = new HashSet<>();
        Long legacyCounter = null;
        for (Map.Entry<String, ?> entry : legacy.entrySet()) {
            String key = entry.getKey();
            if (!isCacheKey(key)) {
                continue;
            }
            // whatever it is, the old key goes; only strings (and the counter number) are worth copying
            remove.add(key);
            Object value = entry.getValue();
            if (key.equals(ID_COUNTER_KEY)) {
                if (value instanceof Number) {
                    legacyCounter = ((Number) value).longValue();
                }
            } else if (value instanceof String && !current.containsKey(key)) {
                entries.put(key, (String) value);
            }
        }
        Long counter = null;
        if (legacyCounter != null) {
            Object stored = current.get(ID_COUNTER_KEY);
            long storedCounter = stored instanceof Number ? ((Number) stored).longValue() : Long.MIN_VALUE;
            if (legacyCounter > storedCounter) {
                counter = legacyCounter;
            }
        }
        return new Plan(entries, counter, remove);
    }
}
