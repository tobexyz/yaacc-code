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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;

import org.junit.Test;

/** {@link ProviderProbe}: the provider's type and name are read defensively. */
public class ProviderProbeTest {

    private final ContentResolver resolver = mock(ContentResolver.class);
    private final Uri uri = mock(Uri.class);

    private Cursor cursorWithName(String name) {
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToFirst()).thenReturn(true);
        when(cursor.getColumnIndex("_display_name")).thenReturn(0);
        when(cursor.getType(0)).thenReturn(Cursor.FIELD_TYPE_STRING);
        when(cursor.getString(0)).thenReturn(name);
        when(resolver.query(nullable(Uri.class), any(), nullable(String.class), any(), nullable(String.class)))
                .thenReturn(cursor);
        return cursor;
    }

    @Test
    public void readsTheTypeAndTheName() {
        when(resolver.getType(uri)).thenReturn("audio/mpeg");
        Cursor cursor = cursorWithName("Song.mp3");

        ProviderProbe.Info info = ProviderProbe.query(resolver, uri);

        assertEquals("audio/mpeg", info.getProviderType());
        assertEquals("Song.mp3", info.getDisplayName());
        verify(cursor).close();
    }

    @Test
    public void aFailingTypeIsNullAndTheNameIsStillRead() {
        when(resolver.getType(uri)).thenThrow(new SecurityException("no"));
        cursorWithName("x.mp3");

        ProviderProbe.Info info = ProviderProbe.query(resolver, uri);

        assertNull(info.getProviderType());
        assertEquals("x.mp3", info.getDisplayName());
    }

    @Test
    public void aMissingCursorIsNoName() {
        when(resolver.getType(uri)).thenReturn("audio/mpeg");
        when(resolver.query(nullable(Uri.class), any(), nullable(String.class), any(), nullable(String.class)))
                .thenReturn(null);

        assertNull(ProviderProbe.query(resolver, uri).getDisplayName());
    }

    @Test
    public void aFailingQueryIsNoName() {
        when(resolver.query(nullable(Uri.class), any(), nullable(String.class), any(), nullable(String.class)))
                .thenThrow(new IllegalArgumentException("bad"));

        assertNull(ProviderProbe.query(resolver, uri).getDisplayName());
    }

    @Test
    public void aNameThatIsNotTextIsNoName() {
        Cursor cursor = cursorWithName("ignored");
        when(cursor.getType(0)).thenReturn(Cursor.FIELD_TYPE_BLOB);

        assertNull(ProviderProbe.query(resolver, uri).getDisplayName());
    }

    @Test
    public void aMissingColumnIsNoName() {
        Cursor cursor = cursorWithName("ignored");
        when(cursor.getColumnIndex("_display_name")).thenReturn(-1);

        assertNull(ProviderProbe.query(resolver, uri).getDisplayName());
    }

    @Test
    public void aHugeNameIsCutBeforeItIsCleaned() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            huge.append('a');
        }
        cursorWithName(huge.toString());

        assertEquals(ProviderProbe.MAX_NAME_CHARS, ProviderProbe.query(resolver, uri).getDisplayName().length());
    }
}
