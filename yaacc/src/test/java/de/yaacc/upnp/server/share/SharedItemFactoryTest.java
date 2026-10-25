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
package de.yaacc.upnp.server.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.VideoItem;
import org.junit.Test;

import de.yaacc.player.PlayableItem;

/** {@link SharedItemFactory}: the DIDL item class follows the MIME type, nothing else is invented. */
public class SharedItemFactoryTest {

    private static final String URL = "http://192.168.1.5:49157/proxy/abcdefghijklmnopqrstuv";

    private static Uri uri() {
        // the unit-test android.jar returns null for Uri.parse; the factory only needs toString()
        Uri uri = org.mockito.Mockito.mock(Uri.class);
        org.mockito.Mockito.when(uri.toString()).thenReturn(URL);
        return uri;
    }

    private static Res firstRes(PlayableItem item) {
        return item.getItem().getFirstResource();
    }

    @Test
    public void audioBecomesAMusicTrackWithTheGivenUrlAndSize() {
        PlayableItem item = SharedItemFactory.create("audio/mpeg", "My song", uri(), 1234L, 0L, 0L);

        assertNotNull(item);
        assertTrue(item.getItem() instanceof MusicTrack);
        assertEquals("audio/mpeg", item.getMimeType());
        assertEquals("My song", item.getTitle());
        assertEquals(URL, firstRes(item).getValue());
        assertEquals(Long.valueOf(1234L), firstRes(item).getSize());
        assertEquals("audio/mpeg", firstRes(item).getProtocolInfo().getContentFormat());
        assertNull("no duration is invented", firstRes(item).getDuration());
        assertEquals(0L, item.getDuration());
    }

    @Test
    public void videoBecomesAVideoItem() {
        PlayableItem item = SharedItemFactory.create("video/mp4", "Clip", uri(), 99L, 0L, 0L);

        assertTrue(item.getItem() instanceof VideoItem);
    }

    @Test
    public void imageBecomesAnImageItem() {
        PlayableItem item = SharedItemFactory.create("image/jpeg", "Photo", uri(), 5L, 0L, 0L);

        assertTrue(item.getItem() instanceof ImageItem);
    }

    @Test
    public void aWildcardImageTypeStillBuildsAnImageItem() {
        // the link fallback "assume a shared image"
        PlayableItem item = SharedItemFactory.create("image/*", "x", uri(), 0L, 0L, 0L);

        assertTrue(item.getItem() instanceof ImageItem);
    }

    @Test
    public void aKnownDurationIsAnnouncedInTheDidlFormat() {
        PlayableItem item = SharedItemFactory.create("audio/mpeg", "x", uri(), 10L, 3_723_000L, 128_000L);

        assertEquals("01:02:03", firstRes(item).getDuration());
        assertEquals(Long.valueOf(128_000L), firstRes(item).getBitrate());
        assertEquals(3_723_000L, item.getDuration());
    }

    @Test
    public void anUnknownSizeIsNotAnnounced() {
        PlayableItem item = SharedItemFactory.create("audio/mpeg", "x", uri(), 0L, 0L, 0L);

        assertNull(firstRes(item).getSize());
    }

    @Test
    public void otherTypesAndBrokenInputAreRefused() {
        assertNull(SharedItemFactory.create("text/html", "x", uri(), 1L, 0L, 0L));
        assertNull(SharedItemFactory.create("application/octet-stream", "x", uri(), 1L, 0L, 0L));
        assertNull(SharedItemFactory.create(null, "x", uri(), 1L, 0L, 0L));
        assertNull(SharedItemFactory.create("audio/mpeg", "x", null, 1L, 0L, 0L));
        assertNull(SharedItemFactory.create("not a mime type", "x", uri(), 1L, 0L, 0L));
    }
}
