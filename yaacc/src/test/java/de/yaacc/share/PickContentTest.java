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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import de.yaacc.player.PlayableItem;

/** 11.2: what the receiver picker knows about the content, so that a receiver that plays music only is not offered for pictures. */
public class PickContentTest {

    @Test
    public void theDefaultOffersEveryReceiver() {
        assertTrue(PickContent.UNKNOWN.offersAudioOnlyReceivers());
    }

    @Test
    public void musicOffersAnAudioOnlyReceiver() {
        assertTrue(PickContent.ofMimeTypes(Arrays.asList("audio/mpeg", "audio/flac")).offersAudioOnlyReceivers());
    }

    @Test
    public void aSingleImageOrVideoTakesAnAudioOnlyReceiverAway() {
        assertFalse(PickContent.ofMimeTypes(Collections.singletonList("image/jpeg")).offersAudioOnlyReceivers());
        assertFalse(PickContent.ofMimeTypes(Collections.singletonList("video/mp4")).offersAudioOnlyReceivers());
        assertFalse(PickContent.ofMimeTypes(Arrays.asList("audio/mpeg", "audio/flac", "video/mp4")).offersAudioOnlyReceivers());
        assertFalse(PickContent.ofMimeTypes(Arrays.asList("image/png", "audio/mpeg")).offersAudioOnlyReceivers());
    }

    @Test
    public void aPlaylistFileAmongMusicChangesNothing() {
        assertTrue(PickContent.ofMimeTypes(Arrays.asList("audio/mpeg", "audio/x-mpegurl")).offersAudioOnlyReceivers());
    }

    @Test
    public void aTypeThatIsNotKnownIsNoReasonToHideAReceiver() {
        assertTrue(PickContent.ofMimeTypes(Arrays.asList(null, "", "application/octet-stream")).offersAudioOnlyReceivers());
        assertTrue(PickContent.ofMimeTypes(Collections.<String>emptyList()).offersAudioOnlyReceivers());
        assertTrue(PickContent.ofMimeTypes(null).offersAudioOnlyReceivers());
    }

    @Test
    public void theTypeIsComparedWithoutCase() {
        assertFalse(PickContent.ofMimeTypes(Collections.singletonList("IMAGE/JPEG; q=1")).offersAudioOnlyReceivers());
    }

    @Test
    public void thePickedQueueOfFilesIsReadFromItsThreeLists() {
        PickedQueue.Entry song = new PickedQueue.Entry("content://x/a", "a.mp3", "audio/mpeg", 1L);
        PickedQueue.Entry film = new PickedQueue.Entry("content://x/b", "b.mp4", "video/mp4", 1L);
        PickedQueue.Entry photo = new PickedQueue.Entry("content://x/c", "c.jpg", "image/jpeg", 1L);

        assertTrue(PickContent.of(PickedQueue.build(new ArrayList<>(Collections.singletonList(song)), false)).offersAudioOnlyReceivers());
        assertFalse(PickContent.of(PickedQueue.build(new ArrayList<>(Arrays.asList(song, film)), false)).offersAudioOnlyReceivers());
        assertFalse(PickContent.of(PickedQueue.build(new ArrayList<>(Arrays.asList(song, photo)), false)).offersAudioOnlyReceivers());
        assertFalse(PickContent.of(PickedQueue.build(new ArrayList<>(Collections.singletonList(photo)), false)).offersAudioOnlyReceivers());
    }

    @Test
    public void aKindName() {
        assertTrue(PickContent.ofKind("audio").offersAudioOnlyReceivers());
        assertFalse(PickContent.ofKind("image").offersAudioOnlyReceivers());
        assertFalse(PickContent.ofKind("video").offersAudioOnlyReceivers());
        assertTrue("no kind known", PickContent.ofKind(null).offersAudioOnlyReceivers());
        assertEquals(PickContent.UNKNOWN, PickContent.ofKind(null));
    }

    @Test
    public void playableItemsAreReadByTheirType() {
        PlayableItem song = new PlayableItem();
        song.setMimeType("audio/mpeg");
        PlayableItem photo = new PlayableItem();
        photo.setMimeType("image/jpeg");

        assertTrue(PickContent.ofItems(Collections.singletonList(song)).offersAudioOnlyReceivers());
        assertFalse(PickContent.ofItems(Arrays.asList(song, photo)).offersAudioOnlyReceivers());
    }
}
