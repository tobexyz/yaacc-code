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
package de.yaacc.multiroom.protocol;

import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastJson;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** The JSON messages (Hello, ServerSettings, ClientInfo): a u32 length and the text, read through the guarded parser. */
final class JsonBodies {

    private JsonBodies() {
    }

    /** Reads the length-prefixed JSON object; the text goes through the depth and token pre-scan of the control client. */
    static JSONObject read(ByteReader reader) throws SnapcastProtocolException {
        String text = reader.text();
        try {
            return SnapcastJson.parseObject(text);
        } catch (SnapcastException e) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.MALFORMED, "not a JSON object");
        }
    }

    /** The u32 length and the UTF-8 text. */
    static byte[] write(String json) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        if (text.length > SnapcastProtocolLimits.MAX_SMALL_BODY - 4) {
            throw new IllegalArgumentException("JSON message too large");
        }
        ByteBuffer out = ByteBuffer.allocate(4 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(text.length);
        out.put(text);
        return out.array();
    }
}
