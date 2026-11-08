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

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client-side sort of a fully loaded folder (issue #252), kept free of the adapter so it can run on a
 * worker thread: it never touches the list it is given, it returns a sorted copy. No Android classes.
 * <p>
 * The orderings are the former comparators of {@link BrowseContentItemAdapter}, unchanged:
 * <ul>
 *     <li>NAME: containers before items regardless of direction, titles compared ignoring case
 *     (a missing title counts as empty), the comparison reverses with the direction.</li>
 *     <li>DATE: containers and items interleaved by {@code dc:date}; a missing or unparseable date sorts last in
 *     either direction.</li>
 * </ul>
 * Both sorts are stable, like {@link List#sort}.
 */
public final class BrowseSorter {

    private BrowseSorter() {
    }

    /**
     * The sorted copy and what changed against the input.
     */
    public static final class Result {
        /** The sorted copy; the input list is not touched. */
        public final List<DIDLObject> sorted;
        /** First position whose object differs from the input, -1 if the order did not change. */
        public final int firstChanged;
        /** Last position whose object differs from the input, -1 if the order did not change. */
        public final int lastChanged;

        Result(List<DIDLObject> sorted, int firstChanged, int lastChanged) {
            this.sorted = sorted;
            this.firstChanged = firstChanged;
            this.lastChanged = lastChanged;
        }

        public boolean isChanged() {
            return firstChanged >= 0;
        }

        /** Number of positions from the first to the last changed one, 0 if nothing changed. */
        public int changedCount() {
            return firstChanged < 0 ? 0 : lastChanged - firstChanged + 1;
        }
    }

    public static Result sort(List<? extends DIDLObject> input, BrowseContentItemAdapter.SortMode mode,
                              boolean nameAscending, boolean dateAscending) {
        List<DIDLObject> sorted = new ArrayList<>(input);
        if (sorted.size() >= 2) {
            if (mode == BrowseContentItemAdapter.SortMode.DATE) {
                // parse every date once instead of twice per comparison: a folder of thousands of items
                // means ten thousands of comparisons
                Map<DIDLObject, Long> dates = new IdentityHashMap<>(sorted.size());
                for (DIDLObject object : sorted) {
                    dates.put(object, parseDateMillis(object));
                }
                sorted.sort((a, b) -> compareDates(dates.get(a), dates.get(b), dateAscending));
            } else {
                sorted.sort((a, b) -> compareByNameGrouped(a, b, nameAscending));
            }
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != input.get(i)) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        return new Result(sorted, first, last);
    }

    /**
     * Name mode: containers before items regardless of direction (issue #252 Group 4 constraint) -- only the
     * alphabetical comparison within each group reverses with {@code ascending}.
     */
    static int compareByNameGrouped(DIDLObject a, DIDLObject b, boolean ascending) {
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

    /**
     * Date mode: ordered by {@code dc:date} according to {@code ascending} (oldest-first when {@code true},
     * newest-first when {@code false}). Items with a missing or unparseable date sort last regardless of
     * direction, never crashing.
     */
    static int compareDates(Long dateA, Long dateB, boolean ascending) {
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

    public static boolean hasDate(DIDLObject object) {
        return object.getFirstPropertyValue(DIDLObject.Property.DC.DATE.class) != null;
    }

    /**
     * Defensively parses a DIDL {@code dc:date} value (format is server-dependent, not guaranteed to be strict
     * ISO-8601) into epoch millis for sorting. Returns {@code null} rather than throwing when the value is
     * missing or cannot be parsed by any of the attempted formats.
     */
    static Long parseDateMillis(DIDLObject object) {
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
        } catch (DateTimeParseException dateOnlyFailed) {
            // Not a plain date, try a full date-time below.
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
        } catch (DateTimeParseException offsetFailed) {
            // Not an offset date-time either, try a local date-time.
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException localFailed) {
            return null;
        }
    }
}
