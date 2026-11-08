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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import de.yaacc.player.PlayableItem;

/** The mirror of a track of a device keeps only an address the app would fetch. */
public class OhPlayableItemsTest {
    private MockedStatic<Uri> uriStatic;

    @Before
    public void setUp() {
        uriStatic = mockStatic(Uri.class);
        uriStatic.when(() -> Uri.parse(anyString())).thenAnswer(invocation -> mock(Uri.class));
    }

    @After
    public void tearDown() {
        uriStatic.close();
    }

    @Test
    public void anHttpAddressIsKept() {
        PlayableItem item = OhPlayableItems.fromTrack("http://h/a.mp3", "", 1000);

        assertNotNull(item.getUri());
    }

    @Test
    public void otherAddressesAreNotKeptButTheTrackIsStillListed() {
        PlayableItem file = OhPlayableItems.fromTrack("file:///data/data/de.yaacc/secret", "", 1000);
        PlayableItem content = OhPlayableItems.fromTrack("content://media/external/audio/1", "", 1000);

        assertNull(file.getUri());
        assertNull(content.getUri());
        assertNotNull(file.getTitle());
    }
}
