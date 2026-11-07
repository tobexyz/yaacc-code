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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.junit.Test;

import java.net.URI;

/** An empty album art address is no album art. */
public class AlbumArtTest {

    private static MusicTrack track(String art) {
        MusicTrack track = new MusicTrack();
        if (art != null) {
            track.addProperty(new DIDLObject.Property.UPNP.ALBUM_ART_URI(URI.create(art)));
        }
        return track;
    }

    @Test
    public void anAddressIsReturned() {
        assertEquals(URI.create("http://h/art.jpg"), AlbumArt.of(track("http://h/art.jpg")));
    }

    @Test
    public void anEmptyAddressIsNoAlbumArt() {
        assertNull(AlbumArt.of(track("")));
    }

    @Test
    public void aTrackWithoutAddressOrWithoutItemHasNoAlbumArt() {
        assertNull(AlbumArt.of(track(null)));
        assertNull(AlbumArt.of(null));
    }
}
