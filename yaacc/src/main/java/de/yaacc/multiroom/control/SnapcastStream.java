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

import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An audio source of the server. {@link #getHost()} and {@link #getPort()} come from the {@code uri.host} field, which
 * the server fills with host and port together (for example {@code 0.0.0.0:4953}). The values are what the server
 * reports: they are for matching and display, never a place to connect to. Immutable.
 */
public final class SnapcastStream {

    private final String id;
    private final String status;
    private final String rawUri;
    private final String scheme;
    private final String host;
    private final int port;
    private final String path;
    private final Map<String, String> query;

    public SnapcastStream(String id, String status, String rawUri, String scheme, String host, int port, String path,
                          Map<String, String> query) {
        this.id = Objects.requireNonNull(id);
        this.status = status == null ? "unknown" : SnapcastJson.truncate(status);
        this.rawUri = rawUri == null ? "" : SnapcastJson.truncate(rawUri);
        this.scheme = scheme == null ? "" : SnapcastJson.truncate(scheme);
        this.host = host == null ? "" : SnapcastJson.truncate(host);
        this.port = port >= 1 && port <= 65535 ? port : -1;
        this.path = path == null ? "" : SnapcastJson.truncate(path);
        Map<String, String> copy = new TreeMap<>();
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (copy.size() >= SnapcastLimits.MAX_QUERY_ENTRIES) {
                    break;
                }
                copy.put(SnapcastJson.truncate(entry.getKey()), SnapcastJson.truncate(entry.getValue()));
            }
        }
        this.query = Collections.unmodifiableMap(copy);
    }

    /** @return the stream, or null when the JSON has no id */
    static SnapcastStream fromJson(JSONObject json) {
        String id = SnapcastJson.strictString(json, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        JSONObject uri = SnapcastJson.object(json, "uri");
        String hostField = SnapcastJson.string(uri, "host", "");
        String host = hostField;
        int port = -1;
        int colon = hostField.lastIndexOf(':');
        if (colon > 0) {
            boolean bracketed = hostField.startsWith("[");
            boolean singleColon = hostField.indexOf(':') == colon;
            // "host:port", "[v6]:port"; a loose IPv6 literal (several colons, no brackets) has no port
            if ((bracketed && hostField.charAt(colon - 1) == ']') || (!bracketed && singleColon)) {
                port = parsePort(hostField.substring(colon + 1));
                host = hostField.substring(0, colon);
            }
        }
        Map<String, String> query = new TreeMap<>();
        JSONObject queryJson = SnapcastJson.object(uri, "query");
        if (queryJson != null) {
            Iterator<String> keys = queryJson.keys();
            while (keys.hasNext() && query.size() < SnapcastLimits.MAX_QUERY_ENTRIES) {
                String key = keys.next();
                query.put(SnapcastJson.truncate(key), SnapcastJson.string(queryJson, key, ""));
            }
        }
        return new SnapcastStream(id,
                SnapcastJson.string(json, "status", "unknown"),
                SnapcastJson.string(uri, "raw", ""),
                SnapcastJson.string(uri, "scheme", ""),
                host, port,
                SnapcastJson.string(uri, "path", ""),
                query);
    }

    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return -1;
            }
        }
        int port = Integer.parseInt(text);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    public String getId() {
        return id;
    }

    /** {@code idle}, {@code playing} or what the server says; {@code unknown} when it says nothing. */
    public String getStatus() {
        return status;
    }

    public String getRawUri() {
        return rawUri;
    }

    public String getScheme() {
        return scheme;
    }

    /** The host part of {@code uri.host}, empty for a pipe or file source. */
    public String getHost() {
        return host;
    }

    /** The port part of {@code uri.host}, or -1 when there is none. */
    public int getPort() {
        return port;
    }

    public String getPath() {
        return path;
    }

    /** The query of the URI (at most 32 entries), unmodifiable. */
    public Map<String, String> getQuery() {
        return query;
    }

    /** @return the value of a query parameter, or null */
    public String getQueryParameter(String name) {
        return query.get(name);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastStream)) {
            return false;
        }
        SnapcastStream that = (SnapcastStream) other;
        return port == that.port && id.equals(that.id) && status.equals(that.status) && rawUri.equals(that.rawUri)
                && scheme.equals(that.scheme) && host.equals(that.host) && path.equals(that.path) && query.equals(that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, status, rawUri, scheme, host, port, path, query);
    }

    /** Fixed words only: status and scheme are text of the server (up to 1 KiB, may hold line breaks or addresses). */
    @Override
    public String toString() {
        return "SnapcastStream{status=" + known(status, "idle", "playing", "unknown") + ", scheme="
                + known(scheme, "tcp", "pipe", "file", "alsa", "jack", "meta", "process") + "}";
    }

    private static String known(String value, String... words) {
        for (String word : words) {
            if (word.equals(value)) {
                return word;
            }
        }
        return "other";
    }
}
