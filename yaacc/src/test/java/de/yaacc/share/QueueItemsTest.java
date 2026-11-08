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
import static org.junit.Assert.assertNotNull;

import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.player.PlayableItem;

/**
 * The items of the players of a picked queue: an image has no length and no end of its own, so it gets the "default duration" setting (the
 * time the players show an image before the next one); audio and video have none until their share is bound (-1: the receiver says when
 * they end).
 */
public class QueueItemsTest {

    private static final long IMAGE_MILLIS = 8000L;

    private MockedStatic<Uri> uris;

    @Before
    public void setUp() {
        uris = Mockito.mockStatic(Uri.class);
        uris.when(() -> Uri.parse(Mockito.anyString())).thenAnswer(invocation -> {
            Uri uri = Mockito.mock(Uri.class);
            Mockito.when(uri.toString()).thenReturn(invocation.getArgument(0));
            return uri;
        });
    }

    @After
    public void tearDown() {
        uris.close();
    }

    private static List<PickedQueue.Entry> mixed() {
        List<PickedQueue.Entry> entries = new ArrayList<>();
        entries.add(new PickedQueue.Entry("content://p/1", "a.jpg", "image/jpeg", 10));
        entries.add(new PickedQueue.Entry("content://p/2", "b.mp3", "audio/mpeg", 10));
        entries.add(new PickedQueue.Entry("content://p/3", "c.mp4", "video/mp4", 10));
        entries.add(new PickedQueue.Entry("content://p/4", "d.png", "image/png", 10));
        return entries;
    }

    private static void assertDurations(QueueItems.Built built, long image) {
        List<PlayableItem> items = built.items();
        assertEquals(4, items.size());
        assertEquals("jpeg", image, items.get(0).getDuration());
        assertEquals("audio", -1L, items.get(1).getDuration());
        assertEquals("video", -1L, items.get(2).getDuration());
        assertEquals("png", image, items.get(3).getDuration());
    }

    @Test
    public void imagesOfAQueueThatIsSharedGetTheDefaultDuration() {
        QueueItems.Built built = QueueItems.placeholders(mixed(), IMAGE_MILLIS);

        assertNotNull(built);
        assertDurations(built, IMAGE_MILLIS);
    }

    @Test
    public void itemsOfThePhonesOwnPlayersHaveNoDuration() {
        // this phone's image viewer changes the image by its own slideshow time and does not read the item's duration
        assertDurations(QueueItems.direct(mixed()), -1L);
    }

    @Test
    public void theSettingNeverMeansNoTimerForImages() {
        // "never" is -1 in the setting: the image stays until the user goes on
        assertDurations(QueueItems.placeholders(mixed(), -1L), -1L);
    }

    @Test
    public void aSettingOfZeroIsPassedOnLikeForBrowsedItems() {
        assertDurations(QueueItems.placeholders(mixed(), 0L), 0L);
    }
}
