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
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import de.yaacc.R;

/**
 * The browsers of the folder "Videos" (all videos, the galleries and the videos of one gallery) against a fake media store:
 * what they ask for (media paths, bucket, sort order) and what they answer (ids, parents, names, counts, pages).
 */
public class VideoBrowsersTest {

    private static final SortCriterion[] NO_SORT = new SortCriterion[0];
    private static final String[] VIDEO_COLUMNS = {"_id", "_display_name", "mime_type", "_size", "duration", "date_added", "bucket_id",
            "bucket_display_name"};

    private Context context;
    private YaaccContentDirectory directory;
    private MockedStatic<PreferenceManager> preferences;
    private MockedStatic<MimeTypeMap> mimeTypes;
    private SharedPreferences sharedPreferences;

    /** The rows the fake media store answers with, and what it was asked. */
    private Supplier<Cursor> answer;
    private final List<String> selections = new ArrayList<>();
    private final List<List<String>> selectionArgs = new ArrayList<>();
    private final List<String> sortOrders = new ArrayList<>();

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getString(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (id == R.string.videos) return "Videos";
            if (id == R.string.all_videos) return "All videos";
            if (id == R.string.bucket_names) return "Galleries";
            if (id == R.string.gallery_unnamed) return "(root)";
            return "text " + id;
        });
        ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        doAnswer(invocation -> {
            selections.add(invocation.getArgument(2));
            String[] args = invocation.getArgument(3);
            selectionArgs.add(args == null ? new ArrayList<>() : Arrays.asList(args));
            sortOrders.add(invocation.getArgument(4));
            return answer.get();
        }).when(resolver).query(any(), any(), nullable(String.class), any(), nullable(String.class));

        answer = () -> MediaCursors.of(VIDEO_COLUMNS, new Object[0][]);
        directory = mock(YaaccContentDirectory.class);
        when(directory.getContext()).thenReturn(context);
        when(directory.getIpAddress()).thenReturn("10.0.0.1");
        when(directory.formatDuration(any())).thenReturn("0:00:10");

        sharedPreferences = mock(SharedPreferences.class); // the media path filter is off: every path
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenReturn(false);
        preferences = Mockito.mockStatic(PreferenceManager.class);
        preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPreferences);
        MimeTypeMap map = mock(MimeTypeMap.class);
        when(map.getExtensionFromMimeType(any())).thenReturn("mp4");
        mimeTypes = Mockito.mockStatic(MimeTypeMap.class);
        mimeTypes.when(MimeTypeMap::getSingleton).thenReturn(map);
    }

    @After
    public void tearDown() {
        preferences.close();
        mimeTypes.close();
    }

    private static Object[] video(long id, String name, long bucketId, String bucketName) {
        return new Object[]{id, name, "video/mp4", 1000L, 10000L, 1700000000L, bucketId, bucketName};
    }

    private void rows(Object[]... rows) {
        answer = () -> MediaCursors.of(VIDEO_COLUMNS, rows);
    }

    private static List<String> ids(List<? extends DIDLObject> objects) {
        List<String> ids = new ArrayList<>();
        for (DIDLObject object : objects) {
            ids.add(object.getId());
        }
        return ids;
    }

    private static List<String> titles(List<? extends DIDLObject> objects) {
        List<String> titles = new ArrayList<>();
        for (DIDLObject object : objects) {
            titles.add(object.getTitle());
        }
        return titles;
    }

    // ---- the folder "Videos"

    @Test
    public void theFolderOfTheVideosHoldsAllVideosAndTheGalleries() {
        VideosFolderBrowser browser = new VideosFolderBrowser(context);

        List<Container> children = browser.browseContainer(directory, "400999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(2, (int) browser.getSize(directory, "400999"));
        assertEquals(Arrays.asList("430999", "440999"), ids(children));
        assertEquals(Arrays.asList("All videos", "Galleries"), titles(children));
        assertEquals("400999", children.get(0).getParentID());
        assertEquals("400999", children.get(1).getParentID());
        assertTrue(browser.browseItem(directory, "400999", 0, Long.MAX_VALUE, NO_SORT).isEmpty());
        DIDLObject folder = browser.browseMeta(directory, "400999", 0, 1, NO_SORT);
        assertEquals("400999", folder.getId());
        assertEquals("0", folder.getParentID());
        assertEquals(2, ((Container) folder).getChildCount().intValue());
    }

    @Test
    public void theTwoFoldersCanBeSortedAndPaged() {
        VideosFolderBrowser browser = new VideosFolderBrowser(context);

        List<Container> descending = browser.browseContainer(directory, "400999", 0, Long.MAX_VALUE,
                new SortCriterion[]{new SortCriterion(false, "dc:title")});
        List<Container> second = browser.browseContainer(directory, "400999", 1, 1, NO_SORT);

        assertEquals(Arrays.asList("Galleries", "All videos"), titles(descending));
        assertEquals(Arrays.asList("Galleries"), titles(second));
    }

    // ---- all videos

    @Test
    public void allVideosListsEveryVideoWithTheOldItemIdAndTheNewParent() {
        rows(video(11, "a.mp4", 1, "Camera"), video(12, "b.mp4", 2, "CapCut"));
        VideosAllFolderBrowser browser = new VideosAllFolderBrowser(context);

        List<Item> items = browser.browseItem(directory, "430999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(Arrays.asList("41099911", "41099912"), ids(items));
        assertEquals("430999", items.get(0).getParentID());
        assertEquals(Arrays.asList("a.mp4", "b.mp4"), titles(items));
        assertEquals(2, (int) browser.getSize(directory, "430999"));
        assertTrue(selections.get(0).contains("_data like ?"));
        assertEquals("_display_name ASC", sortOrders.get(0));
    }

    @Test
    public void allVideosPagesAndSortsByTheMediaStore() {
        rows(video(1, "a", 1, "x"), video(2, "b", 1, "x"), video(3, "c", 1, "x"), video(4, "d", 1, "x"), video(5, "e", 1, "x"));
        VideosAllFolderBrowser browser = new VideosAllFolderBrowser(context);

        List<Item> page = browser.browseItem(directory, "430999", 1, 2,
                new SortCriterion[]{new SortCriterion(false, "dc:date")});

        assertEquals(Arrays.asList("b", "c"), titles(page));
        assertEquals("date_added DESC", sortOrders.get(0));
    }

    @Test
    public void aVideoItemKnowsItsParentAsAllVideos() throws Exception {
        rows(video(11, "a.mp4", 1, "Camera"));

        DIDLObject item = new VideoItemBrowser(context).browseMeta(directory, "41099911", 0, 1, NO_SORT);

        assertEquals("41099911", item.getId());
        assertEquals("430999", item.getParentID());
        assertEquals("11", selectionArgs.get(0).get(0));
    }

    // ---- the galleries

    @Test
    public void theGalleriesAreOneContainerPerBucketWithTheCountOfItsVideos() {
        rows(video(1, "a", 1, "Camera"), video(2, "b", 1, "Camera"), video(3, "c", 2, "CapCut"), video(4, "d", 3, "Camera"),
                video(5, "e", 4, null));
        VideosByBucketNamesFolderBrowser browser = new VideosByBucketNamesFolderBrowser(context);

        List<Container> galleries = browser.browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(Arrays.asList("(root)", "Camera", "Camera (2)", "CapCut"), titles(galleries));
        assertEquals(Arrays.asList("4509994", "4509991", "4509993", "4509992"), ids(galleries));
        assertEquals("440999", galleries.get(1).getParentID());
        assertEquals(Integer.valueOf(1), galleries.get(0).getChildCount());
        assertEquals(Integer.valueOf(2), galleries.get(1).getChildCount());
        assertEquals(Integer.valueOf(1), galleries.get(2).getChildCount());
        assertEquals(4, (int) browser.getSize(directory, "440999"));
    }

    @Test
    public void aPageOfGalleriesIsCutAfterTheGroupingNotAfterTheRows() {
        // five rows, two galleries: the first page of one gallery must be the first gallery, the second page the second one
        rows(video(1, "a", 1, "Camera"), video(2, "b", 1, "Camera"), video(3, "c", 1, "Camera"), video(4, "d", 2, "Movies"),
                video(5, "e", 2, "Movies"));
        VideosByBucketNamesFolderBrowser browser = new VideosByBucketNamesFolderBrowser(context);

        assertEquals(Arrays.asList("Camera"), titles(browser.browseContainer(directory, "440999", 0, 1, NO_SORT)));
        assertEquals(Arrays.asList("Movies"), titles(browser.browseContainer(directory, "440999", 1, 1, NO_SORT)));
        assertTrue(browser.browseContainer(directory, "440999", 2, 1, NO_SORT).isEmpty());
    }

    @Test
    public void theGalleriesAreSortedByTheirNameInBothDirections() {
        rows(video(1, "a", 1, "bravo"), video(2, "b", 2, "Alpha"), video(3, "c", 3, "charlie"));
        VideosByBucketNamesFolderBrowser browser = new VideosByBucketNamesFolderBrowser(context);

        List<Container> ascending = browser.browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT);
        List<Container> descending = browser.browseContainer(directory, "440999", 0, Long.MAX_VALUE,
                new SortCriterion[]{new SortCriterion(false, "dc:title")});

        assertEquals(Arrays.asList("Alpha", "bravo", "charlie"), titles(ascending));
        assertEquals(Arrays.asList("charlie", "bravo", "Alpha"), titles(descending));
    }

    @Test
    public void noVideosNoGalleries() {
        rows();
        VideosByBucketNamesFolderBrowser browser = new VideosByBucketNamesFolderBrowser(context);

        assertTrue(browser.browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT).isEmpty());
        assertEquals(0, (int) browser.getSize(directory, "440999"));
    }

    @Test
    public void theGalleriesFolderKnowsItsParentAndCount() {
        rows(video(1, "a", 1, "Camera"), video(2, "b", 2, "CapCut"));

        DIDLObject folder = new VideosByBucketNamesFolderBrowser(context).browseMeta(directory, "440999", 0, 1, NO_SORT);

        assertEquals("440999", folder.getId());
        assertEquals("400999", folder.getParentID());
        assertEquals("Galleries", folder.getTitle());
        assertEquals(Integer.valueOf(2), ((Container) folder).getChildCount());
    }

    @Test
    public void theGalleriesAreListedFromTheMediaPaths() {
        rows(video(1, "a", 1, "Camera"));

        new VideosByBucketNamesFolderBrowser(context).browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT);

        assertTrue(selections.get(0).contains("_data like ?"));
        assertEquals(Arrays.asList("%%"), selectionArgs.get(0));
    }

    // ---- one gallery

    @Test
    public void aGalleryListsTheVideosOfItsBucketInTheMediaPaths() {
        rows(video(21, "x.mp4", 7, "Camera"), video(22, "y.mp4", 7, "Camera"));
        VideosByBucketNameFolderBrowser browser = new VideosByBucketNameFolderBrowser(context);

        List<Item> items = browser.browseItem(directory, "4509997", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(Arrays.asList("41099921", "41099922"), ids(items));
        assertEquals("4509997", items.get(0).getParentID());
        assertTrue(selections.get(0).contains("bucket_id=?"));
        assertTrue(selections.get(0).contains("_data like ?"));
        assertTrue(selectionArgs.get(0).contains("7"));
        assertTrue(selectionArgs.get(0).contains("%%"));
    }

    @Test
    public void aGalleryWithANegativeBucketIdWorks() {
        rows(video(21, "x.mp4", -7, "Camera"));
        VideosByBucketNameFolderBrowser browser = new VideosByBucketNameFolderBrowser(context);

        List<Item> items = browser.browseItem(directory, "450999-7", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(1, items.size());
        assertTrue(selectionArgs.get(0).contains("-7"));
    }

    @Test
    public void aGalleryPagesItsVideos() {
        rows(video(1, "a", 7, "Camera"), video(2, "b", 7, "Camera"), video(3, "c", 7, "Camera"));
        VideosByBucketNameFolderBrowser browser = new VideosByBucketNameFolderBrowser(context);

        assertEquals(Arrays.asList("b", "c"), titles(browser.browseItem(directory, "4509997", 1, 5, NO_SORT)));
        assertEquals(Arrays.asList("a"), titles(browser.browseItem(directory, "4509997", 0, 1, NO_SORT)));
    }

    @Test
    public void theMetadataOfAGalleryHasTheNameOfTheBucketAndTheSuffixOfTheList() {
        rows(video(1, "a", 1, "Camera"), video(2, "b", 3, "Camera"), video(3, "c", 3, "Camera"));
        VideosByBucketNameFolderBrowser browser = new VideosByBucketNameFolderBrowser(context);

        Container second = (Container) browser.browseMeta(directory, "4509993", 0, 1, NO_SORT);
        Container first = (Container) browser.browseMeta(directory, "4509991", 0, 1, NO_SORT);

        assertEquals("Camera (2)", second.getTitle());
        assertEquals(Integer.valueOf(2), second.getChildCount());
        assertEquals("440999", second.getParentID());
        assertEquals("Camera", first.getTitle());
        assertEquals("4509993", second.getId());
    }

    @Test
    public void aGalleryThatDoesNotExistHasNoMetadata() {
        rows(video(1, "a", 1, "Camera"));

        assertNull(new VideosByBucketNameFolderBrowser(context).browseMeta(directory, "4509999", 0, 1, NO_SORT));
    }

    // ---- with the media path filter on

    private void filterOn(String... paths) {
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenReturn(true);
        when(sharedPreferences.getStringSet(any(), any())).thenReturn(new java.util.HashSet<>(Arrays.asList(paths)));
    }

    private void assertPathsInSelection(String selection) {
        assertTrue(selection, selection.startsWith("(_data like ? or _data like ? )"));
    }

    @Test
    public void withTheFilterOnEveryListingAsksForTheMediaPathsInParentheses() {
        filterOn("/a", "/b");
        rows(video(1, "a", 7, "Camera"));

        new VideosAllFolderBrowser(context).browseItem(directory, "430999", 0, Long.MAX_VALUE, NO_SORT);
        new VideosAllFolderBrowser(context).getSize(directory, "430999");
        new VideosByBucketNamesFolderBrowser(context).browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(3, selections.size());
        for (int i = 0; i < 3; i++) {
            assertPathsInSelection(selections.get(i));
            assertEquals(2, selectionArgs.get(i).size());
            assertTrue(selectionArgs.get(i).containsAll(Arrays.asList("%/a%", "%/b%")));
        }
    }

    @Test
    public void withTheFilterOnTheBucketIsAnAdditionalConditionOfAllMediaPathsNotOfTheLastOne() {
        filterOn("/a", "/b");
        rows(video(1, "a", 7, "Camera"));
        VideosByBucketNameFolderBrowser browser = new VideosByBucketNameFolderBrowser(context);

        browser.browseItem(directory, "4509997", 0, Long.MAX_VALUE, NO_SORT);
        browser.getSize(directory, "4509997");

        for (int i = 0; i < 2; i++) {
            String selection = selections.get(i);
            assertPathsInSelection(selection);
            // the closing parenthesis of the media paths comes before AND: the bucket is not bound to the last "like" only
            assertTrue(selection, selection.indexOf(')') < selection.indexOf("AND bucket_id=?"));
            List<String> args = selectionArgs.get(i);
            assertEquals(3, args.size());
            assertEquals("7", args.get(2));
            assertTrue(args.subList(0, 2).containsAll(Arrays.asList("%/a%", "%/b%")));
        }
    }

    // ---- odd rows

    @Test
    public void aVideoWithoutMimeTypeOrDurationIsStillListed() {
        rows(new Object[]{1L, "odd.bin", null, 5L, null, 1700000000L, 1L, "Camera"});

        List<Item> items = new VideosAllFolderBrowser(context).browseItem(directory, "430999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(Arrays.asList("odd.bin"), titles(items));
    }

    @Test
    public void aVideoThatDoesNotExistHasNoMetadataAndNoItems() {
        rows();
        VideoItemBrowser browser = new VideoItemBrowser(context);

        assertNull(browser.browseMeta(directory, "4109999", 0, 1, NO_SORT));
        assertTrue(browser.browseItem(directory, "4109999", 0, 1, NO_SORT).isEmpty());
    }

    @Test
    public void theGalleriesAreReadOnceForTheCountThePageAndTheMetadataOfOneBrowse() {
        rows(video(1, "a", 1, "Camera"), video(2, "b", 2, "Movies"));
        VideosByBucketNamesFolderBrowser browser = new VideosByBucketNamesFolderBrowser(context);

        browser.getSize(directory, "440999");
        browser.browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT);

        assertEquals(1, selections.size());
    }

    @Test
    public void galleriesThatDifferOnlyInTheCaseAreInAStableOrder() {
        rows(video(1, "a", 2, "movies"), video(2, "b", 1, "Movies"));
        VideosByBucketNamesFolderBrowser browser = new VideosByBucketNamesFolderBrowser(context);

        List<Container> all = browser.browseContainer(directory, "440999", 0, Long.MAX_VALUE, NO_SORT);
        List<Container> first = new VideosByBucketNamesFolderBrowser(context).browseContainer(directory, "440999", 0, 1, NO_SORT);
        List<Container> second = new VideosByBucketNamesFolderBrowser(context).browseContainer(directory, "440999", 1, 1, NO_SORT);

        assertEquals(Arrays.asList("Movies", "movies"), titles(all)); // upper case first, then by id
        assertEquals(Arrays.asList("Movies"), titles(first));
        assertEquals(Arrays.asList("movies"), titles(second));
    }
}
