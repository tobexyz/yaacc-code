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
package de.yaacc.browser;

/**
 * Decides which shared text may become a playable URI. Plain Java so it can be unit tested on
 * the JVM.
 * <p>
 * The text comes from another app and is untrusted. With the proxy option on, a {@code file://}
 * or bare-path URI would be served to the whole LAN through the local {@code /proxy/<hash>}
 * link, so only {@code http} and {@code https} are accepted. The scheme is the text up to the
 * first {@code ':'}, exactly what {@code android.net.Uri#getScheme()} would report, and is
 * compared as ASCII (no locale or Unicode case folding). Nothing is trimmed: leading blanks
 * mean "no valid scheme".
 */
public final class ShareUriPolicy {

    /** Longer texts are dropped, not truncated. */
    public static final int MAX_TEXT_LENGTH = 2048;

    private ShareUriPolicy() {
    }

    /**
     * @param text the shared text, may be null
     * @return true if the text is short enough and starts with an http or https scheme
     */
    public static boolean isAllowed(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            return false;
        }
        int separator = text.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        return schemeEquals(text, separator, "http") || schemeEquals(text, separator, "https");
    }

    private static boolean schemeEquals(String text, int schemeLength, String lowerCaseScheme) {
        if (schemeLength != lowerCaseScheme.length()) {
            return false;
        }
        for (int i = 0; i < schemeLength; i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c != lowerCaseScheme.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
