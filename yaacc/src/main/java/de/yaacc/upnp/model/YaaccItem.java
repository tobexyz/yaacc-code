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
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight Item wrapper that avoids Cling Property overhead.
 * Converts to Cling Item only when needed for serialization.
 */
public class YaaccItem {
    
    private final String id;
    private final String parentId;
    private final String title;
    private final String creator;
    private final boolean restricted;
    private final String clazz; // e.g., "object.item.audioItem"
    protected List<YaaccRes> resources = new ArrayList<>();
    
    public YaaccItem(String id, String parentId, String title, String creator, boolean restricted, String clazz) {
        this.id = id;
        this.parentId = parentId;
        this.title = title;
        this.creator = creator;
        this.restricted = restricted;
        this.clazz = clazz;
    }
    
    public String getId() {
        return id;
    }
    
    public String getParentId() {
        return parentId;
    }
    
    public String getTitle() {
        return title;
    }
    
    public String getCreator() {
        return creator;
    }
    
    public boolean isRestricted() {
        return restricted;
    }
    
    public String getClazz() {
        return clazz;
    }
    
    public List<YaaccRes> getResources() {
        return resources;
    }
    
    public void addResource(YaaccRes res) {
        resources.add(res);
    }
    
    /**
     * Convert to Cling Item for UPnP serialization.
     */
    public Item toClingItem() {
        Res[] clingResources = new Res[resources.size()];
        for (int i = 0; i < resources.size(); i++) {
            clingResources[i] = resources.get(i).toClingRes();
        }
        
        Item item;
        if (clazz.contains("audioItem")) {
            item = new AudioItem(id, parentId, title, creator, clingResources);
        } else if (clazz.contains("videoItem")) {
            item = new VideoItem(id, parentId, title, creator, clingResources);
        } else if (clazz.contains("imageItem")) {
            item = new ImageItem(id, parentId, title, creator, clingResources);
        } else {
            item = new Item(id, parentId, title, creator, new org.fourthline.cling.support.model.DIDLObject.Class(clazz));
        }
        
        item.setRestricted(restricted);
        return item;
    }
    
    /**
     * Create from Cling Item.
     */
    public static YaaccItem fromClingItem(Item item) {
        String itemClazz = item.getClazz() != null ? item.getClazz().getValue() : "object.item";
        YaaccItem yaaccItem = new YaaccItem(
            item.getId(),
            item.getParentID(),
            item.getTitle(),
            item.getCreator(),
            item.isRestricted(),
            itemClazz
        );
        
        for (org.fourthline.cling.support.model.Res res : item.getResources()) {
            yaaccItem.addResource(YaaccRes.fromClingRes(res));
        }
        
        return yaaccItem;
    }
}
