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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Build;
import android.provider.MediaStore;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler.ContentHolder;

/**
 * What the content server answers for an id: the thumbnail of an image and the cover of an album are served only when the image
 * or a track of the album is in the media paths of the server settings. The ids are consecutive numbers any host of the network can
 * count through. Plain JVM (Android 9 branch of the album art, see {@link AlbumArtFallbackTest}).
 */
public class MediaPathServingTest {

    private static final byte[] ICON = {1, 2, 3, 4, 5};

    private MockedStatic<PreferenceManager> preferenceStatic;
    private MockedStatic<MediaStore.Images.Thumbnails> thumbnails;
    private Context context;
    private Cursor cursor;
    private final List<String> selections = new ArrayList<>();
    private final List<List<String>> selectionArgs = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        assumeTrue("branch for Android 9 and earlier", Build.VERSION.SDK_INT <= Build.VERSION_CODES.P);
        Field memo = YaaccUpnpServerContentHttpHandler.class.getDeclaredField("genericMusicIconBytes");
        memo.setAccessible(true);
        memo.set(null, null);
        context = mock(Context.class);
        Resources resources = mock(Resources.class);
        ContentResolver resolver = mock(ContentResolver.class);
        cursor = mock(Cursor.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.openRawResource(anyInt())).thenAnswer(invocation -> new ByteArrayInputStream(ICON));
        when(context.getContentResolver()).thenReturn(resolver);
        when(context.getString(R.string.settings_local_server_chkbx)).thenReturn("server");
        when(preferences.getBoolean("server", false)).thenReturn(true);
        doAnswer(invocation -> {
            selections.add(invocation.getArgument(2));
            String[] args = invocation.getArgument(3);
            selectionArgs.add(args == null ? new ArrayList<>() : Arrays.asList(args));
            return cursor;
        }).when(resolver).query(any(), any(), nullable(String.class), any(), nullable(String.class));
        preferenceStatic = mockStatic(PreferenceManager.class);
        preferenceStatic.when(() -> PreferenceManager.getDefaultSharedPreferences(context)).thenReturn(preferences);
        thumbnails = mockStatic(MediaStore.Images.Thumbnails.class);
        thumbnails.when(() -> MediaStore.Images.Thumbnails.getThumbnail(any(), anyLong(), anyInt(), any())).thenReturn(mock(Bitmap.class));
    }

    @After
    public void tearDown() {
        if (preferenceStatic != null) {
            preferenceStatic.close();
        }
        if (thumbnails != null) {
            thumbnails.close();
        }
    }

    private YaaccUpnpServerContentHttpHandler handler() {
        return new YaaccUpnpServerContentHttpHandler(context);
    }

    @Test
    public void theThumbnailOfAnImageInTheMediaPathsIsServed() {
        when(cursor.getCount()).thenReturn(1);

        ContentHolder holder = handler().lookupThumbnail("7", Collections.emptyList());

        assertNotNull(holder.content); // the picture, not the default icon (which is null here, no resources)
        assertEquals("image/png", holder.mimeType.toString());
        assertTrue(selections.get(0).contains("_id=?"));
        assertTrue(selections.get(0).contains("_data like ?"));
        assertEquals("7", selectionArgs.get(0).get(0));
    }

    @Test
    public void theThumbnailOfAnImageOutsideTheMediaPathsIsNotServed() {
        when(cursor.getCount()).thenReturn(0);

        ContentHolder holder = handler().lookupThumbnail("7", Collections.emptyList());

        assertNull("no picture: the default icon (null without resources)", holder.content);
        thumbnails.verify(() -> MediaStore.Images.Thumbnails.getThumbnail(any(), anyLong(), anyInt(), any()), never());
    }

    @Test
    public void aThumbnailRequestWithoutAMediaStoreAnswerIsNotServedEither() {
        // a null cursor (the media store is not there)
        ContentResolver resolver = context.getContentResolver();
        when(resolver.query(any(), any(), nullable(String.class), any(), nullable(String.class))).thenReturn(null);

        ContentHolder holder = handler().lookupThumbnail("7", Collections.emptyList());

        assertNull(holder.content);
        thumbnails.verify(() -> MediaStore.Images.Thumbnails.getThumbnail(any(), anyLong(), anyInt(), any()), never());
    }

    @Test
    public void theCoverOfAnAlbumWithoutATrackInTheMediaPathsIsTheGenericIconAndNotCached() {
        when(cursor.getCount()).thenReturn(0);

        ContentHolder holder = handler().lookupAlbumArt("7", Collections.emptyList());

        assertArrayEquals(ICON, holder.content);
        assertEquals(0, holder.cacheSeconds);
        assertEquals(1, selections.size()); // only the check, the album table was not asked
        assertTrue(selections.get(0).contains("album_id=?"));
        assertTrue(selections.get(0).contains("_data like ?"));
        assertEquals("7", selectionArgs.get(0).get(0));
    }

    @Test
    public void theNotFoundPageDoesNotRepeatTheRequest() {
        assertEquals("<html><body>Resource not found</body></html>", YaaccUpnpServerContentHttpHandler.NOT_FOUND_PAGE);
        assertTrue(!YaaccUpnpServerContentHttpHandler.NOT_FOUND_PAGE.contains("%"));
    }
}
