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
package de.yaacc.player;

import de.yaacc.share.DisplayName;

/**
 * The title of a track as a toast shows it. The title is text supplied by a server: it is cleaned with the one cleaner of the
 * app ({@link DisplayName#clean}: line breaks and tabs become a space, control, bidi and invisible characters are dropped) and
 * cut at {@link #MAX_CODE_POINTS} code points (a surrogate pair is never split) with an ellipsis. Never empty: a title with
 * nothing visible, or one that is an address (the app uses the address as the title when a controller sent no metadata), shows as
 * a question mark; the caller may put the position of the track in its place. Pure.
 */
final class TrackToastText {

    /** The most code points of a title in a toast. */
    static final int MAX_CODE_POINTS = 60;

    private static final String ELLIPSIS = "\u2026";
    private static final String UNKNOWN = "?";

    private TrackToastText() {
    }

    /** True for text that is an address (a scheme, {@code ://}, or {@code content:} / {@code file:}), which a toast must never show. */
    static boolean looksLikeAddress(String raw) {
        String cleaned = DisplayName.clean(raw).toLowerCase(java.util.Locale.ROOT);
        return cleaned.contains("://") || cleaned.startsWith("content:") || cleaned.startsWith("file:");
    }

    static String title(String raw) {
        String cleaned = DisplayName.clean(raw);
        if (cleaned.isEmpty() || looksLikeAddress(cleaned)) {
            return UNKNOWN;
        }
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_CODE_POINTS) {
            return cleaned;
        }
        int end = cleaned.offsetByCodePoints(0, MAX_CODE_POINTS);
        return cleaned.substring(0, end).trim() + ELLIPSIS;
    }
}
