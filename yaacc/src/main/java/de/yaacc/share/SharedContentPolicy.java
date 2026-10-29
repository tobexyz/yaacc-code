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

import java.util.Locale;

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

    private static final String CONTENT_PREFIX = "content://";

    private static final String SUBTYPE_SPECIALS = ".+-_!#$&^";

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
        if (uriString == null || ownAuthority == null || ownAuthority.isEmpty()
                || uriString.length() > MAX_URI_LENGTH) {
            return false;
        }
        for (int i = 0; i < uriString.length(); i++) {
            if (!isPrintableAscii(uriString.charAt(i))) {
                return false;
            }
        }
        if (!uriString.regionMatches(true, 0, CONTENT_PREFIX, 0, CONTENT_PREFIX.length())) {
            return false;
        }
        int authorityStart = CONTENT_PREFIX.length();
        int authorityEnd = authorityStart;
        while (authorityEnd < uriString.length()) {
            char c = uriString.charAt(authorityEnd);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            authorityEnd++;
        }
        if (authorityEnd == authorityStart) {
            return false;
        }
        String authority = uriString.substring(authorityStart, authorityEnd);
        if (authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0) {
            return false;
        }
        return !authority.equalsIgnoreCase(ownAuthority);
    }

    /**
     * The authority of a URI text that is well-formed in the sense of {@link #isAcceptableUri}
     * (the own-authority rule aside), for the checks that need the Android package manager (does
     * a provider of this app own it?).
     *
     * @return the authority as written (not lower-cased), or null if the text is not acceptable
     */
    public static String authorityOf(String uriString) {
        // a blank own authority can never match an accepted authority: only the syntax is judged
        if (!isAcceptableUri(uriString, " ")) {
            return null;
        }
        int start = CONTENT_PREFIX.length();
        int end = start;
        while (end < uriString.length()) {
            char c = uriString.charAt(end);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            end++;
        }
        return uriString.substring(start, end);
    }

    /**
     * Accepts only concrete audio, video and image types: {@code audio/<subtype>},
     * {@code video/<subtype>}, {@code image/<subtype>} (type ASCII case-insensitive), the
     * subtype a non-empty token of letters, digits and {@code . + - _ ! # $ & ^} that starts
     * with a letter or digit. Wildcards, parameters ({@code ; charset=...}), whitespace, a
     * missing subtype, null, empty and over-long texts are rejected.
     */
    public static boolean isAcceptableMimeType(String mime) {
        if (mime == null || mime.length() > MAX_MIME_LENGTH) {
            return false;
        }
        int slash = mime.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String type = mime.substring(0, slash);
        if (!type.equalsIgnoreCase("audio") && !type.equalsIgnoreCase("video")
                && !type.equalsIgnoreCase("image")) {
            return false;
        }
        int subtypeLength = mime.length() - slash - 1;
        if (subtypeLength == 0) {
            return false;
        }
        for (int i = slash + 1; i < mime.length(); i++) {
            char c = mime.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (i == slash + 1 && !alnum) {
                return false;
            }
            if (!alnum && SUBTYPE_SPECIALS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** The cap for the copy fallback in bytes, see {@link #MAX_COPY_BYTES}. */
    public static long maxCopyBytes() {
        return MAX_COPY_BYTES;
    }

    /**
     * @param sizeBytes the number of bytes read or announced, negative for unknown
     * @return true if 0 &lt;= size &lt;= {@link #MAX_COPY_BYTES}; unknown sizes are not accepted
     */
    public static boolean isWithinCopyLimit(long sizeBytes) {
        return sizeBytes >= 0L && sizeBytes <= MAX_COPY_BYTES;
    }

    /**
     * Picks the MIME type of a shared file. The provider's answer ({@code ContentResolver.getType},
     * untrusted but about the actual content) wins; the type declared on the intent is only a
     * fallback:
     * <ol>
     * <li>{@code providerType} if {@link #isAcceptableMimeType(String)} accepts it;</li>
     * <li>else {@code intentType} if it is accepted too (so a wildcard such as {@code audio/*},
     * {@code *}{@code /*} or a non-media type never wins);</li>
     * <li>else null (reject the share).</li>
     * </ol>
     * The result is the lower-case canonical form (ASCII, {@code Locale.ROOT}) of the chosen
     * input, so callers compare and store one spelling. Nothing is trimmed or stripped: a type
     * with whitespace or parameters is not accepted (see {@link #isAcceptableMimeType(String)}),
     * and the provider type {@code application/octet-stream} falls through to the intent type.
     * Never throws; null and empty inputs count as "not acceptable".
     *
     * @return the chosen type in lower case, or null if neither input is acceptable
     */
    public static String chooseMimeType(String providerType, String intentType) {
        if (isAcceptableMimeType(providerType)) {
            return providerType.toLowerCase(Locale.ROOT);
        }
        if (isAcceptableMimeType(intentType)) {
            return intentType.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean isPrintableAscii(char c) {
        return c >= 0x21 && c <= 0x7e;
    }
}
