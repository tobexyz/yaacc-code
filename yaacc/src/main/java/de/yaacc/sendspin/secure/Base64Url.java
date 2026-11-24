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
package de.yaacc.sendspin.secure;

import java.util.Base64;

/** Base64url without padding, the encoding of identities, PSK ids, group ids and handshake bytes of the protocol. */
public final class Base64Url {

    private Base64Url() {
    }

    public static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * @return the bytes, or null if the text is not canonical base64url without padding (other characters, padding, a length that
     * no byte count gives, or trailing bits that are not zero)
     */
    public static byte[] decode(String text) {
        if (text == null) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        if (text.length() % 4 == 1) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            // canonical form: encoding the bytes again gives the text (rejects non-zero trailing bits)
            return encode(bytes).equals(text) ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A 32-byte key or id as the protocol writes it: 43 characters. */
    public static byte[] decode32(String text) {
        byte[] bytes = decode(text);
        return bytes != null && bytes.length == 32 ? bytes : null;
    }
}
