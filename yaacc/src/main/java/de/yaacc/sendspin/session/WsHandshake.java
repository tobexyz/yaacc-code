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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The opening handshake of a WebSocket (RFC 6455, section 4) for both sides, strict and small: the request is a GET with the five
 * required headers, no extensions and no subprotocol are offered or accepted, the header block is capped (lines, count, bytes),
 * and nothing is read past the blank line (the first frame follows it on the same stream).
 */
public final class WsHandshake {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_LINES = 64;

    private WsHandshake() {
    }

    /** {@code Sec-WebSocket-Accept} for a key (RFC 6455, section 1.3). */
    public static String acceptFor(String key) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is part of every platform");
        }
    }

    /** A request that arrived on the server side. */
    public static final class Request {
        private final String path;
        private final Map<String, String> headers;

        Request(String path, Map<String, String> headers) {
            this.path = path;
            this.headers = headers;
        }

        public String path() {
            return path;
        }

        /** @return the value of a header (name in lower case), or null */
        public String header(String lowerCaseName) {
            return headers.get(lowerCaseName);
        }
    }

    // ------------------------------------------------------------------ the client side

    /**
     * Opens the connection: sends the request (no {@code Origin} header, as the protocol requires) and checks the answer.
     *
     * @param host the host of the {@code Host} header (an IPv6 literal without brackets is bracketed here)
     * @param path the request path of the advertised endpoint; it comes from the network and is checked
     * @throws IOException if the server does not accept the upgrade or answers wrongly
     */
    public static void connect(InputStream in, OutputStream out, String host, int port, String path, SecureRandom random)
            throws IOException {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || !isToken(path, true)) {
            throw new IllegalArgumentException("bad request path");
        }
        if (host == null || host.isEmpty() || !isToken(host, false)) {
            throw new IllegalArgumentException("bad host");
        }
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String[] lines = readHeaderBlock(in);
        String status = lines[0];
        if (!status.startsWith("HTTP/1.1 101")) {
            String code = status.length() >= 12 ? status.substring(9, 12) : "?";
            throw new IOException("upgrade refused: " + code);
        }
        Map<String, String> headers = parseHeaders(lines);
        if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
            throw new IOException("bad Upgrade header");
        }
        if (!hasToken(headers.get("connection"), "upgrade")) {
            throw new IOException("bad Connection header");
        }
        if (!acceptFor(key).equals(headers.get("sec-websocket-accept"))) {
            throw new IOException("bad Sec-WebSocket-Accept");
        }
        if (headers.containsKey("sec-websocket-extensions") || headers.containsKey("sec-websocket-protocol")) {
            throw new IOException("an extension or a subprotocol that was not offered");
        }
    }

    // ------------------------------------------------------------------ the server side

    /**
     * Reads a request and answers it; for an endpoint that must refuse browsers, {@code rejectOrigin} answers 403 to a request that
     * carries an {@code Origin} header (the protocol demands it of a client's endpoint).
     *
     * @return the request, after the 101 answer was sent
     * @throws IOException if the request is not a valid upgrade (a 400 or 403 answer was sent where possible)
     */
    public static Request accept(InputStream in, OutputStream out, boolean rejectOrigin) throws IOException {
        String[] lines = readHeaderBlock(in);
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3 || !requestLine[0].equals("GET") || !requestLine[2].equals("HTTP/1.1")
                || !requestLine[1].startsWith("/") || !isToken(requestLine[1], true)) {
            refuse(out, "400 Bad Request");
            throw new IOException("bad request line");
        }
        Map<String, String> headers = parseHeaders(lines);
        String key = headers.get("sec-websocket-key");
        byte[] keyBytes = key == null ? null : decodeKey(key);
        if (!"websocket".equalsIgnoreCase(headers.get("upgrade")) || !hasToken(headers.get("connection"), "upgrade")
                || keyBytes == null || !"13".equals(headers.get("sec-websocket-version"))) {
            refuse(out, "400 Bad Request");
            throw new IOException("not a WebSocket upgrade");
        }
        if (rejectOrigin && headers.containsKey("origin")) {
            refuse(out, "403 Forbidden");
            throw new IOException("a request with an Origin header");
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptFor(key) + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return new Request(requestLine[1], headers);
    }

    private static byte[] decodeKey(String key) {
        try {
            byte[] bytes = Base64.getDecoder().decode(key);
            return bytes.length == 16 ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void refuse(OutputStream out, String status) {
        try {
            out.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // the peer is gone
        }
    }

    // ------------------------------------------------------------------ the header block

    /** Reads up to the blank line, byte by byte so that nothing of the first frame is consumed; @return the lines, the first is the request or status line. */
    private static String[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        int matched = 0; // how many bytes of "\r\n\r\n" were just seen
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("connection closed during the handshake");
            }
            if (block.size() >= MAX_HEADER_BYTES) {
                throw new IOException("handshake too long");
            }
            block.write(b);
            if ((b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))) {
                matched++;
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        String text = new String(block.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = text.substring(0, text.length() - 4).split("\r\n", -1);
        if (lines.length > MAX_HEADER_LINES || lines[0].isEmpty()) {
            throw new IOException("bad handshake");
        }
        return lines;
    }

    private static Map<String, String> parseHeaders(String[] lines) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0 || lines[i].charAt(0) == ' ' || lines[i].charAt(0) == '\t') {
                throw new IOException("bad header line");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            String old = headers.get(name);
            headers.put(name, old == null ? value : old + ", " + value);
        }
        return headers;
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String part : value.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /** No control characters, spaces or non-ASCII (so that nothing can end the request line or add a header). */
    private static boolean isToken(String text, boolean allowPathCharacters) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return false;
            }
        }
        return allowPathCharacters || text.indexOf('/') < 0;
    }
}
