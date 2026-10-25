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

/**
 * Decides which shared local content may be registered for serving. Plain Java so it can be
 * unit tested on the JVM; http/https text links stay with
 * {@code de.yaacc.browser.ShareUriPolicy}.
 * <p>
 * The input comes from another app and is untrusted. Only {@code content://} URIs of OTHER apps
 * are accepted: a {@code file:} URI or a URI of yaacc's own file provider would let any app make
 * yaacc read and serve its private data. Checks are on the text, ASCII only, nothing is trimmed
 * or decoded, so what is checked is what Android would use.
 */
public final class SharedContentPolicy {

    /** Longer URI texts are dropped, not truncated. */
    public static final int MAX_URI_LENGTH = 2048;

    /** Longest MIME type text that is looked at. */
    public static final int MAX_MIME_LENGTH = 255;

    /** Cap for the copy fallback (provider descriptor is not seekable): 512 MiB. */
    public static final long MAX_COPY_BYTES = 512L * 1024L * 1024L;

    private SharedContentPolicy() {
    }

    /**
     * Accepts a URI only if all of these hold: not null, at most {@link #MAX_URI_LENGTH} chars,
     * only printable ASCII (no whitespace, no control characters, no non-ASCII), scheme
     * {@code content} (ASCII case-insensitive) followed by {@code //}, a non-empty authority
     * (up to the next {@code /}, {@code ?} or {@code #}) without {@code @} (Android user id
     * prefix) and without {@code %} (Android decodes authorities), and an authority that is not
     * {@code ownAuthority} (ASCII case-insensitive).
     *
     * @param uriString    the URI text from the sending app, may be null
     * @param ownAuthority the authority of yaacc's own file provider; null or empty means the
     *                     caller has a bug and nothing is accepted
     */
    public static boolean isAcceptableUri(String uriString, String ownAuthority) {
        // STUB (red phase): wrong on purpose
        return false;
    }

    /**
     * Accepts only concrete audio, video and image types: {@code audio/<subtype>},
     * {@code video/<subtype>}, {@code image/<subtype>} (type ASCII case-insensitive), the
     * subtype a non-empty token of letters, digits and {@code . + - _ ! # $ & ^} that starts
     * with a letter or digit. Wildcards, parameters ({@code ; charset=...}), whitespace, a
     * missing subtype, null, empty and over-long texts are rejected.
     */
    public static boolean isAcceptableMimeType(String mime) {
        // STUB (red phase): wrong on purpose
        return true;
    }

    /** The cap for the copy fallback in bytes, see {@link #MAX_COPY_BYTES}. */
    public static long maxCopyBytes() {
        // STUB (red phase): wrong on purpose
        return 0L;
    }

    /**
     * @param sizeBytes the number of bytes read or announced, negative for unknown
     * @return true if 0 &lt;= size &lt;= {@link #MAX_COPY_BYTES}; unknown sizes are not accepted
     */
    public static boolean isWithinCopyLimit(long sizeBytes) {
        // STUB (red phase): wrong on purpose
        return true;
    }
}
