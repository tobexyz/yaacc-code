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
package de.yaacc.sendspin.session;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A JSON message of the protocol: {@code {"type": ..., "payload": {...}}}. Parsing is guarded against hostile input before the
 * platform parser sees it: a byte cap, valid UTF-8, a nesting cap counted by a scan that follows the rule of the platform parser
 * for strings (so the scan and the parser agree where a string ends), and a check of the shape.
 */
public final class Envelope {

    /** The deepest nesting a message may have (the spec caps {@code client/hello} at 10 levels, the root being level 1). */
    public static final int MAX_DEPTH = 10;
    /** The most bytes of a JSON message this implementation reads ({@code client/hello} is capped at 16384 by the spec). */
    public static final int MAX_BYTES = 16384;

    private final String type;
    private final JSONObject payload;

    private Envelope(String type, JSONObject payload) {
        this.type = type;
        this.payload = payload;
    }

    public String type() {
        return type;
    }

    public JSONObject payload() {
        return payload;
    }

    /** @throws ProtocolException if the bytes are not UTF-8 JSON of the shape, too big or too deep */
    public static Envelope parse(byte[] utf8) throws ProtocolException {
        return parse(utf8, MAX_BYTES);
    }

    public static Envelope parse(byte[] utf8, int maxBytes) throws ProtocolException {
        if (utf8.length > maxBytes) {
            throw new ProtocolException("message too big");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("message is not UTF-8");
        }
        if (maxDepth(text) > MAX_DEPTH) {
            throw new ProtocolException("message nested too deep");
        }
        try {
            JSONObject root = new JSONObject(text);
            Object type = root.opt("type");
            Object payload = root.opt("payload");
            if (!(type instanceof String) || !(payload instanceof JSONObject)) {
                throw new ProtocolException("not a message");
            }
            return new Envelope((String) type, (JSONObject) payload);
        } catch (JSONException e) {
            throw new ProtocolException("not JSON");
        }
    }

    /**
     * The deepest nesting of objects and arrays, by a scan that tracks strings as the platform parser does: a quote opens a string
     * only where a value or a name may start (after {@code {}, {@code [}, {@code ,} or {@code :}, ignoring white space), and a
     * backslash inside a string skips the next character. A text that ends inside a string counts as bad (a huge depth).
     */
    static int maxDepth(String text) {
        int depth = 0;
        int max = 0;
        boolean inString = false;
        boolean valueMayStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                    valueMayStart = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    if (valueMayStart) {
                        inString = true;
                    }
                    break;
                case '{':
                case '[':
                    depth++;
                    max = Math.max(max, depth);
                    valueMayStart = true;
                    break;
                case '}':
                case ']':
                    depth--;
                    valueMayStart = false;
                    break;
                case ',':
                case ':':
                    valueMayStart = true;
                    break;
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    break;
                default:
                    valueMayStart = false;
                    break;
            }
        }
        return inString ? Integer.MAX_VALUE : max;
    }
}
