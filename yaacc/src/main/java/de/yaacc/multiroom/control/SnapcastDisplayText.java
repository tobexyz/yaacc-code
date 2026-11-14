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
package de.yaacc.multiroom.control;

/**
 * Makes text that came from the network safe to show. Names of servers, groups, clients and streams, and service
 * names from mDNS are written by whoever runs the machine (or by someone who pretends to): control characters, line
 * and paragraph separators, bidi controls, the C1 range, invisible format characters (zero width, soft hyphen, word
 * joiner, the deprecated U+2065 to U+206F), the letters that draw nothing (Hangul fillers, Braille blank, Khmer inherent
 * vowels, combining grapheme joiner), tag characters and unpaired surrogates become {@code ?}, and the text is cut. The result is
 * <b>display text only</b>: never an identity, never a log line.
 */
public final class SnapcastDisplayText {

    private SnapcastDisplayText() {
    }

    /**
     * @param max the longest result in characters
     * @return the cleaned and cut text; the empty string for null
     */
    public static String of(String text, int max) {
        String cleaned = SnapcastJson.displayText(text, Math.max(0, max));
        return cleaned == null ? "" : cleaned;
    }

    /** The most code points of a server's reason that a toast shows. */
    public static final int MAX_TOAST_REASON_CODE_POINTS = 80;

    /**
     * The server's own words, as little of them as makes sense in a toast that looks like the app's: characters of the
     * category "format" and the invisible ones (zero width space and joiners, word joiner and invisible operators, soft
     * hyphen, byte order mark, bidi controls) are removed, and at most {@code maxCodePoints} code points are kept (a pair
     * is never cut). Control characters and line separators must be replaced before (the stream handling and
     * {@link #of} do that). Empty when nothing is left.
     */
    public static String toastReason(String reason, int maxCodePoints) {
        if (reason == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < reason.length() && count < maxCodePoints) {
            int codePoint = reason.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.FORMAT || isInvisibleFormat(codePoint)) {
                continue;
            }
            kept.appendCodePoint(codePoint);
            count++;
        }
        return kept.toString().trim();
    }

    /** The invisible characters that must go even where a platform's character table does not class them as format. */
    private static boolean isInvisibleFormat(int c) {
        return (c >= 0x200B && c <= 0x200D) || (c >= 0x2060 && c <= 0x206F) || c == 0x00AD || c == 0xFEFF
                || SnapcastJson.isBlankLooking(c);
    }
}
