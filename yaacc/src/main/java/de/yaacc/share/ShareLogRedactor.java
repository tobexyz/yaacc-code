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

import java.util.regex.Pattern;

/**
 * Removes share keys from text that is about to be logged. A proxy URL contains the key of the
 * share ({@code /proxy/<key>} or {@code /proxy/<deviceId>/<key>}); everything after
 * {@code /proxy/} up to the next blank, quote or angle bracket is replaced. Plain Java.
 */
public final class ShareLogRedactor {

    private static final Pattern PROXY_PATH = Pattern.compile("/proxy/[^\\s\"'<>]*");

    private ShareLogRedactor() {
    }

    /** @return the text without share keys; null for null */
    public static String redact(String text) {
        if (text == null || text.indexOf("/proxy/") < 0) {
            return text;
        }
        return PROXY_PATH.matcher(text).replaceAll("/proxy/[redacted]");
    }
}
