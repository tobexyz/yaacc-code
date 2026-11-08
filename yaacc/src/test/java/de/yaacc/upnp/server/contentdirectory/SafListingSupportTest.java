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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.SortCriterion;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Filter, sort and paging of a SAF folder listing, checked against a straightforward reference
 * implementation of what the former DocumentFile based code did. Plain JVM.
 */
public class SafListingSupportTest {

    private static final String[] NAMES = {"a.jpg", "A.jpg", "b.mp3", "B.MP4", "c.png", "x.m3u", "y.M3U", "z.m3u8", "folder", "Folder", "", "readme", null};
    private static final long DAY = 24L * 3600 * 1000;
    private static final long[] TIMES = {0, 1, DAY, DAY + 5000, 3 * DAY, 3 * DAY + 1, 40 * DAY, 400 * DAY};

    private static SafDirectoryEntry file(String name, long lastModified) {
        return new SafDirectoryEntry(name, "id:" + name, "image/jpeg", lastModified, 10, false);
    }

    private static SafDirectoryEntry folder(String name) {
        return SafDirectoryEntry.fromProviderRow(name, "dir:" + name, SafDirectoryEntry.MIME_TYPE_DIR, 0, 0);
    }

    private static List<SafDirectoryEntry> randomFolder(Random random, int size) {
        List<SafDirectoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String name = NAMES[random.nextInt(NAMES.length)];
            long modified = TIMES[random.nextInt(TIMES.length)];
            entries.add(random.nextInt(5) == 0
                    ? SafDirectoryEntry.fromProviderRow(name, "dir:" + i, SafDirectoryEntry.MIME_TYPE_DIR, modified, 0)
                    : new SafDirectoryEntry(name, "id:" + i, "image/jpeg", modified, i, false));
        }
        return entries;
    }

    private static SortCriterion[] randomOrder(Random random) {
        String[] properties = {SortSupport.PROPERTY_TITLE, SortSupport.PROPERTY_DATE, "upnp:artist"};
        int count = random.nextInt(4);
        SortCriterion[] order = new SortCriterion[count];
        for (int i = 0; i < count; i++) {
            order[i] = new SortCriterion(random.nextBoolean(), properties[random.nextInt(properties.length)]);
        }
        return order;
    }

    // ---- reference: what the DocumentFile based SafFolderBrowser did ----

    private static <T> Comparator<T> referenceComparator(SortCriterion[] orderby, Map<String, Function<T, String>> accessors) {
        Comparator<T> stable = (a, b) -> 0;
        if (orderby == null || orderby.length == 0) {
            return stable;
        }
        List<Comparator<T>> comparators = new ArrayList<>();
        for (SortCriterion criterion : orderby) {
            Function<T, String> accessor = accessors.get(criterion.getPropertyName());
            if (accessor == null) {
                continue;
            }
            Comparator<T> comparator = Comparator.comparing(accessor, Comparator.nullsFirst(Comparator.naturalOrder()));
            if (!criterion.isAscending()) {
                comparator = comparator.reversed();
            }
            comparators.add(comparator);
        }
        if (comparators.isEmpty()) {
            return stable;
        }
        Comparator<T> combined = comparators.get(0);
        for (int i = 1; i < comparators.size(); i++) {
            combined = combined.thenComparing(comparators.get(i));
        }
        return combined;
    }

    private static List<SafDirectoryEntry> referenceItems(List<SafDirectoryEntry> children, SortCriterion[] orderby, long first, long max) {
        List<SafDirectoryEntry> candidates = new ArrayList<>();
        for (SafDirectoryEntry child : children) {
            if (!child.isDirectory() && !(child.name != null && child.name.endsWith("m3u"))) {
                candidates.add(child);
            }
        }
        candidates.sort(referenceComparator(orderby, Map.<String, Function<SafDirectoryEntry, String>>of(
                SortSupport.PROPERTY_TITLE, e -> e.name,
                SortSupport.PROPERTY_DATE, e -> e.lastModified > 0 ? SortSupport.formatEpochMillisAsDate(e.lastModified) : null)));
        int start = (int) Math.max(0, first);
        int end = (int) Math.min(candidates.size(), start + max);
        return start >= end ? new ArrayList<>() : new ArrayList<>(candidates.subList(start, end));
    }

    private static List<SafDirectoryEntry> referenceFolders(List<SafDirectoryEntry> children, SortCriterion[] orderby, long first, long max) {
        List<SafDirectoryEntry> folders = new ArrayList<>();
        for (SafDirectoryEntry child : children) {
            if (child.isDirectory()) {
                folders.add(child);
            }
        }
        folders.sort(referenceComparator(orderby, Map.<String, Function<SafDirectoryEntry, String>>of(
                SortSupport.PROPERTY_TITLE, e -> e.name)));
        int start = (int) Math.max(0, first);
        int end = (int) Math.min(folders.size(), start + max);
        return start >= end ? new ArrayList<>() : new ArrayList<>(folders.subList(start, end));
    }

    // ---- tests ----

    @Test
    public void itemsFilteredSortedAndPagedLikeTheReferenceOnRandomFolders() {
        Random random = new Random(42);
        for (int round = 0; round < 300; round++) {
            List<SafDirectoryEntry> children = randomFolder(random, random.nextInt(60));
            SortCriterion[] orderby = randomOrder(random);
            long first = random.nextInt(70) - 2;
            long max = random.nextInt(40);

            List<SafDirectoryEntry> actual = SafListingSupport.page(
                    SafListingSupport.sort(SafListingSupport.itemCandidates(children), orderby,
                            e -> e.name, e -> SafListingSupport.dateKey(e.lastModified)),
                    first, max);

            assertEquals("round " + round, referenceItems(children, orderby, first, max), actual);
        }
    }

    @Test
    public void foldersSortedByTitleAndPagedLikeTheReferenceOnRandomFolders() {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            List<SafDirectoryEntry> children = randomFolder(random, random.nextInt(60));
            SortCriterion[] orderby = randomOrder(random);
            long first = random.nextInt(30) - 1;
            long max = random.nextInt(20);

            List<SafDirectoryEntry> actual = SafListingSupport.page(
                    SafListingSupport.sort(SafListingSupport.directories(children), orderby, e -> e.name, null),
                    first, max);

            assertEquals("round " + round, referenceFolders(children, orderby, first, max), actual);
        }
    }

    @Test
    public void withoutSortCriteriaTheProvidersOrderStays() {
        List<SafDirectoryEntry> children = Arrays.asList(file("c.jpg", 3), file("a.jpg", 1), file("b.jpg", 2));

        List<SafDirectoryEntry> sortedNone = SafListingSupport.sort(children, null,
                e -> e.name, e -> SafListingSupport.dateKey(e.lastModified));
        List<SafDirectoryEntry> sortedUnknown = SafListingSupport.sort(children, new SortCriterion[]{new SortCriterion(true, "upnp:artist")},
                e -> e.name, e -> SafListingSupport.dateKey(e.lastModified));

        assertEquals(children, sortedNone);
        assertEquals(children, sortedUnknown);
    }

    @Test
    public void sortDoesNotChangeItsInput() {
        List<SafDirectoryEntry> children = Collections.unmodifiableList(Arrays.asList(file("b", 2), file("a", 1)));

        List<SafDirectoryEntry> sorted = SafListingSupport.sort(children,
                new SortCriterion[]{new SortCriterion(true, SortSupport.PROPERTY_TITLE)}, e -> e.name, null);

        assertEquals("a", sorted.get(0).name);
        assertEquals("b", children.get(0).name);
    }

    @Test
    public void aDescendingDateSortPutsFilesWithoutDateLast() {
        SafDirectoryEntry old = file("old", DAY);
        SafDirectoryEntry recent = file("recent", 40 * DAY);
        SafDirectoryEntry unknown = file("unknown", 0);

        List<SafDirectoryEntry> sorted = SafListingSupport.sort(Arrays.asList(unknown, old, recent),
                new SortCriterion[]{new SortCriterion(false, SortSupport.PROPERTY_DATE)},
                e -> e.name, e -> SafListingSupport.dateKey(e.lastModified));

        assertEquals(Arrays.asList(recent, old, unknown), sorted);
    }

    @Test
    public void itemCandidatesLeaveOutDirectoriesAndPlaylists() {
        List<SafDirectoryEntry> children = Arrays.asList(
                file("a.jpg", 1), folder("sub"), file("list.m3u", 1), file("list.m3u8", 1), file(null, 1), file("b.mp3", 1));

        List<String> names = new ArrayList<>();
        for (SafDirectoryEntry entry : SafListingSupport.itemCandidates(children)) {
            names.add(entry.name);
        }

        // the check is "ends with m3u", as it always was: m3u8 is kept
        assertEquals(Arrays.asList("a.jpg", "list.m3u8", null, "b.mp3"), names);
    }

    @Test
    public void directoriesAreRecognisedByTheMimeTypeOfTheProviderRow() {
        assertTrue(folder("d").isDirectory());
        assertFalse(SafDirectoryEntry.fromProviderRow("f", "1", "image/jpeg", 0, 0).isDirectory());
        assertFalse(SafDirectoryEntry.fromProviderRow("f", "1", null, 0, 0).isDirectory());
        assertEquals(2, SafListingSupport.directories(Arrays.asList(folder("a"), file("b", 0), folder("c"))).size());
    }

    @Test
    public void pageIsClippedToTheListAndEmptyBehindTheEnd() {
        List<Integer> list = Arrays.asList(0, 1, 2, 3, 4);

        assertEquals(Arrays.asList(1, 2), SafListingSupport.page(list, 1, 2));
        assertEquals(Arrays.asList(3, 4), SafListingSupport.page(list, 3, 100));
        assertEquals(list, SafListingSupport.page(list, -5, 100));
        assertTrue(SafListingSupport.page(list, 5, 10).isEmpty());
        assertTrue(SafListingSupport.page(list, 4_000_000_000L, 10).isEmpty());
        assertTrue(SafListingSupport.page(list, 1, 0).isEmpty());
        assertEquals(list, SafListingSupport.page(list, 0, 4_294_967_295L));
    }

    @Test
    public void pageBoundsForTheLogLine() {
        assertEquals(100, SafListingSupport.pageStart(5000, 100));
        assertEquals(200, SafListingSupport.pageEnd(5000, 100, 100));
        assertEquals(5000, SafListingSupport.pageEnd(5000, 4950, 100));
        assertEquals(5000, SafListingSupport.pageStart(5000, 9000));
        assertEquals(0, SafListingSupport.pageStart(5000, -3));
    }

    @Test
    public void dateKeyIsNullForAnUnknownTimeAndTheUtcDayOtherwise() {
        assertNull(SafListingSupport.dateKey(0));
        assertEquals("1970-01-02", SafListingSupport.dateKey(DAY));
    }

    @Test
    public void sortsByAsksForTheProperty() {
        SortCriterion[] order = {new SortCriterion(true, SortSupport.PROPERTY_DATE)};

        assertTrue(SafListingSupport.sortsBy(order, SortSupport.PROPERTY_DATE));
        assertFalse(SafListingSupport.sortsBy(order, SortSupport.PROPERTY_TITLE));
        assertFalse(SafListingSupport.sortsBy(null, SortSupport.PROPERTY_TITLE));
    }

    @Test
    public void aSafDirectoryIsCacheableOnlyWhenListedAndReadable() {
        List<SafDirectoryEntry> entries = Arrays.asList(file("a", 1), file("b", 2));

        assertTrue(SafDirectory.listed("content://x", true, entries).isCacheable());
        assertFalse(SafDirectory.listed("content://x", false, entries).isCacheable());
        assertFalse(SafDirectory.listingFailed("content://x", true).isCacheable());
        assertFalse(SafDirectory.notADirectory("content://x").isCacheable());
        assertEquals(3, SafDirectory.listed("content://x", true, entries).weight());
    }
}
