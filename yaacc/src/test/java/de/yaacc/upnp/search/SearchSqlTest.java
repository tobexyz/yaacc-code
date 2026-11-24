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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** What a search becomes in the query of the media store: the SQL, its arguments and what is refused. */
public class SearchSqlTest {

    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList("dc:title", "dc:creator", "upnp:artist", "upnp:album"));

    private static SearchSql.Source music() {
        return new SearchSql.Source("object.item.audioItem.musicTrack")
                .column("dc:title", "title", "display_name").column("upnp:artist", "artist");
    }

    private static SearchSql.Source video() {
        return new SearchSql.Source("object.item.videoItem").column("dc:title", "display_name");
    }

    private static SearchSql.Where build(String criteria, SearchSql.Source source) throws Exception {
        return SearchSql.build(SearchExpression.parse(criteria), source, SUPPORTED);
    }

    @Test
    public void everythingIsNoCondition() throws Exception {
        assertTrue(build("*", music()).isTrue());
    }

    @Test
    public void containsIsALikeOverEveryColumnOfTheProperty() throws Exception {
        SearchSql.Where where = build("dc:title contains \"west\"", music());

        assertEquals("(title LIKE ? ESCAPE '\\' OR display_name LIKE ? ESCAPE '\\')", where.sql);
        assertEquals(Arrays.asList("%west%", "%west%"), where.args);
    }

    @Test
    public void wildcardsOfTheTextAreTakenLiterally() throws Exception {
        SearchSql.Where where = build("dc:title contains \"100%_x\"", video());

        assertEquals(Arrays.asList("%100\\%\\_x%"), where.args);
    }

    @Test
    public void theValueIsNeverPartOfTheSql() throws Exception {
        SearchSql.Where where = build("dc:title = \"x' OR 1=1 --\"", video());

        assertFalse(where.sql, where.sql.contains("1=1"));
        assertEquals(Arrays.asList("x' OR 1=1 --"), where.args);
    }

    @Test
    public void doesNotContainMustHoldForEveryColumn() throws Exception {
        SearchSql.Where where = build("dc:title doesNotContain \"a\"", music());

        assertTrue(where.sql, where.sql.contains(" AND "));
        assertEquals(2, where.args.size());
    }

    @Test
    public void aPropertyOfNoColumnNeverMatchesButItsNegationAlwaysDoes() throws Exception {
        assertTrue(build("upnp:album contains \"x\"", video()).isFalse());
        assertTrue(build("upnp:album doesNotContain \"x\"", video()).isTrue());
    }

    @Test
    public void theClassIsAConstantOfTheSource() throws Exception {
        assertTrue(build("upnp:class derivedfrom \"object.item.audioItem\"", music()).isTrue());
        assertTrue(build("upnp:class derivedfrom \"object.item.audioItem\"", video()).isFalse());
        assertTrue(build("upnp:class = \"object.item.videoItem\"", video()).isTrue());
    }

    @Test
    public void andAndOrCombineAndConstantsFold() throws Exception {
        SearchSql.Where where = build("upnp:class derivedfrom \"object.item.videoItem\" and dc:title contains \"w\"", music());
        assertTrue("the class does not fit: nothing", where.isFalse());

        SearchSql.Where either = build("upnp:class derivedfrom \"object.item.videoItem\" or dc:title contains \"w\"", music());
        assertEquals("only the title is left", Arrays.asList("%w%", "%w%"), either.args);

        SearchSql.Where both = build("dc:title contains \"a\" or upnp:artist contains \"b\"", music());
        assertTrue(both.sql, both.sql.contains(" OR "));
        assertEquals(3, both.args.size());
    }

    @Test
    public void aPropertyOutsideTheCapabilitiesIsRefused() throws Exception {
        try {
            build("dc:date contains \"2020\"", music());
            fail("expected a refusal");
        } catch (SearchExpression.InvalidSearchException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("dc:date"));
        }
    }

    @Test
    public void anOrderingOperatorIsRefused() throws Exception {
        try {
            build("dc:title > \"a\"", music());
            fail("expected a refusal");
        } catch (SearchExpression.InvalidSearchException expected) {
            // fine
        }
    }
}
