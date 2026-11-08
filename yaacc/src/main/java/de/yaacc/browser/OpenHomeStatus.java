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

import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.item.Item;

import de.yaacc.openhome.OhTransportState;
import de.yaacc.util.LogText;

/** The status line of an OpenHome device in the terms of {@link RendererStatus} (the receiver row). */
final class OpenHomeStatus {
    private OpenHomeStatus() {
    }

    /** @return the UPnP name of the transport state that {@link RendererStatus} understands */
    static String upnpState(OhTransportState state) {
        if (state == null) {
            return "NO_MEDIA_PRESENT";
        }
        switch (state) {
            case PLAYING:
            case BUFFERING:
                return "PLAYING";
            case PAUSED:
                return "PAUSED_PLAYBACK";
            default:
                return "STOPPED";
        }
    }

    /**
     * @param metadata the DIDL-Lite text of the track the device plays (from the device: untrusted)
     * @param uri      its address
     * @return the title; the last part of the address without a title; null if the device plays nothing
     */
    static String trackTitle(String metadata, String uri) {
        if (metadata != null && !metadata.isEmpty() && metadata.length() <= de.yaacc.openhome.OhQueue.MAX_METADATA_LENGTH) {
            try {
                DIDLContent content = new DIDLParser().parse(metadata);
                if (!content.getItems().isEmpty()) {
                    Item item = content.getItems().get(0);
                    String title = item.getTitle() == null ? "" : LogText.clean(item.getTitle()).trim();
                    if (!title.isEmpty()) {
                        return title;
                    }
                }
            } catch (Exception e) {
                // a device may send anything: the address is used then
            }
        }
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? null : LogText.clean(name);
    }
}
