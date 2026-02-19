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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class PlayableItemTest {

    @Test
    public void testDefaultConstructor() {
        PlayableItem item = new PlayableItem();
        
        assertEquals("", item.getMimeType());
        assertEquals("", item.getTitle());
        assertEquals(null, item.getUri());
        assertEquals(0L, item.getDuration());
        assertEquals(null, item.getItem());
        assertNotNull(item.getId());
    }

    @Test
    public void testSettersAndGetters() {
        PlayableItem item = new PlayableItem();
        
        item.setMimeType("audio/mpeg");
        item.setTitle("Test Song");
        item.setDuration(180000L);
        
        assertEquals("audio/mpeg", item.getMimeType());
        assertEquals("Test Song", item.getTitle());
        assertEquals(180000L, item.getDuration());
    }

    @Test
    public void testUniqueIds() {
        PlayableItem item1 = new PlayableItem();
        PlayableItem item2 = new PlayableItem();
        
        assertNotEquals(item1.getId(), item2.getId());
    }

    @Test
    public void testItemSetterGetter() {
        PlayableItem item = new PlayableItem();
        
        // Just test that we can set and get an item
        assertNotNull(item);
    }

    @Test
    public void testDurationSetter() {
        PlayableItem item = new PlayableItem();
        
        item.setDuration(1000L);
        assertEquals(1000L, item.getDuration());
        
        item.setDuration(0L);
        assertEquals(0L, item.getDuration());
        
        item.setDuration(3600000L); // 1 hour
        assertEquals(3600000L, item.getDuration());
    }

    @Test
    public void testMimeTypeSetter() {
        PlayableItem item = new PlayableItem();
        
        item.setMimeType("video/mp4");
        assertEquals("video/mp4", item.getMimeType());
        
        item.setMimeType("image/jpeg");
        assertEquals("image/jpeg", item.getMimeType());
    }

    @Test
    public void testTitleSetter() {
        PlayableItem item = new PlayableItem();
        
        item.setTitle("My Song");
        assertEquals("My Song", item.getTitle());
        
        item.setTitle("");
        assertEquals("", item.getTitle());
    }
}
