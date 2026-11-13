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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;

import de.yaacc.share.PickContent;

/** 11.2: what the receiver picker is told about the browsed object that is about to be played. */
public class PickContentsTest {

    private static Item item(String mime) {
        Item item = new Item("i", "0", "i", "yaacc", (DIDLObject.Class) null);
        if (mime != null) {
            item.addResource(new Res(new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mime, "*"), 1L, "http://x/i"));
        }
        return item;
    }

    @Test
    public void aMusicItemOffersEveryReceiver() {
        assertTrue(PickContents.of(item("audio/mpeg")).offersAudioOnlyReceivers());
    }

    @Test
    public void anImageOrAVideoItemTakesTheAudioOnlyReceiversAway() {
        assertFalse(PickContents.of(item("image/jpeg")).offersAudioOnlyReceivers());
        assertFalse(PickContents.of(item("video/mp4")).offersAudioOnlyReceivers());
    }

    @Test
    public void anItemWithoutAKnownTypeOffersEveryReceiver() {
        assertTrue(PickContents.of(item(null)).offersAudioOnlyReceivers());
        assertTrue(PickContents.of(item("application/octet-stream")).offersAudioOnlyReceivers());
    }

    @Test
    public void aPlaylistFileIsStillAMusicItemForThePicker() {
        assertTrue(PickContents.of(item("audio/x-mpegurl")).offersAudioOnlyReceivers());
    }

    @Test
    public void aPhotoAlbumTakesTheAudioOnlyReceiversAwayAndOtherFoldersDoNot() {
        Container photos = new Container();
        photos.setClazz(new DIDLObject.Class("object.container.album.photoAlbum"));
        Container music = new Container();
        music.setClazz(new DIDLObject.Class("object.container.album.musicAlbum"));
        Container plain = new Container();

        assertFalse(PickContents.of(photos).offersAudioOnlyReceivers());
        assertTrue(PickContents.of(music).offersAudioOnlyReceivers());
        assertTrue(PickContents.of(plain).offersAudioOnlyReceivers());
    }

    @Test
    public void nothingKnownOffersEveryReceiver() {
        assertSame(PickContent.UNKNOWN, PickContents.of(null));
        assertTrue(PickContents.of(mock(DIDLObject.class)).offersAudioOnlyReceivers());
    }
}
