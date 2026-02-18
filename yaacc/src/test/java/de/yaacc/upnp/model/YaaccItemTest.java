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
package de.yaacc.upnp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;
import org.seamless.util.MimeType;

import java.util.List;

public class YaaccItemTest {

    private ProtocolInfo createProtocolInfo(String mimeType) {
        return new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType, ProtocolInfo.WILDCARD);
    }

    @Test
    public void testBasicCreation() {
        YaaccItem item = new YaaccItem(
            "item_id",
            "parent_id",
            "Item Title",
            "Creator",
            false,
            "object.item.audioItem.musicTrack"
        );
        
        assertEquals("item_id", item.getId());
        assertEquals("parent_id", item.getParentId());
        assertEquals("Item Title", item.getTitle());
        assertEquals("Creator", item.getCreator());
        assertEquals(false, item.isRestricted());
    }

    @Test
    public void testResourcesList() {
        YaaccItem item = new YaaccItem(
            "item_id", "parent_id", "Title", "Creator", false, "object.item.audioItem"
        );
        
        item.addResource(new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            123L,
            "03:00",
            null,
            "http://uri"
        ));
        
        List<YaaccRes> resources = item.getResources();
        assertNotNull(resources);
        assertEquals(1, resources.size());
        
        YaaccRes res = resources.get(0);
        assertEquals("http://uri", res.getValue());
    }

    @Test
    public void testToClingItem() {
        YaaccItem item = new YaaccItem(
            "item_id",
            "parent_id",
            "Item Title",
            "Creator",
            false,
            "object.item.audioItem"
        );
        item.addResource(new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            12345L,
            "03:45",
            null,
            "http://example.com/file.mp3"
        ));
        
        Item clingItem = item.toClingItem();
        
        assertNotNull(clingItem);
        assertEquals("item_id", clingItem.getId());
        assertEquals("parent_id", clingItem.getParentID());
        assertEquals("Item Title", clingItem.getTitle());
        assertEquals("Creator", clingItem.getCreator());
        assertEquals(false, clingItem.isRestricted());
        assertNotNull(clingItem.getResources());
        assertEquals(1, clingItem.getResources().size());
    }

    @Test
    public void testMultipleResources() {
        YaaccItem item = new YaaccItem(
            "item_id", "parent_id", "Title", "Creator", false, "object.item"
        );
        
        item.addResource(new YaaccRes(createProtocolInfo("audio/mpeg"), 100L, "01:00", null, "http://uri1"));
        item.addResource(new YaaccRes(createProtocolInfo("video/mp4"), 200L, null, null, "http://uri2"));
        
        List<YaaccRes> resources = item.getResources();
        assertEquals(2, resources.size());
        assertEquals("http://uri1", resources.get(0).getValue());
        assertEquals("http://uri2", resources.get(1).getValue());
    }
}
