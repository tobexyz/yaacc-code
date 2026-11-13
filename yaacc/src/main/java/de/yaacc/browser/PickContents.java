/*
 *
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

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.Item;

import de.yaacc.share.PickContent;

/**
 * What the receiver picker is told about a browsed object that is about to be played (11.2). An item is read by the type of
 * its resource, as {@link SameMediaKind} does for the queue of "play all" (that queue is of the kind of the item). A folder is
 * unknown, except a photo album. Plain JVM.
 */
final class PickContents {

    private static final String PHOTO_ALBUM = "object.container.album.photoAlbum";

    private PickContents() {
    }

    static PickContent of(DIDLObject object) {
        if (object instanceof Item) {
            return PickContent.ofKind(SameMediaKind.kindOf((Item) object));
        }
        if (object != null && object.getClazz() != null && object.getClazz().getValue() != null
                && object.getClazz().getValue().startsWith(PHOTO_ALBUM)) {
            return PickContent.ofKind("image");
        }
        return PickContent.UNKNOWN;
    }
}
