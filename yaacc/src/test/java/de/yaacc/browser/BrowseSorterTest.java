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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The sorted copy of {@link BrowseSorter} against the former in-place sort of the adapter (copied below as the
 * reference), on random data.
 */
public class BrowseSorterTest {

    // ---- the former implementation, unchanged, as reference ----

    private static void referenceSort(List<DIDLObject> objects, BrowseContentItemAdapter.SortMode mode, boolean nameAscending, boolean dateAscending) {
        if (objects.size() < 2) {
            return;
        }
        if (mode == BrowseContentItemAdapter.SortMode.DATE) {
            Map<DIDLObject, Long> dates = new IdentityHashMap<>(objects.size());
            for (DIDLObject object : objects) {
                dates.put(object, referenceParse(object));
            }
            objects.sort((a, b) -> referenceCompareDates(dates.get(a), dates.get(b), dateAscending));
        } else {
            objects.sort((a, b) -> referenceCompareByNameGrouped(a, b, nameAscending));
        }
    }

    private static int referenceCompareByNameGrouped(DIDLObject a, DIDLObject b, boolean ascending) {
        boolean aContainer = a instanceof Container;
        boolean bContainer = b instanceof Container;
        if (aContainer != bContainer) {
            return aContainer ? -1 : 1;
        }
        String titleA = a.getTitle() == null ? "" : a.getTitle();
        String titleB = b.getTitle() == null ? "" : b.getTitle();
        int comparison = titleA.compareToIgnoreCase(titleB);
        return ascending ? comparison : -comparison;
    }

    private static int referenceCompareDates(Long dateA, Long dateB, boolean ascending) {
        if (dateA == null && dateB == null) {
            return 0;
        }
        if (dateA == null) {
            return 1;
        }
        if (dateB == null) {
            return -1;
        }
        return ascending ? dateA.compareTo(dateB) : dateB.compareTo(dateA);
    }

    private static Long referenceParse(DIDLObject object) {
        String value = object.getFirstPropertyValue(DIDLObject.Property.DC.DATE.class);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ---- data ----

    private static final String[] DATE_FORMATS = {null, "", "  ", "garbage", "2020-02-30", "2021-05-17", "2019-12-31T23:59:59",
            "2022-01-01T10:00:00+02:00", "2022-01-01T10:00:00Z", "2022-01-01", " 2023-03-03 "};

    private static List<DIDLObject> randomObjects(Random random, int count) {
        String[] titles = {"apple", "Apple", "APPLE", "banana", "Banana", "cherry", "", null, "Zebra", "zebra", "10", "9", "ä"};
        List<DIDLObject> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String title = titles[random.nextInt(titles.length)];
            DIDLObject object = random.nextBoolean()
                    ? new Container("c" + i, "0", title, "creator", (DIDLObject.Class) null, 0)
                    : new Item("i" + i, "0", title, "creator", (DIDLObject.Class) null);
            String date = random.nextInt(3) == 0
                    ? "20" + (10 + random.nextInt(15)) + "-0" + (1 + random.nextInt(9)) + "-1" + random.nextInt(10)
                    : DATE_FORMATS[random.nextInt(DATE_FORMATS.length)];
            if (date != null) {
                object.addProperty(new DIDLObject.Property.DC.DATE(date));
            }
            result.add(object);
        }
        return result;
    }

