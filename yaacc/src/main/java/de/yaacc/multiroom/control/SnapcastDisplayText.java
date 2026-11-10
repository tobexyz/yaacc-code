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
 * and paragraph separators, bidi controls and the C1 range become {@code ?}, and the text is cut. The result is
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
}
