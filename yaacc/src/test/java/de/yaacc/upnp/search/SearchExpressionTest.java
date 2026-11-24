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
package de.yaacc.upnp.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.yaacc.upnp.search.SearchExpression.InvalidSearchException;

public class SearchExpressionTest {

    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "dc:title", "dc:creator", "upnp:artist", "upnp:album", "upnp:genre", "upnp:class"));

    private static final SearchSql.Source MUSIC = new SearchSql.Source("object.item.audioItem.musicTrack")
            .column("dc:title", "TITLE", "DISPLAY_NAME").column("upnp:artist", "ARTIST").column("dc:creator", "ARTIST")
            .column("upnp:album", "ALBUM");
    private static final SearchSql.Source VIDEO = new SearchSql.Source("object.item.videoItem").column("dc:title", "DISPLAY_NAME");

    private static SearchSql.Where where(String criteria, SearchSql.Source source) throws Exception {
        return SearchSql.build(SearchExpression.parse(criteria), source, SUPPORTED);
    }

    private static void refused(String criteria) {
        try {
            SearchSql.build(SearchExpression.parse(criteria), MUSIC, SUPPORTED);
            fail("must be refused: " + criteria);
        } catch (InvalidSearchException expected) {
            // refused
        }
    }

    @Test
    public void anAsteriskIsEverything() throws Exception {
        assertSame(SearchExpression.ALL, SearchExpression.parse(" * "));
        assertTrue(where("*", MUSIC).isTrue());
    }

    @Test
    public void aContainsOnAPropertyWithTwoColumnsLooksInBoth() throws Exception {
        SearchSql.Where w = where("dc:title contains \"westen\"", MUSIC);
        assertEquals("(TITLE LIKE ? ESCAPE '\\' OR DISPLAY_NAME LIKE ? ESCAPE '\\')", w.sql);
        assertEquals(Arrays.asList("%westen%", "%westen%"), w.args);
    }

    @Test
    public void theQueryOfTheClientOfTheAppParses() throws Exception {
        String criteria = "(dc:title contains \"beatles\" or upnp:artist contains \"beatles\") and "
                + "(dc:title contains \"help\" or upnp:artist contains \"help\")";
        SearchSql.Where w = where(criteria, MUSIC);
        assertEquals(6, w.args.size());
        assertEquals("%beatles%", w.args.get(0));
        assertEquals("%help%", w.args.get(5));
        // a video has no artist: only its title is looked at
        SearchSql.Where video = where(criteria, VIDEO);
        assertEquals("(DISPLAY_NAME LIKE ? ESCAPE '\\' AND DISPLAY_NAME LIKE ? ESCAPE '\\')", video.sql);
    }

    @Test
    public void andBindsStrongerThanOr() throws Exception {
        SearchSql.Where w = where("dc:title contains \"a\" or dc:title contains \"b\" and upnp:artist contains \"c\"", MUSIC);
        assertTrue(w.sql, w.sql.contains(" OR ("));
        assertEquals(5, w.args.size()); // a: 2 columns, b: 2 columns, c: the artist
    }

    @Test
    public void valuesAreBoundNeverPartOfTheSql() throws Exception {
        // the text of the search: x" or 1=1 --  (the quote is escaped by a backslash)
        SearchSql.Where w = where("dc:title contains \"x\\\" or 1=1 --\"", VIDEO);
        assertEquals("DISPLAY_NAME LIKE ? ESCAPE '\\'", w.sql);
        assertEquals(Collections.singletonList("%x\" or 1=1 --%"), w.args);
        // 100%_\ in the text: the characters of a LIKE pattern are taken literally
        SearchSql.Where like = where("dc:title contains \"100%_\\\\\"", VIDEO);
        assertEquals(Collections.singletonList("%100\\%\\_\\\\%"), like.args);
    }

    @Test
    public void theClassIsAConstantOfTheSource() throws Exception {
        String audio = "upnp:class derivedfrom \"object.item.audioItem\" and dc:title contains \"x\"";
        assertFalse(where(audio, MUSIC).isFalse());
        assertTrue(where(audio, VIDEO).isFalse());
        assertTrue(where("upnp:class derivedfrom \"object.item\"", VIDEO).isTrue());
        assertTrue(where("upnp:class derivedfrom \"object.container\"", VIDEO).isFalse());
        assertTrue(where("upnp:class = \"object.item.videoItem\"", VIDEO).isTrue());
        assertTrue(where("upnp:class derivedfrom \"object.item.video\"", VIDEO).isFalse());
    }

    @Test
    public void aPropertyTheItemsDoNotHaveMatchesNothingOrEverythingForTheNegatives() throws Exception {
        assertTrue(where("upnp:artist contains \"x\"", VIDEO).isFalse());
        assertTrue(where("upnp:artist doesNotContain \"x\"", VIDEO).isTrue());
        assertTrue(where("upnp:artist exists true", VIDEO).isFalse());
        assertTrue(where("upnp:artist exists false", VIDEO).isTrue());
    }

    @Test
    public void otherOperatorsAndExists() throws Exception {
        assertEquals("ARTIST = ?", where("upnp:artist = \"Abba\"", MUSIC).sql);
        assertEquals("(ARTIST IS NULL OR ARTIST != ?)", where("upnp:artist != \"Abba\"", MUSIC).sql);
        assertEquals("(ALBUM IS NOT NULL AND ALBUM != '')", where("upnp:album exists true", MUSIC).sql);
        assertEquals("(ALBUM IS NULL OR ALBUM NOT LIKE ? ESCAPE '\\')", where("upnp:album doesNotContain \"x\"", MUSIC).sql);
    }

    @Test
    public void whatThisServerDoesNotDoIsRefused() {
        refused("dc:date > \"2020\"");
        refused("res@size > \"5\"");
        refused("dc:title < \"m\"");
        refused("dc:title derivedfrom \"x\"");
        refused("upnp:class < \"x\"");
    }

    @Test
    public void malformedTextIsRefused() {
        refused("");
        refused("   ");
        refused("dc:title contains");
        refused("dc:title contains westen");
        refused("dc:title contains \"westen");
        refused("dc:title like \"x\"");
        refused("(dc:title contains \"x\"");
        refused("dc:title contains \"x\")");
        refused("dc:title contains \"x\" and");
        refused("dc:title contains \"x\" dc:title contains \"y\"");
        refused("upnp:artist exists maybe");
        refused("dc:title contains \"a\\");
    }

    @Test
    public void hugeOrDeepTextIsRefusedBeforeAnythingIsBuilt() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < SearchExpression.MAX_DEPTH + 5; i++) {
            deep.append('(');
        }
        deep.append("dc:title contains \"x\"");
        for (int i = 0; i < SearchExpression.MAX_DEPTH + 5; i++) {
            deep.append(')');
        }
        refused(deep.toString());
        StringBuilder many = new StringBuilder("dc:title contains \"x\"");
        for (int i = 0; i < SearchExpression.MAX_RELATIONS + 1; i++) {
            many.append(" or dc:title contains \"x\"");
        }
        refused(many.toString());
        refused(new String(new char[SearchExpression.MAX_LENGTH + 1]).replace('\0', 'a'));
    }

    @Test
    public void everyPageGetsItsPartOfTheSources() {
        long[] counts = {5, 0, 3};
        assertEquals("[[0, 5], [0, 0], [0, 3]]", Arrays.deepToString(SearchPaging.slices(counts, 0, 0)));
        assertEquals("[[0, 4], [0, 0], [0, 0]]", Arrays.deepToString(SearchPaging.slices(counts, 0, 4)));
        assertEquals("[[4, 1], [0, 0], [0, 3]]", Arrays.deepToString(SearchPaging.slices(counts, 4, 4)));
        assertEquals("[[5, 0], [0, 0], [2, 1]]", Arrays.deepToString(SearchPaging.slices(counts, 7, 50)));
        assertEquals("[[5, 0], [0, 0], [3, 0]]", Arrays.deepToString(SearchPaging.slices(counts, 100, 50)));
        assertEquals(8, SearchPaging.total(counts));
    }
}
