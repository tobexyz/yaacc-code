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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The free text search of a UPnP server (issue #336), pure Java. What the user types is split into words; every word must be found
 * (and) in at least one of the properties that the server can search (or): {@code (dc:title contains "a" or upnp:artist contains
 * "a") and (dc:title contains "b" or upnp:artist contains "b")}. The properties come from the server's own list
 * ({@code GetSearchCapabilities}); a server that lists none (or does not have the action) cannot be searched. The text is
 * limited (words, characters) and quotes and backslashes are escaped as the ContentDirectory search syntax asks, so that the text
 * can not change the meaning of the criteria.
 */
public final class SearchCriteria {

    public static final int MAX_WORDS = 5;
    public static final int MAX_WORD_CODE_POINTS = 64;

    /** The properties a free text is looked for in, most useful first; the server's list decides which of them are used. */
    static final List<String> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            "dc:title", "upnp:artist", "upnp:album", "dc:creator", "upnp:genre", "dc:description"));

    private SearchCriteria() {
    }

    /** @return the cleaned words of the text (control characters dropped, at most {@link #MAX_WORDS}, each cut to {@link #MAX_WORD_CODE_POINTS}); empty if there are none */
    public static List<String> words(String freeText) {
        List<String> words = new ArrayList<>();
        if (freeText == null) {
            return words;
        }
        StringBuilder word = new StringBuilder();
        int codePoints = 0;
        for (int i = 0; i < freeText.length() && words.size() < MAX_WORDS; ) {
            int cp = freeText.codePointAt(i);
            i += Character.charCount(cp);
            boolean separator = Character.isWhitespace(cp) || Character.isSpaceChar(cp);
            int type = Character.getType(cp);
            boolean dropped = type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.UNASSIGNED;
            if (separator) {
                if (word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                    codePoints = 0;
                }
            } else if (!dropped && codePoints < MAX_WORD_CODE_POINTS) {
                word.appendCodePoint(cp);
                codePoints++;
            }
        }
        if (word.length() > 0 && words.size() < MAX_WORDS) {
            words.add(word.toString());
        }
        return words;
    }

    /** @return the words of the text joined by one blank: the text as it is searched and shown; empty if there is nothing to search */
    public static String normalized(String freeText) {
        return String.join(" ", words(freeText));
    }

    /**
     * The properties of {@link #PROPERTIES} that the server can search.
     *
     * @param capabilities the answer of {@code GetSearchCapabilities}: comma separated property names, {@code *} for every one; null or empty for none
     * @return the usable properties in the order of {@link #PROPERTIES}; empty if the server can search none of them
     */
    public static List<String> usableProperties(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> offered = new ArrayList<>();
        for (String name : capabilities.split(",")) {
            offered.add(name.trim().toLowerCase(Locale.ROOT));
        }
        if (offered.contains("*")) {
            return PROPERTIES;
        }
        List<String> usable = new ArrayList<>();
        for (String property : PROPERTIES) {
            if (offered.contains(property)) {
                usable.add(property);
            }
        }
        return usable;
    }

    /**
     * @param freeText   what the user typed
     * @param properties the properties to look in, see {@link #usableProperties}
     * @return the {@code SearchCriteria} argument, or null if there are no words or no property
     */
    public static String build(String freeText, List<String> properties) {
        return build(freeText, properties, false);
    }

    /**
     * The same, but every word is also looked for in its other spellings (all lower case, all upper case, first letter big):
     * some servers compare case sensitive, and "westen" would not be found in "ImWestenNichtsNeues". Longer, so it is the last
     * thing tried when a search found nothing.
     */
    public static String buildAnyCase(String freeText, List<String> properties) {
        return build(freeText, properties, true);
    }

    private static String build(String freeText, List<String> properties, boolean anyCase) {
        List<String> words = words(freeText);
        if (words.isEmpty() || properties == null || properties.isEmpty()) {
            return null;
        }
        StringBuilder criteria = new StringBuilder();
        for (String word : words) {
            if (criteria.length() > 0) {
                criteria.append(" and ");
            }
            List<String> spellings = anyCase ? spellings(word) : Collections.singletonList(word);
            boolean group = properties.size() * spellings.size() > 1;
            if (group) {
                criteria.append('(');
            }
            boolean first = true;
            for (String property : properties) {
                for (String spelling : spellings) {
                    if (!first) {
                        criteria.append(" or ");
                    }
                    first = false;
                    criteria.append(property).append(" contains \"").append(escape(spelling)).append('"');
                }
            }
            if (group) {
                criteria.append(')');
            }
        }
        return criteria.toString();
    }

    /** The word as typed, all lower case, all upper case and with a big first letter; each once. */
    static List<String> spellings(String word) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        result.add(word);
        String lower = word.toLowerCase(Locale.ROOT);
        result.add(lower);
        result.add(word.toUpperCase(Locale.ROOT));
        int first = lower.offsetByCodePoints(0, 1);
        result.add(lower.substring(0, first).toUpperCase(Locale.ROOT) + lower.substring(first));
        return new ArrayList<>(result);
    }

    /** The string syntax of the ContentDirectory search: a backslash and a double quote are escaped by a backslash. */
    static String escape(String word) {
        return word.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
