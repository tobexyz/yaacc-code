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
package de.yaacc.yxc;

/** Names that come from the network, made fit for one line of text. */
public final class YxcNames {
    /** The longest name shown; a device can send much more. */
    public static final int MAX_NAME_CHARS = 48;

    private YxcNames() {
    }

    /**
     * Control and format characters (line breaks, bidi overrides) are dropped, the length is capped.
     *
     * @return the cleaned text, {@code null} when nothing is left
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() <= MAX_NAME_CHARS; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        String text = out.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_NAME_CHARS ? text.substring(0, MAX_NAME_CHARS) + "\u2026" : text;
    }
}
