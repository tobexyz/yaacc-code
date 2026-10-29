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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafCacheEntriesTest {

    @Test
    public void aDurationOnItsOwnIsBare() {
        assertTrue(SafCacheEntries.isBareDuration("00:03:12"));
        assertTrue(SafCacheEntries.isBareDuration("123:59:59"));
    }

    @Test
    public void everythingElseIsNotBare() {
        assertFalse(SafCacheEntries.isBareDuration(null));
        assertFalse(SafCacheEntries.isBareDuration(""));
        assertFalse(SafCacheEntries.isBareDuration("00:03"));
        assertFalse(SafCacheEntries.isBareDuration("00:3:12"));
        assertFalse(SafCacheEntries.isBareDuration("aa:bb:cc"));
        assertFalse(SafCacheEntries.isBareDuration("00:03:12|audio/mpeg|7|20|2"));
        assertFalse(SafCacheEntries.isBareDuration("|image/jpeg|4|10|1"));
    }

    @Test
    public void metadataIsParsedAndBareOrCorruptValuesAreNot() {
        SAFMetadata metadata = SafCacheEntries.parseMetadata("00:01:00|audio/mpeg|7|20|2");
        assertNotNull(metadata);
        assertEquals("7", metadata.shortId);
        assertNull(SafCacheEntries.parseMetadata(null));
        assertNull(SafCacheEntries.parseMetadata("00:03:12"));
        assertNull("a size that is no number does not throw", SafCacheEntries.parseMetadata("|a/b|1|x|2"));
    }

    @Test
    public void theDurationOfAnEntryInEitherForm() {
        assertEquals("00:03:12", SafCacheEntries.durationOf("00:03:12"));
        assertEquals("00:01:00", SafCacheEntries.durationOf("00:01:00|audio/mpeg|7|20|2"));
        assertEquals("", SafCacheEntries.durationOf("|audio/mpeg|7|20|2"));
        assertEquals("", SafCacheEntries.durationOf(null));
        assertEquals("", SafCacheEntries.durationOf("garbage"));
    }
}
