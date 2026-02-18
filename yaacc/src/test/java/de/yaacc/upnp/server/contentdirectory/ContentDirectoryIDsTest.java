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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContentDirectoryIDsTest {

    @Test
    public void testRootId() {
        assertEquals("0", ContentDirectoryIDs.ROOT.getId());
    }

    @Test
    public void testParentOfRootId() {
        assertEquals("-1", ContentDirectoryIDs.PARENT_OF_ROOT.getId());
    }

    @Test
    public void testImagesFolderId() {
        assertEquals("100999", ContentDirectoryIDs.IMAGES_FOLDER.getId());
    }

    @Test
    public void testVideosFolderId() {
        assertEquals("400999", ContentDirectoryIDs.VIDEOS_FOLDER.getId());
    }

    @Test
    public void testMusicFolderId() {
        assertEquals("500999", ContentDirectoryIDs.MUSIC_FOLDER.getId());
    }

    @Test
    public void testSafFolderId() {
        assertEquals("1000999", ContentDirectoryIDs.SAF_FOLDER.getId());
    }

    @Test
    public void testLiveStreamFolderId() {
        assertEquals("1200999", ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId());
    }

    @Test
    public void testAllIdsUnique() {
        ContentDirectoryIDs[] values = ContentDirectoryIDs.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals("IDs should be unique: " + values[i].getId() + " vs " + values[j].getId(), 
                    values[i].getId(), values[j].getId());
            }
        }
    }

    @Test
    public void testIdFormat() {
        // All IDs should be valid format (digits only, or with underscore for prefix)
        for (ContentDirectoryIDs id : ContentDirectoryIDs.values()) {
            String idStr = id.getId();
            assertNotNull(idStr);
            assertFalse("ID should not be empty: " + id.name(), idStr.isEmpty());
            assertTrue("ID should match format: " + idStr, idStr.matches("^[0-9_-]+$"));
        }
    }

    @Test
    public void testValuesLength() {
        // Should have 28 values
        assertEquals(28, ContentDirectoryIDs.values().length);
    }

    @Test
    public void testValueOf() {
        assertEquals(ContentDirectoryIDs.ROOT, ContentDirectoryIDs.valueOf("ROOT"));
        assertEquals(ContentDirectoryIDs.MUSIC_FOLDER, ContentDirectoryIDs.valueOf("MUSIC_FOLDER"));
        assertEquals(ContentDirectoryIDs.VIDEOS_FOLDER, ContentDirectoryIDs.valueOf("VIDEOS_FOLDER"));
        assertEquals(ContentDirectoryIDs.SAF_FOLDER, ContentDirectoryIDs.valueOf("SAF_FOLDER"));
        assertEquals(ContentDirectoryIDs.LIVE_STREAM_FOLDER, ContentDirectoryIDs.valueOf("LIVE_STREAM_FOLDER"));
    }

    @Test
    public void testOrdinalOrder() {
        assertEquals(0, ContentDirectoryIDs.PARENT_OF_ROOT.ordinal());
        assertEquals(1, ContentDirectoryIDs.ROOT.ordinal());
    }

    @Test
    public void testNameMethod() {
        assertEquals("ROOT", ContentDirectoryIDs.ROOT.name());
        assertEquals("MUSIC_FOLDER", ContentDirectoryIDs.MUSIC_FOLDER.name());
        assertEquals("SAF_FOLDER", ContentDirectoryIDs.SAF_FOLDER.name());
        assertEquals("LIVE_STREAM_FOLDER", ContentDirectoryIDs.LIVE_STREAM_FOLDER.name());
    }

    @Test
    public void testLiveStreamSubIds() {
        assertEquals("1210999", ContentDirectoryIDs.LIVE_STREAM_SYSTEM_AUDIO.getId());
        assertEquals("1220999", ContentDirectoryIDs.LIVE_STREAM_SCREEN_CAST.getId());
        assertEquals("1230999", ContentDirectoryIDs.LIVE_STREAM_COMBINED.getId());
    }

    @Test
    public void testMusicSubIds() {
        assertEquals("600999", ContentDirectoryIDs.MUSIC_ALL_TITLES_FOLDER.getId());
        assertEquals("700999", ContentDirectoryIDs.MUSIC_GENRES_FOLDER.getId());
        assertEquals("800999", ContentDirectoryIDs.MUSIC_ALBUMS_FOLDER.getId());
        assertEquals("900999", ContentDirectoryIDs.MUSIC_ARTISTS_FOLDER.getId());
    }
}
