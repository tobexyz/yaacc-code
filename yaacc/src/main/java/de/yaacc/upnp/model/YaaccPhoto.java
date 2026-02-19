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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.upnp.model;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.Photo;

import java.net.URI;

/**
 * Lightweight Photo wrapper that avoids Cling Property overhead.
 * Supports: album art URI.
 */
public class YaaccPhoto extends YaaccItem {
    
    private URI albumArtUri;
    
    public YaaccPhoto(String id, String parentId, String title, String creator, boolean restricted) {
        super(id, parentId, title, creator, restricted, "object.item.imageItem.photo");
    }
    
    public void setAlbumArtUri(URI albumArtUri) {
        this.albumArtUri = albumArtUri;
    }
    
    public URI getAlbumArtUri() {
        return albumArtUri;
    }
    
    /**
     * Convert to Cling Photo for UPnP serialization.
     */
    @Override
    public Photo toClingItem() {
        Res[] clingResources = new Res[resources.size()];
        for (int i = 0; i < resources.size(); i++) {
            clingResources[i] = resources.get(i).toClingRes();
        }
        
        Photo photo = new Photo(
            getId(),
            getParentId(),
            getTitle(),
            getCreator(),
            "",
            clingResources
        );
        
        photo.setRestricted(isRestricted());
        
        if (albumArtUri != null) {
            photo.replaceFirstProperty(new DIDLObject.Property.UPNP.ALBUM_ART_URI(albumArtUri));
        }
        
        return photo;
    }
    
    /**
     * Create from Cling Photo.
     */
    public static YaaccPhoto fromClingPhoto(Photo photo) {
        YaaccPhoto yaaccPhoto = new YaaccPhoto(
            photo.getId(),
            photo.getParentID(),
            photo.getTitle(),
            photo.getCreator(),
            photo.isRestricted()
        );
        
        URI albumArtUri = photo.getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        if (albumArtUri != null) {
            yaaccPhoto.setAlbumArtUri(albumArtUri);
        }
        
        for (Res res : photo.getResources()) {
            yaaccPhoto.addResource(YaaccRes.fromClingRes(res));
        }
        
        return yaaccPhoto;
    }
}
