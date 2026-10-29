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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which cached durations belong to a SAF tree whose permission was given up. Plain JVM. */
public class SAFCacheTreeKeyTest {

    private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";

    @Test
    public void aFileBelowTheTreeBelongsToIt() {
        assertTrue(SAFCacheManager.isCacheKeyUnderTree(
                "saf_cache_" + TREE + "/document/primary%3ADCIM%2Fa.mp3", TREE));
    }

    @Test
    public void aSiblingTreeWithTheSamePrefixDoesNot() {
        assertFalse(SAFCacheManager.isCacheKeyUnderTree(
                "saf_cache_" + TREE + "2/document/x", TREE));
    }

    @Test
    public void otherPreferencesAreNeverTouched() {
        assertFalse(SAFCacheManager.isCacheKeyUnderTree("settings_duration_format_key", TREE));
        assertFalse(SAFCacheManager.isCacheKeyUnderTree(null, TREE));
        assertFalse(SAFCacheManager.isCacheKeyUnderTree("saf_cache_" + TREE + "/x", null));
    }
}
