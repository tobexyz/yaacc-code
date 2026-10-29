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

import org.fourthline.cling.support.model.SortCriterion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The filter, sort and paging logic for SAF folder listings, on the cheap {@link SafDirectoryEntry}
 * data and free of Android types (plain JVM testable). It yields the same results the former
 * per-file {@code DocumentFile} code did; only the sort keys are computed once per entry instead of
 * on every comparison.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafListingSupport {

    private SafListingSupport() {
    }

    /** Playlists are never served as items (they never occupy a candidate slot, so they cannot shift paging). */
    public static boolean isPlaylist(String name) {
        return name != null && name.endsWith("m3u");
    }

    public static List<SafDirectoryEntry> directories(List<SafDirectoryEntry> entries) {
        List<SafDirectoryEntry> result = new ArrayList<>();
        for (SafDirectoryEntry entry : entries) {
            if (entry.isDirectory()) {
                result.add(entry);
            }
        }
        return result;
    }

    /** The files that can become items: no directories, no playlists. Keeps the provider's order. */
    public static List<SafDirectoryEntry> itemCandidates(List<SafDirectoryEntry> entries) {
        List<SafDirectoryEntry> result = new ArrayList<>();
        for (SafDirectoryEntry entry : entries) {
            if (!entry.isDirectory() && !isPlaylist(entry.name)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** The {@code dc:date} sort key of a last-modified value; null (sorted first) if it is unknown. */
    public static String dateKey(long lastModified) {
        return lastModified > 0 ? SortSupport.formatEpochMillisAsDate(lastModified) : null;
    }

    /** @return true if {@code orderby} asks for the given property */
    public static boolean sortsBy(SortCriterion[] orderby, String property) {
        if (orderby != null) {
            for (SortCriterion criterion : orderby) {
                if (property.equals(criterion.getPropertyName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sorts a copy of {@code items} by {@code orderby}, exactly like chaining one
     * {@code Comparator.comparing(accessor, nullsFirst(naturalOrder()))} per criterion (reversed for a
     * descending one) over a stable sort: a criterion whose property has no key function (a null
     * function, or a property other than title/date) is skipped, and without any usable criterion the
     * provider's order stays. The keys are read once per item.
     *
     * @param titleKey the {@code dc:title} key, or null if the items have no title sort
     * @param dateKey  the {@code dc:date} key, or null if the items have no date sort
     */
    public static <T> List<T> sort(List<T> items, SortCriterion[] orderby,
                                   Function<T, String> titleKey, Function<T, String> dateKey) {
        List<T> result = new ArrayList<>(items);
        if (orderby == null || orderby.length == 0) {
            return result;
        }
        List<Function<T, String>> keyFunctions = new ArrayList<>();
        List<Boolean> ascending = new ArrayList<>();
        for (SortCriterion criterion : orderby) {
            Function<T, String> keyFunction = null;
            if (SortSupport.PROPERTY_TITLE.equals(criterion.getPropertyName())) {
                keyFunction = titleKey;
            } else if (SortSupport.PROPERTY_DATE.equals(criterion.getPropertyName())) {
                keyFunction = dateKey;
            }
            if (keyFunction != null) {
                keyFunctions.add(keyFunction);
                ascending.add(criterion.isAscending());
            }
        }
        if (keyFunctions.isEmpty()) {
            return result;
        }
        int criteria = keyFunctions.size();
        List<Keyed<T>> keyed = new ArrayList<>(result.size());
        for (T item : result) {
            String[] keys = new String[criteria];
            for (int i = 0; i < criteria; i++) {
                keys[i] = keyFunctions.get(i).apply(item);
            }
            keyed.add(new Keyed<>(item, keys));
        }
        keyed.sort((a, b) -> {
            for (int i = 0; i < criteria; i++) {
                int comparison = ascending.get(i)
                        ? compareNullsFirst(a.keys[i], b.keys[i])
                        : compareNullsFirst(b.keys[i], a.keys[i]);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        });
        result.clear();
        for (Keyed<T> entry : keyed) {
            result.add(entry.item);
        }
        return result;
    }

    /**
     * The page {@code [firstResult, firstResult + maxResults)} of {@code items}, clipped to the list;
     * empty (not an error) if it starts behind the end or {@code maxResults} is 0.
     */
    public static <T> List<T> page(List<T> items, long firstResult, long maxResults) {
        long start = Math.max(0L, firstResult);
        if (start >= items.size() || maxResults <= 0) {
            return new ArrayList<>();
        }
        long end = Math.min((long) items.size(), start + maxResults);
        return new ArrayList<>(items.subList((int) start, (int) end));
    }

    /** The clipped start of the page {@link #page} returns, for logging. */
    public static int pageStart(int size, long firstResult) {
        return (int) Math.min((long) size, Math.max(0L, firstResult));
    }

    /** The clipped end of the page {@link #page} returns, for logging. */
    public static int pageEnd(int size, long firstResult, long maxResults) {
        long start = Math.max(0L, firstResult);
        return (int) Math.min((long) size, start + Math.max(0L, maxResults));
    }

    private static int compareNullsFirst(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static final class Keyed<T> {
        final T item;
        final String[] keys;

        Keyed(T item, String[] keys) {
            this.item = item;
            this.keys = keys;
        }
    }
}
