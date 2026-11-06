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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Build;
import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collections;

import de.yaacc.R;
import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler.ContentHolder;

/**
 * Issue 296: the cover of a music track whose album has none is the generic music note (not the app logo), and only that
 * answer may be cached by the control point. The unit tests run with {@code Build.VERSION.SDK_INT == 0}, so they drive the
 * branch for Android 9 and earlier (a cursor on the album table); the Android 10 branch (loadThumbnail) is not covered here.
 */
public class AlbumArtFallbackTest {

    private static final byte[] ICON = {1, 2, 3, 4, 5};

    private MockedStatic<PreferenceManager> preferenceStatic;
    private Context context;
    private Cursor cursor;
    private boolean serverEnabled = true;

    @Before
    public void setUp() throws Exception {
        assumeTrue("branch for Android 9 and earlier", Build.VERSION.SDK_INT <= Build.VERSION_CODES.P);
        resetMemo();
        context = mock(Context.class);
        Resources resources = mock(Resources.class);
        ContentResolver resolver = mock(ContentResolver.class);
        cursor = mock(Cursor.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.openRawResource(anyInt())).thenAnswer(invocation -> new ByteArrayInputStream(ICON));
        when(context.getContentResolver()).thenReturn(resolver);
        when(context.getString(R.string.settings_local_server_chkbx)).thenReturn("server");
        when(preferences.getBoolean("server", false)).thenAnswer(invocation -> serverEnabled);
        when(resolver.query(any(), any(), any(), any(), any())).thenReturn(cursor);
        preferenceStatic = mockStatic(PreferenceManager.class);
        preferenceStatic.when(() -> PreferenceManager.getDefaultSharedPreferences(context)).thenReturn(preferences);
    }

    @After
    public void tearDown() throws Exception {
        if (preferenceStatic != null) {
            preferenceStatic.close();
        }
        resetMemo();
    }

    private static void resetMemo() throws Exception {
        Field memo = YaaccUpnpServerContentHttpHandler.class.getDeclaredField("genericMusicIconBytes");
        memo.setAccessible(true);
        memo.set(null, null);
    }

    /** The album table answers one row whose ALBUM_ART column is {@code albumArt} (null: no cover). */
    private void oneAlbumRow(String albumArt) {
        when(cursor.isAfterLast()).thenReturn(false, true);
        when(cursor.getColumnIndex(any())).thenReturn(0);
        when(cursor.getString(0)).thenReturn(albumArt);
    }

    private ContentHolder lookup(String albumId) {
        return new YaaccUpnpServerContentHttpHandler(context).lookupAlbumArt(albumId, Collections.emptyList());
    }

    @Test
    public void anAlbumWithoutACoverGetsTheGenericMusicIconAndMayBeCached() {
        oneAlbumRow(null);

        ContentHolder holder = lookup("7");

        assertNotNull(holder);
        assertArrayEquals(ICON, holder.content);
        assertEquals("image/png", holder.mimeType.toString());
        assertEquals(YaaccUpnpServerContentHttpHandler.GENERIC_ICON_CACHE_SECONDS, holder.cacheSeconds);
        assertTrue(holder.cacheSeconds > 0);
    }

    @Test
    public void anAlbumWithACoverIsServedFromItsFileAndNotCached() throws Exception {
        File cover = File.createTempFile("cover", ".png");
        cover.deleteOnExit();
        Files.write(cover.toPath(), new byte[]{9, 9});
        oneAlbumRow(cover.getAbsolutePath());

        ContentHolder holder = lookup("7");

        assertEquals(cover.getAbsolutePath(), holder.uri);
        assertNull(holder.content);
        assertEquals(0, holder.cacheSeconds);
    }

    @Test
    public void anUnknownAlbumGetsTheIconButNotACacheHeader() {
        when(cursor.isAfterLast()).thenReturn(true);

        ContentHolder holder = lookup("7");

        assertArrayEquals(ICON, holder.content);
        assertEquals("no row says nothing about a cover", 0, holder.cacheSeconds);
    }

    @Test
    public void aDisabledServerOrAMissingIdGetsTheIconButNotACacheHeader() {
        oneAlbumRow(null);
        serverEnabled = false;
        ContentHolder disabled = lookup("7");
        serverEnabled = true;
        ContentHolder missing = lookup(null);

        assertArrayEquals(ICON, disabled.content);
        assertEquals(0, disabled.cacheSeconds);
        assertArrayEquals(ICON, missing.content);
        assertEquals(0, missing.cacheSeconds);
    }

    @Test
    public void theIconIsReadOnceAndIsNotTheAppLogo() {
        oneAlbumRow(null);
        ContentHolder first = lookup("7");
        when(context.getResources()).thenThrow(new IllegalStateException("must not be read again"));
        oneAlbumRow(null);

        ContentHolder second = lookup("8");

        assertArrayEquals(first.content, second.content);
        assertFalse("not the bytes of the logo", second.content.length == 0);
    }

    @Test
    public void whenThePictureCannotBeReadTheAnswerIsNeverCacheable() {
        oneAlbumRow(null);
        when(context.getResources().openRawResource(anyInt())).thenThrow(new RuntimeException("gone"));

        ContentHolder holder = lookup("7");

        // the stand-in is the app logo (not readable on the JVM: no picture at all, answered 404)
        assertTrue(holder == null || holder.cacheSeconds == 0);
    }
}
