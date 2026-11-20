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
package de.yaacc.fcast.protocol;

/**
 * Text and number rules for what a receiver sends (everything from the network is untrusted): control characters are
 * removed, long text is cut by code points, numbers are made finite and clamped, and a body nested too deep is refused before
 * the JSON parser sees it (a recursive parser can overflow its stack on 16000 open brackets, which fit into a packet). Pure.
 */
public final class FCastText {
    /** Names of receivers and apps. */
    public static final int MAX_NAME = 60;
    /** Error texts of a receiver. */
    public static final int MAX_ERROR = 200;
    /** URLs and MIME types from a receiver (shown, never fetched). */
    public static final int MAX_URL = 2048;
    /** The deepest nesting of arrays and objects in a body that is parsed (a message of protocol v3 has 3). */
    public static final int MAX_DEPTH = 16;

    private FCastText() {
    }

    /**
     * @return the text without control characters (every character below 0x20, 0x7f to 0x9f, and the line and
     * paragraph separators) and without lone surrogates, trimmed and cut to at most {@code maxCodePoints} code points; null
     * stays null
     */
    public static String clean(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxCodePoints * 2));
        int count = 0;
        for (int i = 0; i < text.length() && count < maxCodePoints; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) || cp == 0x2028 || cp == 0x2029
                    || (cp >= 0xd800 && cp <= 0xdfff)) {
                continue;
            }
            out.appendCodePoint(cp);
            count++;
        }
        return out.toString().trim();
    }

    /** @return the value, or {@code min} / {@code max} if outside; NaN becomes {@code min} */
    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** @return true if the body has more than {@link #MAX_DEPTH} levels of arrays and objects (brackets in strings do not count) */
    public static boolean isNestedTooDeep(String body) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) {
                    return true;
                }
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return false;
    }
}
