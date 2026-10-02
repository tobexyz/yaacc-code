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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.fourthline.cling.support.contentdirectory.ContentDirectoryErrorCode;
import org.fourthline.cling.support.contentdirectory.ContentDirectoryException;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.junit.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class SortSupportTest {

    private static final String DEFAULT_SORT_ORDER = "default_column ASC";
    private static final Comparator<DIDLObject> DEFAULT_COMPARATOR =
            Comparator.comparing(DIDLObject::getTitle);

    // --- validateSupported ---

    @Test
    public void validateSupportedNoOpForNullArray() throws ContentDirectoryException {
        SortSupport.validateSupported(null);
    }

    @Test
    public void validateSupportedNoOpForEmptyArray() throws ContentDirectoryException {
        SortSupport.validateSupported(new SortCriterion[0]);
    }

    @Test
    public void validateSupportedPassesForTitle() throws ContentDirectoryException {
        SortSupport.validateSupported(new SortCriterion[]{
                new SortCriterion(true, SortSupport.PROPERTY_TITLE)});
    }

    @Test
    public void validateSupportedPassesForDate() throws ContentDirectoryException {
        SortSupport.validateSupported(new SortCriterion[]{
                new SortCriterion(false, SortSupport.PROPERTY_DATE)});
    }

    @Test
    public void validateSupportedThrowsForUnsupportedProperty() {
        try {
            SortSupport.validateSupported(new SortCriterion[]{
                    new SortCriterion(true, "upnp:artist")});
            fail("expected ContentDirectoryException");
        } catch (ContentDirectoryException ex) {
            assertEquals(ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA.getCode(),
                    ex.getErrorCode());
        }
    }

    @Test
    public void validateSupportedThrowsIfAnyCriterionUnsupported() {
        try {
            SortSupport.validateSupported(new SortCriterion[]{
                    new SortCriterion(true, SortSupport.PROPERTY_TITLE),
                    new SortCriterion(false, "upnp:genre")});
            fail("expected ContentDirectoryException");
        } catch (ContentDirectoryException ex) {
            assertEquals(ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA.getCode(),
                    ex.getErrorCode());
        }
    }

    @Test
    public void validateSupportedPassesAtMaxCriteria() throws ContentDirectoryException {
        SortSupport.validateSupported(criteria(SortSupport.MAX_SORT_CRITERIA));
    }

    @Test
    public void validateSupportedThrowsAboveMaxCriteria() {
        try {
            SortSupport.validateSupported(criteria(SortSupport.MAX_SORT_CRITERIA + 1));
            fail("expected ContentDirectoryException");
        } catch (ContentDirectoryException ex) {
            assertEquals(ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA.getCode(),
                    ex.getErrorCode());
        }
    }

    /**
     * Builds {@code count} valid criteria (alternating {@code dc:title}/
     * {@code dc:date}) so the length cap, not the property allowlist, is
     * what's under test.
     */
    private static SortCriterion[] criteria(int count) {
        SortCriterion[] result = new SortCriterion[count];
        for (int i = 0; i < count; i++) {
            result[i] = new SortCriterion(true,
                    i % 2 == 0 ? SortSupport.PROPERTY_TITLE : SortSupport.PROPERTY_DATE);
        }
        return result;
    }

    // --- toMediaStoreSortOrder ---

    @Test
    public void toMediaStoreSortOrderReturnsDefaultForNullOrderby() {
        String result = SortSupport.toMediaStoreSortOrder(null, columnMap(), DEFAULT_SORT_ORDER);
        assertEquals(DEFAULT_SORT_ORDER, result);
    }

    @Test
    public void toMediaStoreSortOrderReturnsDefaultForEmptyOrderby() {
        String result = SortSupport.toMediaStoreSortOrder(new SortCriterion[0], columnMap(), DEFAULT_SORT_ORDER);
        assertEquals(DEFAULT_SORT_ORDER, result);
    }

    @Test
    public void toMediaStoreSortOrderBuildsAscendingTitleColumn() {
        SortCriterion[] orderby = {new SortCriterion(true, SortSupport.PROPERTY_TITLE)};
        String result = SortSupport.toMediaStoreSortOrder(orderby, columnMap(), DEFAULT_SORT_ORDER);
        assertEquals("title_column ASC", result);
    }

    @Test
    public void toMediaStoreSortOrderBuildsDescendingDateColumn() {
        SortCriterion[] orderby = {new SortCriterion(false, SortSupport.PROPERTY_DATE)};
        String result = SortSupport.toMediaStoreSortOrder(orderby, columnMap(), DEFAULT_SORT_ORDER);
        assertEquals("date_column DESC", result);
    }

    @Test
    public void toMediaStoreSortOrderBuildsMultiKeyOrder() {
        SortCriterion[] orderby = {
                new SortCriterion(false, SortSupport.PROPERTY_DATE),
                new SortCriterion(true, SortSupport.PROPERTY_TITLE)};
        String result = SortSupport.toMediaStoreSortOrder(orderby, columnMap(), DEFAULT_SORT_ORDER);
        assertEquals("date_column DESC, title_column ASC", result);
    }

    @Test
    public void toMediaStoreSortOrderFallsBackToDefaultForUnmappedProperty() {
        // Only PROPERTY_TITLE mapped - PROPERTY_DATE is requested but unmapped
        // for this (hypothetical title-only) caller.
        Map<String, String> titleOnly = new HashMap<>();
        titleOnly.put(SortSupport.PROPERTY_TITLE, "title_column");
        SortCriterion[] orderby = {new SortCriterion(false, SortSupport.PROPERTY_DATE)};
        String result = SortSupport.toMediaStoreSortOrder(orderby, titleOnly, DEFAULT_SORT_ORDER);
        assertEquals(DEFAULT_SORT_ORDER, result);
    }

    @Test
    public void toMediaStoreSortOrderSkipsUnmappedAndKeepsMappedInMultiKey() {
        Map<String, String> titleOnly = new HashMap<>();
        titleOnly.put(SortSupport.PROPERTY_TITLE, "title_column");
        SortCriterion[] orderby = {
                new SortCriterion(false, SortSupport.PROPERTY_DATE),
                new SortCriterion(true, SortSupport.PROPERTY_TITLE)};
        String result = SortSupport.toMediaStoreSortOrder(orderby, titleOnly, DEFAULT_SORT_ORDER);
        assertEquals("title_column ASC", result);
    }

    // --- toComparator ---

    @Test
    public void toComparatorReturnsDefaultForNullOrderby() {
        Comparator<DIDLObject> result = SortSupport.toComparator(null, accessorMap(), DEFAULT_COMPARATOR);
        assertSame(DEFAULT_COMPARATOR, result);
    }

    @Test
    public void toComparatorReturnsDefaultForEmptyOrderby() {
        Comparator<DIDLObject> result =
                SortSupport.toComparator(new SortCriterion[0], accessorMap(), DEFAULT_COMPARATOR);
        assertSame(DEFAULT_COMPARATOR, result);
    }

    @Test
    public void toComparatorReturnsDefaultWhenNoCriterionMaps() {
        Map<String, Function<DIDLObject, String>> titleOnly = new HashMap<>();
        titleOnly.put(SortSupport.PROPERTY_TITLE, DIDLObject::getTitle);
        SortCriterion[] orderby = {new SortCriterion(true, SortSupport.PROPERTY_DATE)};
        Comparator<DIDLObject> result = SortSupport.toComparator(orderby, titleOnly, DEFAULT_COMPARATOR);
        assertSame(DEFAULT_COMPARATOR, result);
    }

    @Test
    public void toComparatorOrdersAscendingByTitle() {
        SortCriterion[] orderby = {new SortCriterion(true, SortSupport.PROPERTY_TITLE)};
        Comparator<DIDLObject> comparator = SortSupport.toComparator(orderby, accessorMap(), DEFAULT_COMPARATOR);

        TestDidlObject a = new TestDidlObject("Apple", null);
        TestDidlObject b = new TestDidlObject("Banana", null);
        assertTrue(comparator.compare(a, b) < 0);
        assertTrue(comparator.compare(b, a) > 0);
    }

    @Test
    public void toComparatorOrdersDescendingByTitle() {
        SortCriterion[] orderby = {new SortCriterion(false, SortSupport.PROPERTY_TITLE)};
        Comparator<DIDLObject> comparator = SortSupport.toComparator(orderby, accessorMap(), DEFAULT_COMPARATOR);

        TestDidlObject a = new TestDidlObject("Apple", null);
        TestDidlObject b = new TestDidlObject("Banana", null);
        assertTrue(comparator.compare(a, b) > 0);
        assertTrue(comparator.compare(b, a) < 0);
    }

    // --- formatEpochMillisAsDate ---

    @Test
    public void formatEpochMillisAsDateFormatsKnownTimestamp() {
        // 2021-06-15T00:00:00Z
        long millis = Instant.parse("2021-06-15T00:00:00Z").toEpochMilli();
        assertEquals("2021-06-15", SortSupport.formatEpochMillisAsDate(millis));
    }

    @Test
    public void formatEpochMillisAsDateFormatsEpochZero() {
        assertEquals("1970-01-01", SortSupport.formatEpochMillisAsDate(0L));
    }

    private static Map<String, String> columnMap() {
        Map<String, String> map = new HashMap<>();
        map.put(SortSupport.PROPERTY_TITLE, "title_column");
        map.put(SortSupport.PROPERTY_DATE, "date_column");
        return map;
    }

    private static Map<String, Function<DIDLObject, String>> accessorMap() {
        Map<String, Function<DIDLObject, String>> map = new HashMap<>();
        map.put(SortSupport.PROPERTY_TITLE, DIDLObject::getTitle);
        return map;
    }

    /**
     * Minimal DIDLObject subclass for comparator tests - only title is used
     * by the test accessor map above.
     */
    private static class TestDidlObject extends DIDLObject {
        TestDidlObject(String title, String creator) {
            super();
            setTitle(title);
            setCreator(creator);
        }
    }
}
