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
package de.yaacc.sendspin.discovery;

import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.hub.Endpoint;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The TXT record of a Sendspin player's announcement: {@code path} (the WebSocket endpoint; the specification makes it required,
 * recommended {@code /sendspin}) and {@code name} (display text, optional). Announcements are not authenticated, so both are
 * hints that are checked: the path must match the {@code path-absolute} rule of RFC 3986 (the specification tells a server to
 * connect only then; a path that does not is refused, a missing one is read as the default), the name is cleaned display text.
 * Pure.
 */
public final class SendspinServiceTxt {

    public static final int MAX_PATH = 100;

    private final String path;
    private final String name;
    private final String yaacc;

    private SendspinServiceTxt(String path, String name, String yaacc) {
        this.path = path;
        this.name = name;
        this.yaacc = yaacc;
    }

    /**
     * The {@code yaacc} id of the phone that announced this (the key id of its player), or null: a YAACC phone puts the same id in the
     * records of its player and of its server, which is how the two are shown as one card. Not authenticated: only used to join two
     * announcements, never to trust anything.
     */
    public String yaacc() {
        return yaacc;
    }

    public String path() {
        return path;
    }

    /** The announced name, cleaned, or the empty text. */
    public String name() {
        return name;
    }

    /**
     * @param attributes the TXT attributes as the platform gives them (values are bytes), may be null
     * @return the record, or null if the {@code path} is present and not acceptable (the service is not to be used)
     */
    public static SendspinServiceTxt parse(Map<String, byte[]> attributes) {
        String path = Endpoint.DEFAULT_PATH;
        String name = "";
        String yaacc = null;
        if (attributes != null) {
            byte[] rawPath = find(attributes, "path");
            if (rawPath != null) {
                String text = decode(rawPath);
                if (text == null || !isPathAbsolute(text)) {
                    return null;
                }
                path = text;
            }
            byte[] rawName = find(attributes, "name");
            if (rawName != null) {
                String text = decode(rawName);
                name = text == null ? "" : SendspinRowText.clean(text);
            }
            byte[] rawId = find(attributes, "yaacc");
            if (rawId != null) {
                String text = decode(rawId);
                yaacc = text != null && text.matches("[A-Za-z0-9_-]{1,64}") ? text : null;
            }
        }
        return new SendspinServiceTxt(path, name, yaacc);
    }

    private static byte[] find(Map<String, byte[]> attributes, String key) {
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length > 512) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * RFC 3986 {@code path-absolute}: a slash, not followed by a second slash, then segments of {@code pchar} (unreserved,
     * percent-encoded, sub-delimiters, colon, at sign) separated by slashes. No query, no fragment, no space.
     */
    static boolean isPathAbsolute(String text) {
        if (text.isEmpty() || text.length() > MAX_PATH || text.charAt(0) != '/' || text.startsWith("//")) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0;
            boolean subDelim = "!$&'()*+,;=".indexOf(c) >= 0;
            if (c == '%') {
                if (i + 2 >= text.length()) { // two hex digits must follow
                    return false;
                }
                if (!isHex(text.charAt(i + 1)) || !isHex(text.charAt(i + 2))) {
                    return false;
                }
                i += 2;
            } else if (!(unreserved || subDelim || c == ':' || c == '@' || c == '/')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
