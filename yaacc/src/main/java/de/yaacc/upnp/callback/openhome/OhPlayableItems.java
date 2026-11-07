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
package de.yaacc.upnp.callback.openhome;

import android.net.Uri;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.item.Item;

import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhUriPolicy;
import de.yaacc.player.PlayableItem;
import de.yaacc.util.YaaccLogger;

/** Conversion between the tracks of an OpenHome queue (address and DIDL-Lite text) and the app's {@link PlayableItem}s. */
public final class OhPlayableItems {
    private static final String DEFAULT_MIME_TYPE = "audio/mpeg";

    private OhPlayableItems() {
    }

    /** Never null: unreadable metadata give an item that plays the address; the address of the queue wins over the metadata. */
    public static PlayableItem fromTrack(String uri, String metadata, int defaultDurationMillis) {
        PlayableItem item = null;
        try {
            if (metadata != null && !metadata.isEmpty() && metadata.length() <= OhQueue.MAX_METADATA_LENGTH) {
                DIDLContent content = new DIDLParser().parse(metadata);
                if (!content.getItems().isEmpty()) {
                    item = new PlayableItem(content.getItems().get(0), defaultDurationMillis);
                }
            }
        } catch (Exception e) {
            // a device may send anything as metadata; the track is shown by its address then
            YaaccLogger.d(OhPlayableItems.class.getName(), "OpenHome track metadata not readable: " + e.getClass().getSimpleName());
        }
        if (item == null) {
            item = new PlayableItem();
            item.setTitle(uri);
            item.setDuration(defaultDurationMillis);
        }
        item.setUri(Uri.parse(uri));
        if (item.getMimeType() == null || item.getMimeType().isEmpty()) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri);
            String mimeType = extension == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            item.setMimeType(mimeType == null ? DEFAULT_MIME_TYPE : mimeType);
        }
        return item;
    }

    /** @return true if a device can fetch the track: the queue takes http and https addresses only */
    public static boolean isSendable(PlayableItem item) {
        return item != null && item.getUri() != null && OhUriPolicy.isAcceptable(item.getUri().toString());
    }

    /** The DIDL-Lite text of the item, empty if it has none or it cannot be written. */
    public static String metadataOf(PlayableItem item) {
        Item didl = item == null ? null : item.getItem();
        if (didl == null) {
            return "";
        }
        try {
            return new DIDLParser().generate(new DIDLContent().addItem(didl));
        } catch (Exception e) {
            YaaccLogger.d(OhPlayableItems.class.getName(), "OpenHome track metadata not writable: " + e.getClass().getSimpleName());
            return "";
        }
    }
}
