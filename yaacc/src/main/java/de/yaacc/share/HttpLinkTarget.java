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
package de.yaacc.share;

import java.util.Objects;

/**
 * Target of a {@link ShareRegistry.Kind#HTTP_LINK} entry: an http or https link the proxy relays.
 * The proxy never fetches anything else, so the scheme is checked when the target is built and
 * again where it is fetched ({@link #isFetchable}). Plain Java. {@code toString()} does not show
 * the link (it may carry a token).
 */
public final class HttpLinkTarget {

    /** Longer links are refused. */
    public static final int MAX_LENGTH = 2048;

    private final String url;

    private HttpLinkTarget(String url) {
        this.url = url;
    }

    /**
     * @param url the link as text, may be null
     * @return the target with the scheme in lower case, or null if the text is not an http or
     * https link with a host part, is longer than {@link #MAX_LENGTH}, has leading blanks or
     * contains whitespace or control characters
     */
    public static HttpLinkTarget of(String url) {
        String normalized = normalize(url);
        return normalized == null ? null : new HttpLinkTarget(normalized);
    }

    /** @return true if {@link #of(String)} would accept the text */
    public static boolean isFetchable(String url) {
        return normalize(url) != null;
    }

    private static String normalize(String url) {
        if (url == null || url.length() > MAX_LENGTH) {
            return null;
        }
        String scheme;
        if (startsWithIgnoreCase(url, "http://")) {
            scheme = "http://";
        } else if (startsWithIgnoreCase(url, "https://")) {
            scheme = "https://";
        } else {
            return null;
        }
        // needs at least one character of authority; "http:///x" has none
        if (url.length() == scheme.length() || url.charAt(scheme.length()) == '/') {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                return null;
            }
        }
        return scheme + url.substring(scheme.length());
    }

    private static boolean startsWithIgnoreCase(String text, String lowerCasePrefix) {
        if (text.length() < lowerCasePrefix.length()) {
            return false;
        }
        for (int i = 0; i < lowerCasePrefix.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c != lowerCasePrefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** @return the link, with a lower-case http or https scheme */
    public String getUrl() {
        return url;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HttpLinkTarget && url.equals(((HttpLinkTarget) o).url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    @Override
    public String toString() {
        return "HttpLinkTarget";
    }
}
