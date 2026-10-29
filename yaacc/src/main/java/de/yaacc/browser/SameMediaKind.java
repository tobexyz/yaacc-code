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

import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrows a play queue to the kind of media of the item the user started it from. A folder of the
 * storage access framework (e.g. DCIM/Camera) holds images and videos side by side, unlike the
 * media store folders, which are by kind. A mixed queue goes to the multi content player, which
 * hands every item to another app through a chooser; a queue of one kind goes to the in-app
 * image viewer, the video player or the music player. Plain JVM.
 */
final class SameMediaKind {

    private SameMediaKind() {
    }

    /** @return "image", "audio" or "video", or null if the item has no resource of such a type */
    static String kindOf(Item item) {
        if (item == null || item.getResources() == null) {
            return null;
        }
        for (Res resource : item.getResources()) {
            if (resource == null || resource.getProtocolInfo() == null) {
                continue;
            }
            String format = resource.getProtocolInfo().getContentFormat();
            if (format == null) {
                continue;
            }
            for (String kind : new String[]{"image", "audio", "video"}) {
                if (format.startsWith(kind + "/")) {
                    return kind;
                }
            }
        }
        return null;
    }

    /**
     * @return the items of the same kind as {@code target}, in their order (a copy); all items if the
     * target has no known kind
     */
    static List<Item> of(List<Item> items, Item target) {
        if (items == null) {
            return new ArrayList<>();
        }
        String kind = kindOf(target);
        if (kind == null) {
            return new ArrayList<>(items);
        }
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (item == target || kind.equals(kindOf(item))) {
                result.add(item);
            }
        }
        return result;
    }
}
