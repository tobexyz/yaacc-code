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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashSet;

import de.yaacc.upnp.server.contentdirectory.ContentDirectoryIDs;
import de.yaacc.util.SAFCacheManager;

/** /saf/&lt;key&gt;/&lt;name&gt;: a file is served only from a tree that is selected for the server, also when its short id is still known. */
public class SafServingTest {

    private static final String MUSIC = "content://com.android.externalstorage.documents/tree/primary%3AMusic";
    private static final String DCIM = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";
    // what the settings store: the document uri of the ticked folder, a whole tree and a folder below a tree
    private static final String SELECTED_TREE = MUSIC + "/document/primary%3AMusic";
    private static final String SELECTED_FOLDER = DCIM + "/document/primary%3ADCIM%2FCamera";

    private Context context;
    private MockedStatic<PreferenceManager> preferences;
    private MockedStatic<SAFCacheManager> caches;
    private MockedStatic<DocumentFile> documents;

    @Before
    public void setUp() {
        context = mock(Context.class);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(sharedPreferences.getStringSet(any(), any())).thenReturn(new HashSet<>(java.util.Arrays.asList(SELECTED_TREE, SELECTED_FOLDER)));
        preferences = Mockito.mockStatic(PreferenceManager.class);
        preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPreferences);
        SAFCacheManager cache = mock(SAFCacheManager.class);
        when(cache.getUriForShortId("1")).thenReturn(MUSIC + "/document/primary%3AMusic%2Fa.mp4");
        when(cache.getUriForShortId("2")).thenReturn("content://com.android.externalstorage.documents/tree/primary%3AVideos/document/primary%3AVideos%2Fb.mp4");
        when(cache.getUriForShortId("3")).thenReturn(DCIM + "/document/primary%3ADCIM%2FCamera%2Fc.mp4");
        when(cache.getUriForShortId("4")).thenReturn(DCIM + "/document/primary%3ADCIM%2FScreenshots%2Fd.mp4");
        caches = Mockito.mockStatic(SAFCacheManager.class);
        caches.when(() -> SAFCacheManager.getInstance(any())).thenReturn(cache);
        DocumentFile file = mock(DocumentFile.class);
        when(file.exists()).thenReturn(true);
        when(file.isDirectory()).thenReturn(false);
        when(file.getType()).thenReturn("video/mp4");
        documents = Mockito.mockStatic(DocumentFile.class);
        documents.when(() -> DocumentFile.fromSingleUri(any(), any())).thenReturn(file);
    }

    @After
    public void tearDown() {
        preferences.close();
        caches.close();
        documents.close();
    }

    private YaaccUpnpServerContentHttpHandler.ContentHolder lookup(String shortId) {
        return new YaaccUpnpServerContentHttpHandler(context).lookupSafContent(
                ContentDirectoryIDs.SAF_PREFIX.getId() + shortId, shortId + ".mp4", Collections.emptyList());
    }

    @Test
    public void aFileOfASelectedTreeIsServed() {
        assertNotNull(lookup("1"));
    }

    @Test
    public void aFileBelowASelectedFolderIsServedAndASiblingFolderOfTheSameTreeIsNot() {
        assertNotNull(lookup("3"));
        assertNull(lookup("4"));
    }

    @Test
    public void aFileOfADeselectedTreeIsNotServedAndNotEvenOpened() {
        assertNull(lookup("2"));

        documents.verify(() -> DocumentFile.fromSingleUri(any(), any()), never());
    }
}
