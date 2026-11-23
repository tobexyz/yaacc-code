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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SearchCriteriaTest {

    private static final List<String> TITLE = Collections.singletonList("dc:title");
    private static final List<String> TITLE_ARTIST = Arrays.asList("dc:title", "upnp:artist");

    @Test
    public void oneWordInOnePropertyNeedsNoBrackets() {
        assertEquals("dc:title contains \"help\"", SearchCriteria.build("help", TITLE));
    }

    @Test
    public void everyWordMustBeFoundInAtLeastOneProperty() {
        assertEquals("(dc:title contains \"beatles\" or upnp:artist contains \"beatles\") and "
                        + "(dc:title contains \"help\" or upnp:artist contains \"help\")",
                SearchCriteria.build("  beatles   help ", TITLE_ARTIST));
    }

    @Test
    public void quotesAndBackslashesInTheTextCanNotChangeTheMeaning() {
        assertEquals("dc:title contains \"say\\\"hi\\\\\"", SearchCriteria.build("say\"hi\\", TITLE));
        // the criteria has exactly the parts the builder wrote: no quote of the text ends the string
        String criteria = SearchCriteria.build("a\" or dc:title contains \"b", TITLE);
        assertTrue(criteria, criteria.startsWith("dc:title contains \"a\\\"\" and dc:title contains \"or\" and "));
    }

    @Test
    public void controlCharactersAreDroppedAndTheTextIsLimited() {
        assertEquals(Arrays.asList("ab", "cd"), SearchCriteria.words("a\u0000b\n\tc\u200bd"));
        assertEquals(SearchCriteria.MAX_WORDS, SearchCriteria.words("1 2 3 4 5 6 7 8").size());
        assertEquals(SearchCriteria.MAX_WORD_CODE_POINTS, SearchCriteria.words(new String(new char[500]).replace('\0', 'x')).get(0).length());
        assertEquals("a b", SearchCriteria.normalized("  a \t b "));
    }

    @Test
    public void nothingToSearchGivesNoCriteria() {
        assertNull(SearchCriteria.build("   ", TITLE));
        assertNull(SearchCriteria.build(null, TITLE));
        assertNull(SearchCriteria.build("x", Collections.<String>emptyList()));
        assertNull(SearchCriteria.build("x", null));
        assertEquals("", SearchCriteria.normalized("\u0000 "));
    }

    @Test
    public void theServersListDecidesWhichPropertiesAreUsed() {
        assertEquals(Arrays.asList("dc:title", "upnp:artist"), SearchCriteria.usableProperties("upnp:class, UPnP:Artist ,dc:title,res@size"));
        assertEquals(SearchCriteria.PROPERTIES, SearchCriteria.usableProperties("*"));
        assertTrue(SearchCriteria.usableProperties("upnp:class,res@size").isEmpty());
        assertTrue(SearchCriteria.usableProperties("").isEmpty());
        assertTrue(SearchCriteria.usableProperties(null).isEmpty());
    }

    @Test
    public void everySpellingOfAWordCanBeLookedFor() {
        assertEquals(Arrays.asList("westen", "WESTEN", "Westen"), SearchCriteria.spellings("westen"));
        assertEquals(Arrays.asList("ImWesten", "imwesten", "IMWESTEN", "Imwesten"), SearchCriteria.spellings("ImWesten"));
        assertEquals("(dc:title contains \"westen\" or dc:title contains \"WESTEN\" or dc:title contains \"Westen\")",
                SearchCriteria.buildAnyCase("westen", TITLE));
        assertEquals("dc:title contains \"1\"", SearchCriteria.buildAnyCase("1", TITLE));
    }

    @Test
    public void aSearchPositionKeepsItsText() {
        String id = SearchPosition.objectId("a b");
        assertTrue(SearchPosition.isSearch(id));
        assertEquals("a b", SearchPosition.text(id));
        assertTrue(!SearchPosition.isSearch("0") && !SearchPosition.isSearch(null));
        assertNull(SearchPosition.text("42"));
    }
}
