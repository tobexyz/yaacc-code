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

import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.item.Photo;
import org.junit.Test;

import java.net.URI;

public class YaaccPhotoTest {

    private ProtocolInfo createProtocolInfo(String mimeType) {
        return new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType, ProtocolInfo.WILDCARD);
    }

    @Test
    public void testBasicCreation() {
        YaaccPhoto photo = new YaaccPhoto(
            "photo_id",
            "parent_id",
            "Photo Name",
            "Photographer",
            false
        );
        
        assertEquals("photo_id", photo.getId());
        assertEquals("parent_id", photo.getParentId());
        assertEquals("Photo Name", photo.getTitle());
        assertEquals("Photographer", photo.getCreator());
        assertEquals(false, photo.isRestricted());
    }

    @Test
    public void testAddResource() {
        YaaccPhoto photo = new YaaccPhoto(
            "photo_id", "parent_id", "Photo", "Creator", false
        );
        
        photo.addResource(new YaaccRes(
            createProtocolInfo("image/jpeg"),
            1234567L,
            null,
            null,
            "http://example.com/photo.jpg"
        ));
        
        assertNotNull(photo.getResources());
        assertEquals(1, photo.getResources().size());
        
        YaaccRes res = photo.getResources().get(0);
        assertEquals("http://example.com/photo.jpg", res.getValue());
        assertEquals(Long.valueOf(1234567L), res.getSize());
    }

    @Test
    public void testAlbumArtUri() {
        YaaccPhoto photo = new YaaccPhoto(
            "id", "parent", "Photo", "Creator", false
        );
        
        photo.setAlbumArtUri(URI.create("http://example.com/thumb.jpg"));
        assertEquals("http://example.com/thumb.jpg", photo.getAlbumArtUri().toString());
    }

    @Test
    public void testToClingPhoto() {
        YaaccPhoto yaaccPhoto = new YaaccPhoto(
            "photo_id",
            "parent_id",
            "Photo Name",
            "Photographer",
            false
        );
        yaaccPhoto.addResource(new YaaccRes(
            createProtocolInfo("image/jpeg"),
            1234567L,
            null,
            null,
            "http://example.com/photo.jpg"
        ));
        yaaccPhoto.setAlbumArtUri(URI.create("http://example.com/thumb.jpg"));
        
        Photo clingPhoto = yaaccPhoto.toClingItem();
        
        assertNotNull(clingPhoto);
        assertEquals("photo_id", clingPhoto.getId());
        assertEquals("parent_id", clingPhoto.getParentID());
        assertEquals("Photo Name", clingPhoto.getTitle());
        assertNotNull(clingPhoto.getResources());
        assertEquals(1, clingPhoto.getResources().size());
    }

    @Test
    public void testMimeTypeVariants() {
        // JPEG
        YaaccPhoto jpeg = new YaaccPhoto("id", "parent", "Photo", "Creator", false);
        jpeg.addResource(new YaaccRes(createProtocolInfo("image/jpeg"), 100L, null, null, "uri"));
        assertEquals("image/jpeg", jpeg.getResources().get(0).getProtocolInfo().getContentFormat());
        
        // PNG
        YaaccPhoto png = new YaaccPhoto("id", "parent", "Photo", "Creator", false);
        png.addResource(new YaaccRes(createProtocolInfo("image/png"), 100L, null, null, "uri"));
        assertEquals("image/png", png.getResources().get(0).getProtocolInfo().getContentFormat());
        
        // GIF
        YaaccPhoto gif = new YaaccPhoto("id", "parent", "Photo", "Creator", false);
        gif.addResource(new YaaccRes(createProtocolInfo("image/gif"), 100L, null, null, "uri"));
        assertEquals("image/gif", gif.getResources().get(0).getProtocolInfo().getContentFormat());
        
        // WebP
        YaaccPhoto webp = new YaaccPhoto("id", "parent", "Photo", "Creator", false);
        webp.addResource(new YaaccRes(createProtocolInfo("image/webp"), 100L, null, null, "uri"));
        assertEquals("image/webp", webp.getResources().get(0).getProtocolInfo().getContentFormat());
    }

    @Test
    public void testNullAlbumArt() {
        YaaccPhoto photo = new YaaccPhoto(
            "id", "parent", "Photo", "Creator", false
        );
        
        assertEquals(null, photo.getAlbumArtUri());
    }

    @Test
    public void testMultipleResources() {
        YaaccPhoto photo = new YaaccPhoto(
            "id", "parent", "Photo", "Creator", false
        );
        
        photo.addResource(new YaaccRes(createProtocolInfo("image/jpeg"), 100L, null, null, "http://uri1"));
        photo.addResource(new YaaccRes(createProtocolInfo("image/png"), 200L, null, null, "http://uri2"));
        
        assertEquals(2, photo.getResources().size());
        assertEquals("http://uri1", photo.getResources().get(0).getValue());
        assertEquals("http://uri2", photo.getResources().get(1).getValue());
    }
}
