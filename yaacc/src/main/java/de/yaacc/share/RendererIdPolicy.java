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
 * Validates the device id that appears in a proxy URL ({@code /proxy/<deviceId>/<key>}) and in
 * the preference key {@code manage_external_seeking_<deviceId>}. The id comes from the URL and
 * is untrusted: it is accepted only if it looks like a UPnP device name (a UUID, optionally
 * with a {@code uuid:} prefix, or the vendor ids seen in practice): 1 to 200 ASCII letters,
 * digits and {@code : . _ -}. Anything else (separators, percent signs, whitespace, control or
 * non-ASCII characters) is refused. Plain Java.
 */
public final class RendererIdPolicy {

    /** The longest accepted id. */
    public static final int MAX_LENGTH = 200;

    private RendererIdPolicy() {
    }

    public static boolean isValid(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ':' || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
