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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** A play queue holds one kind of media, the kind of the item it was started from. */
public class SameMediaKindTest {

    private static Item item(String id, String mime) {
        Item item = new Item(id, "0", id, "yaacc", (DIDLObject.Class) null);
        if (mime != null) {
            item.addResource(new Res(new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mime, "*"), 1L, "http://x/" + id));
        }
        return item;
    }

    @Test
    public void aFolderOfImagesAndVideosQueuesOnlyTheImagesForAnImage() {
        Item img1 = item("a", "image/jpeg");
        Item vid = item("b", "video/mp4");
        Item img2 = item("c", "image/png");
        Item audio = item("d", "audio/mpeg");
        List<Item> queue = SameMediaKind.of(Arrays.asList(img1, vid, img2, audio), img2);
        assertEquals(Arrays.asList(img1, img2), queue);
    }

    @Test
    public void theOrderOfTheFolderIsKept() {
        Item a = item("a", "video/mp4");
        Item b = item("b", "image/jpeg");
        Item c = item("c", "video/x-matroska");
        assertEquals(Arrays.asList(a, c), SameMediaKind.of(Arrays.asList(a, b, c), a));
    }

    @Test
    public void aTargetWithoutKnownKindKeepsEverything() {
        Item unknown = item("u", "application/octet-stream");
        List<Item> all = Arrays.asList(unknown, item("a", "image/jpeg"), item("b", "video/mp4"));
        assertEquals(all, SameMediaKind.of(all, unknown));
        assertEquals(all, SameMediaKind.of(all, null));
    }

    @Test
    public void theTargetIsAlwaysInTheQueue() {
        Item target = item("t", null);
        List<Item> all = Arrays.asList(item("a", "image/jpeg"), target);
        assertEquals(all, SameMediaKind.of(all, target));
    }

    @Test
    public void kindOfReadsTheFirstMediaResource() {
        assertEquals("image", SameMediaKind.kindOf(item("a", "image/jpeg")));
        assertEquals("audio", SameMediaKind.kindOf(item("a", "audio/mpeg")));
        assertNull(SameMediaKind.kindOf(item("a", "text/plain")));
        assertNull(SameMediaKind.kindOf(item("a", null)));
        assertNull(SameMediaKind.kindOf(null));
    }

    @Test
    public void aNullListGivesAnEmptyQueue() {
        assertEquals(0, SameMediaKind.of(null, null).size());
        Item only = item("a", "image/jpeg");
        assertSame(only, SameMediaKind.of(Arrays.asList(only), only).get(0));
    }
}
