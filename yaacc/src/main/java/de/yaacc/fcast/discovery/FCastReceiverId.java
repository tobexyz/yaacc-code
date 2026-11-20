/*
 *
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
package de.yaacc.fcast.discovery;

/**
 * The id of an FCast receiver in the receiver lists: {@code fcast:<endpoint>} with the canonical endpoint text
 * ({@code host:port}, an IPv6 literal in brackets), the same idea as the Snapcast server key (decision D6: the id is the
 * endpoint; a receiver whose address changed is a new receiver). Pure.
 */
public final class FCastReceiverId {
    /** The prefix of every receiver id of an FCast receiver. */
    public static final String PREFIX = "fcast:";
    private static final int MAX_LENGTH = PREFIX.length() + 261;

    private FCastReceiverId() {
    }

    public static String of(FCastEndpoint endpoint) {
        return PREFIX + endpoint;
    }

    /** True for text that starts like an id (it may still be malformed; {@link #parse} decides). */
    public static boolean isFCastId(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** @return the endpoint of a well-formed id (canonical form only, bounded length), or null */
    public static FCastEndpoint parse(String id) {
        if (!isFCastId(id) || id.length() > MAX_LENGTH) {
            return null;
        }
        return FCastEndpoint.parseCanonical(id.substring(PREFIX.length()));
    }
}
