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

import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;

/** The album art address of a track. */
final class AlbumArt {
    private AlbumArt() {
    }

    private static boolean isWebAddress(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * @return the {@code albumArtURI} of the DIDL-Lite item; null if it has none or an empty one (Home Assistant sends an empty
     * address with every track it plays: that must not count as "has art", the other sources of art are tried then), and null for an
     * address that is not http or https: the item comes from another device and the app fetches the address (a {@code file:} or
     * {@code content:} address would read from this phone)
     */
    static URI of(DIDLObject item) {
        if (item == null) {
            return null;
        }
        DIDLObject.Property<URI> property = item.getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        URI uri = property == null ? null : property.getValue();
        return uri == null || !isWebAddress(uri) ? null : uri;
    }
}
