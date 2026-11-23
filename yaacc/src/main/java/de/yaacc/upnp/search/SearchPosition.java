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

/**
 * The place "search results" in the navigation of the folder list (issue #336): a position whose object id is not an id of the
 * server but the search text behind a prefix that the browse code recognises; it is loaded with a search from the root of the
 * server instead of a browse of a folder. Pure Java.
 */
public final class SearchPosition {

    /** Starts the object id of a search position; a server id does not start with it in practice. */
    public static final String PREFIX = "yaacc-search:";

    private SearchPosition() {
    }

    /** @param normalizedText the text, see {@link SearchCriteria#normalized} */
    public static String objectId(String normalizedText) {
        return PREFIX + normalizedText;
    }

    public static boolean isSearch(String objectId) {
        return objectId != null && objectId.startsWith(PREFIX);
    }

    /** @return the search text of a search position, null for any other object id */
    public static String text(String objectId) {
        return isSearch(objectId) ? objectId.substring(PREFIX.length()) : null;
    }
}
