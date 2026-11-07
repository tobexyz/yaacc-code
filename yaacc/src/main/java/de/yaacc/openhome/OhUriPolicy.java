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
package de.yaacc.openhome;

import java.net.URI;
import java.util.Locale;

/**
 * Which addresses the renderer accepts as the address of a track: http and https with a host, no user info, no blank or control
 * character. The address comes from any control point in the network, and the renderer will fetch it.
 */
public final class OhUriPolicy {
    private OhUriPolicy() {
    }

    public static boolean isAcceptable(String address) {
        if (address == null || address.isEmpty() || address.length() > OhQueue.MAX_URI_LENGTH) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                return false;
            }
        }
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            String lower = scheme.toLowerCase(Locale.ROOT);
            return (lower.equals("http") || lower.equals("https")) && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }
}
