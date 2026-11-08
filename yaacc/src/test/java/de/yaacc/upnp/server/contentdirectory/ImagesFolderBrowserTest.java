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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.R;

/** The folder "Images" holds two fixed folders; a client that pages through them or sorts them gets what it asked for. */
public class ImagesFolderBrowserTest {

    private static final SortCriterion[] NO_SORT = new SortCriterion[0];

    private Context context;
    private YaaccContentDirectory directory;
    private MockedStatic<PreferenceManager> preferences;

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getString(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (id == R.string.all_images) return "All images";
            if (id == R.string.bucket_names) return "Galleries";
            return "text " + id;
        });
        ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        doAnswer(invocation -> MediaCursors.of(new String[]{"_id", "bucket_id", "bucket_display_name"}, new Object[0][]))
                .when(resolver).query(any(), any(), nullable(String.class), any(), nullable(String.class));
        directory = mock(YaaccContentDirectory.class);
        when(directory.getContext()).thenReturn(context);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenReturn(false);
        preferences = Mockito.mockStatic(PreferenceManager.class);
        preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPreferences);
    }

    @After
    public void tearDown() {
        preferences.close();
    }

    private List<String> titles(long first, long max, SortCriterion... sort) {
        List<String> titles = new ArrayList<>();
        for (DIDLObject object : new ImagesFolderBrowser(context).browseContainer(directory, "100999", first, max, sort)) {
            titles.add(object.getTitle());
        }
        return titles;
    }

    @Test
    public void theFolderHoldsAllImagesAndTheGalleriesInThisOrder() {
        assertEquals(Arrays.asList("All images", "Galleries"), titles(0, Long.MAX_VALUE));
        assertEquals(Arrays.asList("All images", "Galleries"), titles(0, 50));
        assertEquals(2, (int) new ImagesFolderBrowser(context).getSize(directory, "100999"));
    }

    @Test
    public void aPageIsCutFromTheTwoFolders() {
        assertEquals(Arrays.asList("All images"), titles(0, 1));
        assertEquals(Arrays.asList("Galleries"), titles(1, 1));
        assertEquals(Arrays.asList("Galleries"), titles(1, Long.MAX_VALUE));
        assertTrue(titles(0, 0).isEmpty());
    }

    @Test
    public void aPagePastTheEndIsEmptyNotTheLastFolderAgain() {
        assertTrue(titles(2, 50).isEmpty());
        assertTrue(titles(2, Long.MAX_VALUE).isEmpty());
        assertTrue(titles(Long.MAX_VALUE, 1).isEmpty());
    }

    @Test
    public void oddRequestsGiveNoErrorAndNoFolderTwice() {
        assertTrue(titles(0, Long.MIN_VALUE).isEmpty());
        assertTrue(titles(0, -1).isEmpty());
        assertEquals(Arrays.asList("All images", "Galleries"), titles(Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(titles(Long.MAX_VALUE, Long.MAX_VALUE).isEmpty());
    }

    @Test
    public void aHugeCountGivesBothFolders() {
        assertEquals(Arrays.asList("All images", "Galleries"), titles(0, 4294967297L));
    }

    @Test
    public void theFoldersCanBeSortedAndPaged() {
        SortCriterion descending = new SortCriterion(false, "dc:title");

        assertEquals(Arrays.asList("Galleries", "All images"), titles(0, Long.MAX_VALUE, descending));
        assertEquals(Arrays.asList("All images"), titles(1, 1, descending));
    }
}