    private static void assertSameOrder(List<DIDLObject> expected, List<DIDLObject> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame("position " + i, expected.get(i), actual.get(i));
        }
    }

    @Test
    public void sameOrderAsTheFormerInPlaceSortOnRandomData() {
        Random random = new Random(252);
        for (int round = 0; round < 200; round++) {
            List<DIDLObject> input = randomObjects(random, random.nextInt(60));
            for (BrowseContentItemAdapter.SortMode mode : BrowseContentItemAdapter.SortMode.values()) {
                for (boolean nameAsc : new boolean[]{true, false}) {
                    for (boolean dateAsc : new boolean[]{true, false}) {
                        List<DIDLObject> expected = new ArrayList<>(input);
                        referenceSort(expected, mode, nameAsc, dateAsc);
                        BrowseSorter.Result result = BrowseSorter.sort(input, mode, nameAsc, dateAsc);
                        assertSameOrder(expected, result.sorted);
                    }
                }
            }
        }
    }

    @Test
    public void doesNotTouchTheInputAndReturnsACopy() {
        Random random = new Random(1);
        List<DIDLObject> input = randomObjects(random, 30);
        List<DIDLObject> before = new ArrayList<>(input);
        BrowseSorter.Result result = BrowseSorter.sort(input, BrowseContentItemAdapter.SortMode.NAME, true, false);
        assertSameOrder(before, input);
        assertNotSame(input, result.sorted);
    }

    @Test
    public void reportsTheRangeThatChanged() {
        DIDLObject a = new Item("1", "0", "a", "c", (DIDLObject.Class) null);
        DIDLObject b = new Item("2", "0", "b", "c", (DIDLObject.Class) null);
        DIDLObject c = new Item("3", "0", "c", "c", (DIDLObject.Class) null);
        DIDLObject d = new Item("4", "0", "d", "c", (DIDLObject.Class) null);
        DIDLObject e = new Item("5", "0", "e", "c", (DIDLObject.Class) null);

        BrowseSorter.Result sorted = BrowseSorter.sort(List.of(a, b, c, d, e), BrowseContentItemAdapter.SortMode.NAME, true, false);
        assertFalse(sorted.isChanged());
        assertEquals(0, sorted.changedCount());
        assertEquals(-1, sorted.firstChanged);

        // b and d swapped: positions 1..3 changed, 0 and 4 stay
        BrowseSorter.Result swapped = BrowseSorter.sort(List.of(a, d, c, b, e), BrowseContentItemAdapter.SortMode.NAME, true, false);
        assertTrue(swapped.isChanged());
        assertEquals(1, swapped.firstChanged);
        assertEquals(3, swapped.lastChanged);
        assertEquals(3, swapped.changedCount());
        assertSameOrder(List.of(a, b, c, d, e), swapped.sorted);
    }

    @Test
    public void emptyAndSingleListsAreUnchanged() {
        assertFalse(BrowseSorter.sort(Collections.emptyList(), BrowseContentItemAdapter.SortMode.DATE, true, true).isChanged());
        DIDLObject one = new Item("1", "0", "a", "c", (DIDLObject.Class) null);
        BrowseSorter.Result result = BrowseSorter.sort(List.of(one), BrowseContentItemAdapter.SortMode.NAME, true, true);
        assertFalse(result.isChanged());
        assertEquals(1, result.sorted.size());
    }

    @Test
    public void missingAndUnparseableDatesSortLastInBothDirections() {
        DIDLObject none = new Item("1", "0", "none", "c", (DIDLObject.Class) null);
        DIDLObject bad = new Item("2", "0", "bad", "c", (DIDLObject.Class) null);
        bad.addProperty(new DIDLObject.Property.DC.DATE("not a date"));
        DIDLObject old = new Item("3", "0", "old", "c", (DIDLObject.Class) null);
        old.addProperty(new DIDLObject.Property.DC.DATE("2001-01-01"));
        DIDLObject recent = new Item("4", "0", "recent", "c", (DIDLObject.Class) null);
        recent.addProperty(new DIDLObject.Property.DC.DATE("2024-01-01T10:00:00"));
        List<DIDLObject> input = List.of(none, old, bad, recent);

        assertSameOrder(List.of(recent, old, none, bad), BrowseSorter.sort(input, BrowseContentItemAdapter.SortMode.DATE, true, false).sorted);
        assertSameOrder(List.of(old, recent, none, bad), BrowseSorter.sort(input, BrowseContentItemAdapter.SortMode.DATE, true, true).sorted);
    }
}
