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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SafCacheMigrationTest {

    private static final String K1 = "saf_cache_content://tree/a";
    private static final String K2 = "saf_cache_content://tree/b";

    @Test
    public void onlyCacheKeysAreSelected() {
        assertTrue(SafCacheMigration.isCacheKey(K1));
        assertTrue(SafCacheMigration.isCacheKey("saf_id_counter"));
        assertTrue(SafCacheMigration.isCacheKey("saf_cache_"));
        assertFalse(SafCacheMigration.isCacheKey("saf_cache"));
        assertFalse(SafCacheMigration.isCacheKey("settings_saf_cache_x"));
        assertFalse(SafCacheMigration.isCacheKey("saf_id_counter2"));
        assertFalse(SafCacheMigration.isCacheKey("settings_duration_format_key"));
        assertFalse(SafCacheMigration.isCacheKey(null));
    }

    @Test
    public void copiesEntriesAndCounterAndRemovesExactlyTheOldKeys() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put(K1, "00:01:00|audio/mpeg|1|10|5");
        legacy.put(K2, "|video/mp4|2|20|6");
        legacy.put("saf_id_counter", 3L);
        legacy.put("settings_theme", "dark");
        legacy.put("some_boolean", true);

        SafCacheMigration.Plan plan = SafCacheMigration.plan(legacy, new HashMap<>());

        assertFalse(plan.isEmpty());
        assertTrue(plan.needsWrite());
        assertEquals(Map.of(K1, "00:01:00|audio/mpeg|1|10|5", K2, "|video/mp4|2|20|6"), plan.entriesToWrite);
        assertEquals(Long.valueOf(3), plan.counterToWrite);
        assertEquals(Set.of(K1, K2, "saf_id_counter"), plan.legacyKeysToRemove);
    }

    @Test
    public void nothingToDoOnceTheOldKeysAreGone() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("settings_theme", "dark");
        SafCacheMigration.Plan plan = SafCacheMigration.plan(legacy, Map.of(K1, "x", "saf_id_counter", 9L));
        assertTrue(plan.isEmpty());
        assertFalse(plan.needsWrite());
    }

    @Test
    public void anInterruptedMigrationNeverOverwritesTheNewFile() {
        // the new file was written, the old keys are still there (the process died before removing them);
        // meanwhile the new file got a newer value for K1 and a higher counter
        Map<String, Object> legacy = new HashMap<>();
        legacy.put(K1, "old-value");
        legacy.put(K2, "old-b");
        legacy.put("saf_id_counter", 5L);
        Map<String, Object> current = new HashMap<>();
        current.put(K1, "newer-value");
        current.put("saf_id_counter", 40L);

        SafCacheMigration.Plan plan = SafCacheMigration.plan(legacy, current);

        assertEquals(Map.of(K2, "old-b"), plan.entriesToWrite);
        assertNull("the larger counter is the new file's", plan.counterToWrite);
        assertEquals(Set.of(K1, K2, "saf_id_counter"), plan.legacyKeysToRemove);
    }

    @Test
    public void theLargerCounterWins() {
        Map<String, Object> legacy = Map.of("saf_id_counter", 50L);
        assertEquals(Long.valueOf(50), SafCacheMigration.plan(legacy, Map.of("saf_id_counter", 7L)).counterToWrite);
        assertNull(SafCacheMigration.plan(legacy, Map.of("saf_id_counter", 50L)).counterToWrite);
        assertEquals(Long.valueOf(50), SafCacheMigration.plan(legacy, new HashMap<>()).counterToWrite);
    }

    @Test
    public void entriesOfTheWrongTypeAreDroppedNotCopied() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put(K1, 12);          // not a string
        legacy.put("saf_id_counter", "seven"); // not a number
        SafCacheMigration.Plan plan = SafCacheMigration.plan(legacy, new HashMap<>());
        assertTrue(plan.entriesToWrite.isEmpty());
        assertNull(plan.counterToWrite);
        assertFalse(plan.needsWrite());
        assertEquals(Set.of(K1, "saf_id_counter"), plan.legacyKeysToRemove);
    }

    @Test
    public void runningTheMigrationTwiceChangesNothingTheSecondTime() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put(K1, "a");
        legacy.put("saf_id_counter", 4L);
        Map<String, Object> current = new HashMap<>();

        SafCacheMigration.Plan first = SafCacheMigration.plan(legacy, current);
        current.putAll(first.entriesToWrite);
        current.put("saf_id_counter", first.counterToWrite);
        legacy.keySet().removeAll(first.legacyKeysToRemove);

        assertTrue(SafCacheMigration.plan(legacy, current).isEmpty());
        assertEquals("a", current.get(K1));
        assertEquals(4L, current.get("saf_id_counter"));
    }
}
