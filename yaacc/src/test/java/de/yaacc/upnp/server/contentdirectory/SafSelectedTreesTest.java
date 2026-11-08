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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.SortCriterion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashSet;

import de.yaacc.util.SAFCacheManager;

/**
 * The SAF folders: a short id of the cache names a document, but only a document of a tree that is selected for the server is described,
 * listed or counted. A tree that was deselected keeps its short ids (and the permission of the app), a host of the network could count
 * through them.
 */
public class SafSelectedTreesTest {

    private static final SortCriterion[] NO_SORT = new SortCriterion[0];
    private static final String MUSIC = "content://com.android.externalstorage.documents/tree/primary%3AMusic";
    private static final String DCIM = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";
    /** What the settings store: the document uri of the ticked folder, a whole tree and a folder below a tree. */
    private static final String SELECTED_TREE = MUSIC + "/document/primary%3AMusic";
    private static final String SELECTED_FOLDER = DCIM + "/document/primary%3ADCIM%2FCamera";
    private static final String SELECTED_DOCUMENT = MUSIC + "/document/primary%3AMusic%2Fa.mp3";
    private static final String DESELECTED_DOCUMENT = "content://com.android.externalstorage.documents/tree/primary%3AVideos/document/primary%3AVideos%2Fb.mp4";
    private static final String SIBLING_DOCUMENT = DCIM + "/document/primary%3ADCIM%2FScreenshots%2Fc.png";
    private static final String BELOW_SELECTED_FOLDER = DCIM + "/document/primary%3ADCIM%2FCamera%2Fd.jpg";

    private Context context;
    private YaaccContentDirectory directory;
    private MockedStatic<PreferenceManager> preferences;
    private MockedStatic<SAFCacheManager> caches;
    private MockedStatic<SafDirectoryLister> lister;

    @Before
    public void setUp() {
        context = mock(Context.class);
        directory = mock(YaaccContentDirectory.class);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(sharedPreferences.getStringSet(any(), any())).thenReturn(new HashSet<>(java.util.Arrays.asList(SELECTED_TREE, SELECTED_FOLDER)));
        preferences = Mockito.mockStatic(PreferenceManager.class);
        preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPreferences);
        SAFCacheManager cache = mock(SAFCacheManager.class);
        when(cache.getUriForShortId("1")).thenReturn(SELECTED_DOCUMENT);
        when(cache.getUriForShortId("2")).thenReturn(DESELECTED_DOCUMENT);
        when(cache.getUriForShortId("3")).thenReturn(SIBLING_DOCUMENT);
        when(cache.getUriForShortId("4")).thenReturn(BELOW_SELECTED_FOLDER);
        caches = Mockito.mockStatic(SAFCacheManager.class);
        caches.when(() -> SAFCacheManager.getInstance(any())).thenReturn(cache);
        lister = Mockito.mockStatic(SafDirectoryLister.class);
    }

    @After
    public void tearDown() {
        preferences.close();
        caches.close();
        lister.close();
    }

    private static String id(String shortId) {
        return ContentDirectoryIDs.SAF_PREFIX.getId() + shortId;
    }

    @Test
    public void theMetadataOfADocumentInADeselectedTreeIsNoSuchObject() {
        assertNull(new SafFolderBrowser(context).browseMeta(directory, id("2"), 0, 1, NO_SORT));

        lister.verify(() -> SafDirectoryLister.queryDocument(any(), any()), never()); // not even asked for
    }

    @Test
    public void theMetadataOfADocumentInASelectedTreeIsLookedUp() {
        new SafFolderBrowser(context).browseMeta(directory, id("1"), 0, 1, NO_SORT);

        lister.verify(() -> SafDirectoryLister.queryDocument(any(), any()));
    }

    @Test
    public void aFolderOfADeselectedTreeHasNoCountAndNoChildren() {
        SafFolderBrowser browser = new SafFolderBrowser(context);

        assertEquals(0, (int) browser.getSize(directory, id("2")));
        assertTrue(browser.browseContainer(directory, id("2"), 0, Long.MAX_VALUE, NO_SORT).isEmpty());
        assertTrue(browser.browseItem(directory, id("2"), 0, Long.MAX_VALUE, NO_SORT).isEmpty());
    }

    @Test
    public void aSiblingOfASelectedFolderIsNoSuchObjectThoughTheTreeIsGranted() {
        assertNull(new SafFolderBrowser(context).browseMeta(directory, id("3"), 0, 1, NO_SORT));

        lister.verify(() -> SafDirectoryLister.queryDocument(any(), any()), never());
    }

    @Test
    public void aDocumentBelowASelectedFolderIsLookedUp() {
        new SafFolderBrowser(context).browseMeta(directory, id("4"), 0, 1, NO_SORT);

        lister.verify(() -> SafDirectoryLister.queryDocument(any(), any()));
    }

    @Test
    public void anUnknownShortIdIsStillNoSuchObject() {
        assertNull(new SafFolderBrowser(context).browseMeta(directory, id("99"), 0, 1, NO_SORT));
    }
}
