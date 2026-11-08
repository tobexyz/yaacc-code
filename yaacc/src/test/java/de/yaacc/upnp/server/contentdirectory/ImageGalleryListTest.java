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
import static org.junit.Assert.assertNotNull;
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
import android.database.Cursor;

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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import de.yaacc.R;

/**
 * The list of the galleries of the images: a client that pages through it (the YAACC app asks for 50 at a time) must get the
 * galleries, not the first rows of the images (which may all be in one gallery), and the same list as a client that asks for all.
 */
public class ImageGalleryListTest {

    private static final String[] COLUMNS = {"bucket_id", "bucket_display_name"};

    private Context context;
    private YaaccContentDirectory directory;
    private MockedStatic<PreferenceManager> preferences;
    private Supplier<Cursor> answer;

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getString(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (id == R.string.gallery_unnamed) return "(root)";
            return "text " + id;
        });
        ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        doAnswer(invocation -> answer.get()).when(resolver).query(any(), any(), nullable(String.class), any(), nullable(String.class));
        directory = mock(YaaccContentDirectory.class);
        when(directory.getContext()).thenReturn(context);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class); // the media path filter is off
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenReturn(false);
        preferences = Mockito.mockStatic(PreferenceManager.class);
        preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPreferences);
    }

    @After
    public void tearDown() {
        preferences.close();
    }

    /** {@code camera} images in the bucket "Camera" first (as the newest images come first in the media store), then one image per other bucket. */
    private void library(int camera, String... others) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < camera; i++) {
            rows.add(new Object[]{"1", "Camera"});
        }
        for (int i = 0; i < others.length; i++) {
            rows.add(new Object[]{String.valueOf(10 + i), others[i]});
        }
        Object[][] all = rows.toArray(new Object[0][]);
        answer = () -> MediaCursors.of(COLUMNS, all);
    }

    private List<Container> list(long first, long max, SortCriterion... sort) {
        return new ImagesByBucketNamesFolderBrowser(context).browseContainer(directory, "200999", first, max, sort);
    }

    private static List<String> names(List<Container> containers) {
        return containers.stream().map(DIDLObject::getTitle).collect(Collectors.toList());
    }

    @Test
    public void aPageHoldsGalleriesNotRowsOfImages() {
        library(120, "DCIM", "Pictures", "Screenshots"); // the first 50 rows are all of one gallery

        List<Container> page = list(0, 50);

        assertEquals(Arrays.asList("Camera", "DCIM", "Pictures", "Screenshots"), names(page));
    }

    @Test
    public void pagesOfTheListAddUpToTheListOfAllGalleries() {
        library(5, "b", "c", "d", "e", "f", "g");

        List<String> all = names(list(0, Long.MAX_VALUE));
        List<String> paged = new ArrayList<>();
        paged.addAll(names(list(0, 3)));
        paged.addAll(names(list(3, 3)));
        paged.addAll(names(list(6, 3)));

        assertEquals(7, all.size());
        assertEquals(all, paged);
        assertEquals(0, list(7, 3).size());
    }

    @Test
    public void theTotalIsTheNumberOfGalleries() {
        library(120, "DCIM", "Pictures");

        assertEquals(Integer.valueOf(3), new ImagesByBucketNamesFolderBrowser(context).getSize(directory, "200999"));
    }

    @Test
    public void theGalleriesAreSortedByNameWithoutRegardToTheCase() {
        library(1, "Slack", "musiccastImages", "DCIM");

        assertEquals(Arrays.asList("Camera", "DCIM", "musiccastImages", "Slack"), names(list(0, Long.MAX_VALUE)));
        assertEquals(Arrays.asList("Slack", "musiccastImages", "DCIM", "Camera"),
                names(list(0, Long.MAX_VALUE, new SortCriterion(false, "dc:title"))));
    }

    @Test
    public void everyGalleryHasTheCountOfItsImages() {
        library(3, "DCIM");

        List<Container> galleries = list(0, Long.MAX_VALUE);

        assertEquals(Integer.valueOf(3), galleries.get(0).getChildCount());
        assertEquals(Integer.valueOf(1), galleries.get(1).getChildCount());
    }

    @Test
    public void twoDirectoriesWithTheSameNameAreToldApart() {
        answer = () -> MediaCursors.of(COLUMNS, new Object[]{"5", "Pictures"}, new Object[]{"3", "Pictures"}, new Object[]{"7", null});

        List<Container> galleries = list(0, Long.MAX_VALUE);

        assertEquals(Arrays.asList("(root)", "Pictures", "Pictures (2)"), names(galleries));
    }

    @Test
    public void theNameInTheGalleryIsTheNameInTheList() {
        answer = () -> MediaCursors.of(COLUMNS, new Object[]{"5", "Pictures"}, new Object[]{"3", "Pictures"});

        DIDLObject second = new ImagesByBucketNameFolderBrowser(context).browseMeta(directory, "2109995", 0, 1, new SortCriterion[0]);

        assertNotNull(second);
        assertEquals("Pictures (2)", second.getTitle());
    }
}
