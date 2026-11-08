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
import static org.junit.Assert.assertNull;
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
import android.database.Cursor;
import android.webkit.MimeTypeMap;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.item.Item;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * The media paths of the server settings limit what the network sees: also the metadata of one image, the content and the name of one
 * gallery of images and the name of one album, artist or genre. The ids are consecutive numbers any host of the network can count through;
 * an id outside the media paths must answer "no such object", as an id that does not exist.
 */
public class MediaPathFilterBrowsersTest {

    private static final SortCriterion[] NO_SORT = new SortCriterion[0];
    private static final String[] IMAGE_COLUMNS = {"_id", "_display_name", "mime_type", "_size", "datetaken", "bucket_id", "bucket_display_name"};

    private Context context;
    private YaaccContentDirectory directory;
    private SharedPreferences sharedPreferences;
    private MockedStatic<PreferenceManager> preferences;
    private MockedStatic<MimeTypeMap> mimeTypes;
    private Supplier<Cursor> answer;
    private final List<String> selections = new ArrayList<>();
    private final List<List<String>> selectionArgs = new ArrayList<>();

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn("text");
        ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        answer = () -> MediaCursors.of(IMAGE_COLUMNS, new Object[0][]);
        doAnswer(invocation -> {
            selections.add(invocation.getArgument(2));
            String[] args = invocation.getArgument(3);
            selectionArgs.add(args == null ? new ArrayList<>() : Arrays.asList(args));
            return answer.get();
        }).when(resolver).query(any(), any(), nullable(String.class), any(), nullable(String.class));
        directory = mock(YaaccContentDirectory.class);
        when(directory.getContext()).thenReturn(context);
        when(directory.getIpAddress()).thenReturn("10.0.0.1");
        sharedPreferences = mock(SharedPreferences.class);
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenReturn(true); // the media path filter is on
        when(sharedPreferences.getStringSet(any(), any())).thenReturn(new HashSet<>(Arrays.asList("/a", "/b")));
        preferences = Mockito.mockStatic(PreferenceManager.class);
        preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPreferences);
        MimeTypeMap map = mock(MimeTypeMap.class);
        when(map.getExtensionFromMimeType(any())).thenReturn("jpg");
        mimeTypes = Mockito.mockStatic(MimeTypeMap.class);
        mimeTypes.when(MimeTypeMap::getSingleton).thenReturn(map);
    }

    @After
    public void tearDown() {
        preferences.close();
        mimeTypes.close();
    }

    private void images(Object[]... rows) {
        answer = () -> MediaCursors.of(IMAGE_COLUMNS, rows);
    }

    private static Object[] image(long id, String name, long bucketId, String bucketName) {
        return new Object[]{id, name, "image/jpeg", 2000L, 1700000000000L, bucketId, bucketName};
    }

    /** The selection holds the id (or bucket) first and then all media paths in parentheses; the arguments follow in that order. */
    private void assertFiltered(int query, String idCondition, String id) {
        assertTrue(selections.get(query), selections.get(query).startsWith(idCondition + " and (_data like ? escape '\\' or _data like ? escape '\\' )"));
        List<String> args = selectionArgs.get(query);
        assertEquals(3, args.size());
        assertEquals(id, args.get(0));
        assertTrue(args.subList(1, 3).containsAll(Arrays.asList("%/a%", "%/b%")));
    }

    // ---- images

    @Test
    public void theMetadataOfAnImageIsOnlyAnsweredForAnImageInTheMediaPaths() {
        images(image(5, "a.jpg", 1, "Camera"));

        DIDLObject item = new ImageAllItemBrowser(context).browseMeta(directory, "3109995", 0, 1, NO_SORT);

        assertNotNull(item);
        assertFiltered(0, "_id=?", "5");
    }

    @Test
    public void anImageOutsideTheMediaPathsHasNoMetadata() {
        assertNull(new ImageAllItemBrowser(context).browseMeta(directory, "3109995", 0, 1, NO_SORT));
        assertNull(new ImageByBucketNameItemBrowser(context).browseMeta(directory, "2209995", 0, 1, NO_SORT));
    }

    @Test
    public void theMetadataOfAnImageOfAGalleryIsOnlyAnsweredForAnImageInTheMediaPaths() {
        images(image(5, "a.jpg", 1, "Camera"));

        DIDLObject item = new ImageByBucketNameItemBrowser(context).browseMeta(directory, "2209995", 0, 1, NO_SORT);

        assertNotNull(item);
        assertFiltered(0, "_id=?", "5");
    }

    @Test
    public void aGalleryOfImagesListsOnlyImagesInTheMediaPaths() {
        images(image(5, "a.jpg", 7, "Camera"), image(6, "b.jpg", 7, "Camera"));
        ImagesByBucketNameFolderBrowser browser = new ImagesByBucketNameFolderBrowser(context);

        List<Item> items = browser.browseItem(directory, "2109997", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(2, items.size());
        assertFiltered(0, "bucket_id=?", "7");
    }

    @Test
    public void aGalleryOfImagesOutsideTheMediaPathsIsNoSuchObjectAndEmpty() {
        ImagesByBucketNameFolderBrowser browser = new ImagesByBucketNameFolderBrowser(context);

        assertNull(browser.browseMeta(directory, "2109997", 0, 1, NO_SORT));
        assertEquals(0, (int) browser.getSize(directory, "2109997"));
        assertTrue(browser.browseItem(directory, "2109997", 0, Long.MAX_VALUE, NO_SORT).isEmpty());
    }

    @Test
    public void aGalleryOfImagesHasItsNameAndTheCountOfItsImagesInTheMediaPaths() {
        answer = () -> MediaCursors.of(new String[]{"bucket_display_name"}, new Object[]{"Camera"}, new Object[]{"Camera"});
        ImagesByBucketNameFolderBrowser browser = new ImagesByBucketNameFolderBrowser(context);

        org.fourthline.cling.support.model.container.Container gallery =
                (org.fourthline.cling.support.model.container.Container) browser.browseMeta(directory, "2109997", 0, 1, NO_SORT);

        assertEquals("Camera", gallery.getTitle()); // the name of the directory, not the bucket id
        assertEquals(Integer.valueOf(2), gallery.getChildCount());
        assertEquals("2109997", gallery.getId());
    }

    // ---- music

    @Test
    public void anAlbumOutsideTheMediaPathsIsNoSuchObject() {
        assertNull(new MusicAlbumFolderBrowser(context).browseMeta(directory, "8109997", 0, 1, NO_SORT));
    }

    @Test
    public void anArtistOutsideTheMediaPathsIsNoSuchObject() {
        assertNull(new MusicArtistFolderBrowser(context).browseMeta(directory, "9109997", 0, 1, NO_SORT));
    }

    @Test
    public void aGenreOutsideTheMediaPathsIsNoSuchObject() {
        assertNull(new MusicGenreFolderBrowser(context).browseMeta(directory, "7109997", 0, 1, NO_SORT));
    }

    @Test
    public void theChildCountOfAGalleryInTheListIsTheCountOfItsImagesInTheMediaPaths() {
        // the list of galleries: first the rows of the buckets, then one count per bucket
        List<Cursor> answers = new ArrayList<>();
        answers.add(MediaCursors.of(new String[]{"bucket_id", "bucket_display_name"}, new Object[]{"7", "Camera"}));             // the galleries
        answers.add(MediaCursors.of(new String[]{"bucket_id"}, new Object[]{"7"}, new Object[]{"7"}));                          // the count of bucket 7
        int[] call = {0};
        answer = () -> answers.get(Math.min(call[0]++, answers.size() - 1));

        List<org.fourthline.cling.support.model.container.Container> galleries =
                new ImagesByBucketNamesFolderBrowser(context).browseContainer(directory, "200999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(1, galleries.size());
        assertEquals(Integer.valueOf(2), galleries.get(0).getChildCount());
        int last = selections.size() - 1;
        assertFiltered(last, "bucket_id=?", "7");
    }
}
