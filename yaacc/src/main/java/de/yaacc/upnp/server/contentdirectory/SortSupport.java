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

import org.fourthline.cling.support.contentdirectory.ContentDirectoryErrorCode;
import org.fourthline.cling.support.contentdirectory.ContentDirectoryException;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared, Android-framework-free helper for honoring a UPnP {@code Browse}
 * request's {@code SortCriteria} in {@link YaaccContentDirectory} and its
 * content browsers.
 * <p>
 * Pure JDK types only ({@code java.util.*}, {@code java.time.*}) so this
 * class is unit-testable on the plain JVM without Robolectric - see
 * {@code docs/tech.md}'s "server-side sort" section and
 * {@code 2026-09-24-issue252-sort-by-date/decisions.md} for why that
 * matters in this project.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SortSupport {

    /** {@code dc:title} - the only title-like sort property this service supports. */
    public static final String PROPERTY_TITLE = "dc:title";

    /** {@code dc:date} - the only date-like sort property this service supports. */
    public static final String PROPERTY_DATE = "dc:date";

    /**
     * Service-wide supported sort properties, used both to populate
     * {@code SortCaps} and to validate incoming {@code Browse} requests.
     */
    public static final Set<String> SUPPORTED_PROPERTIES =
            new HashSet<>(Arrays.asList(PROPERTY_TITLE, PROPERTY_DATE));

    /**
     * Upper bound on how many comma-separated criteria a single {@code
     * SortCriteria} request string may specify. There are only two
     * supported properties ({@link #PROPERTY_TITLE}/{@link #PROPERTY_DATE}),
     * so anything beyond a handful is necessarily redundant; this guards
     * against an attacker-chosen, arbitrarily long duplicate-criteria chain
     * turning an {@code O(n)} sort into an {@code O(n * MAX_SORT_CRITERIA)}
     * (or, for {@code SafFolderBrowser}'s whole-folder in-memory sort,
     * {@code O(m*log(m)*MAX_SORT_CRITERIA)}) resource-exhaustion vector -
     * see the issue252 security review (Group 2, Cycle 1).
     */
    static final int MAX_SORT_CRITERIA = 8;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private SortSupport() {
    }

    /**
     * Rejects a {@code Browse} request whose {@code SortCriteria} asks for a
     * property this service does not support (anything other than
     * {@link #PROPERTY_TITLE}/{@link #PROPERTY_DATE}).
     *
     * @param orderby the parsed sort criteria; a {@code null} or empty array
     *                is a no-op (no sort requested).
     * @throws ContentDirectoryException with error code
     *                                   {@link ContentDirectoryErrorCode#UNSUPPORTED_SORT_CRITERIA}
     *                                   if any criterion's property isn't supported, or if
     *                                   {@code orderby} specifies more than {@link #MAX_SORT_CRITERIA}
     *                                   criteria.
     */
    public static void validateSupported(SortCriterion[] orderby) throws ContentDirectoryException {
        if (orderby == null) {
            return;
        }
        if (orderby.length > MAX_SORT_CRITERIA) {
            throw new ContentDirectoryException(
                    ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA,
                    "Too many sort criteria: " + orderby.length
                            + " (maximum " + MAX_SORT_CRITERIA + " allowed)");
        }
        for (SortCriterion criterion : orderby) {
            if (!SUPPORTED_PROPERTIES.contains(criterion.getPropertyName())) {
                throw new ContentDirectoryException(
                        ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA,
                        "Unsupported sort property: " + criterion.getPropertyName());
            }
        }
    }

    /**
     * Builds a MediaStore {@code sortOrder} string (e.g.
     * {@code "<col> ASC, <col> DESC"}) from {@code orderby}, mapping
     * {@link #PROPERTY_TITLE}/{@link #PROPERTY_DATE} through
     * {@code propertyToColumn}.
     * <p>
     * A criterion for a property not present in {@code propertyToColumn} for
     * this call is skipped - not an error here; {@link #validateSupported}
     * already rejected anything outside {@code dc:title}/{@code dc:date} at
     * the top of {@code browse()}. A container-only browser simply has no
     * date column to map {@code dc:date} to, and that's a legitimate
     * per-type gap, not a client error.
     *
     * @return {@code defaultSortOrder} unchanged when {@code orderby} is
     * null/empty or every criterion was skipped.
     */
    public static String toMediaStoreSortOrder(
            SortCriterion[] orderby,
            Map<String, String> propertyToColumn,
            String defaultSortOrder) {
        if (orderby == null || orderby.length == 0) {
            return defaultSortOrder;
        }
        StringBuilder sb = new StringBuilder();
        for (SortCriterion criterion : orderby) {
            String column = propertyToColumn.get(criterion.getPropertyName());
            if (column == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(column).append(criterion.isAscending() ? " ASC" : " DESC");
        }
        return sb.length() == 0 ? defaultSortOrder : sb.toString();
    }

    /**
     * Builds a {@link Comparator} for in-memory sorting from {@code orderby},
     * chaining the mapped property-accessor comparators in {@code orderby}'s
     * array order (stable multi-key sort), each direction-aware per
     * {@link SortCriterion#isAscending()}.
     *
     * @return {@code defaultComparator} unchanged when {@code orderby} is
     * null/empty or no criterion maps.
     */
    public static Comparator<DIDLObject> toComparator(
            SortCriterion[] orderby,
            Map<String, Function<DIDLObject, String>> propertyToAccessor,
            Comparator<DIDLObject> defaultComparator) {
        if (orderby == null || orderby.length == 0) {
            return defaultComparator;
        }
        List<Comparator<DIDLObject>> comparators = new ArrayList<>();
        for (SortCriterion criterion : orderby) {
            Function<DIDLObject, String> accessor = propertyToAccessor.get(criterion.getPropertyName());
            if (accessor == null) {
                continue;
            }
            Comparator<DIDLObject> comparator = Comparator.comparing(
                    accessor, Comparator.nullsFirst(Comparator.naturalOrder()));
            if (!criterion.isAscending()) {
                comparator = comparator.reversed();
            }
            comparators.add(comparator);
        }
        if (comparators.isEmpty()) {
            return defaultComparator;
        }
        Comparator<DIDLObject> combined = comparators.get(0);
        for (int i = 1; i < comparators.size(); i++) {
            combined = combined.thenComparing(comparators.get(i));
        }
        return combined;
    }

    /**
     * Formats an epoch-millisecond timestamp as a {@code "yyyy-MM-dd"}
     * {@code dc:date} string, consistent with the defensive-parsing
     * precedent already established client-side in
     * {@code BrowseContentItemAdapter.parseDateMillis()}.
     * <p>
     * This helper only formats millis - converting a seconds-since-epoch
     * value (e.g. {@code MediaStore.Video.Media.DATE_ADDED}) to millis is
     * the caller's responsibility.
     */
    public static String formatEpochMillisAsDate(long millis) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC));
    }
}
