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
package de.yaacc.yxc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * {@link YxcTransport} over {@link HttpURLConnection} to the control URL
 * {@code http://<host>[:port]/YamahaExtendedControl/v1/} of one device.
 * <p>
 * It talks only to the exact address it was constructed with: the caller must take that address from yaacc's own
 * discovery, never from user input or from a document (the API has no authentication). Redirects are not followed
 * and no proxy is used. When an event port is given, every request carries the two headers that make the device
 * push its events by UDP to that port of this phone.
 */
public final class YxcHttpTransport implements YxcTransport {
    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 5000;

    static final String CONTROL_PATH = "/YamahaExtendedControl/v1/";
    static final String APP_NAME_HEADER = "X-AppName";
    static final String APP_NAME = "MusicCast/1.0";
    static final String APP_PORT_HEADER = "X-AppPort";
    // the open-source clients send the body as plain text; HttpURLConnection would otherwise label it as a web form
    static final String POST_CONTENT_TYPE = "text/plain; charset=utf-8";

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+");
    private static final Pattern IPV6_HOST = Pattern.compile("[0-9A-Fa-f:.]+");
    // path and query as the client builds them: no scheme, no authority, no way up
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_/.,=&?%+~-]*");

    private final String baseUrl;
    private final int eventPort;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /** @param eventPort the UDP port events should be pushed to, 0 for none */
    public YxcHttpTransport(String host, int eventPort) {
        this(host, DEFAULT_PORT, eventPort, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    public YxcHttpTransport(String host, int port, int eventPort, int connectTimeoutMillis, int readTimeoutMillis) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (eventPort < 0 || eventPort > 65535) {
            throw new IllegalArgumentException("event port out of range: " + eventPort);
        }
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.baseUrl = "http://" + authority(host, port) + CONTROL_PATH;
        this.eventPort = eventPort;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    private static String authority(String host, int port) {
        if (host == null) {
            throw new IllegalArgumentException("host is null");
        }
        String name;
        if (host.indexOf(':') >= 0) {
            // an IPv6 literal has at least two colons; "address:port" is not accepted here
            if (!IPV6_HOST.matcher(host).matches() || host.indexOf(':') == host.lastIndexOf(':')) {
                throw new IllegalArgumentException("not an address");
            }
            name = "[" + host + "]";
        } else {
            if (!HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("not a host name or address");
            }
            name = host;
        }
        return port == DEFAULT_PORT ? name : name + ":" + port;
    }

    @Override
    public String get(String path) throws IOException {
        return exchange("GET", path, null);
    }

    @Override
    public String post(String path, String jsonBody) throws IOException {
        if (jsonBody == null) {
            throw new IllegalArgumentException("body is null");
        }
        return exchange("POST", path, jsonBody);
    }

    private String exchange(String method, String path, String body) throws IOException {
        if (path == null || !PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException("not a relative control path");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection(Proxy.NO_PROXY);
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setUseCaches(false);
            if (eventPort > 0) {
                connection.setRequestProperty(APP_NAME_HEADER, APP_NAME);
                connection.setRequestProperty(APP_PORT_HEADER, String.valueOf(eventPort));
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", POST_CONTENT_TYPE);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status > 299) {
                throw new YxcHttpStatusException(status);
            }
            try (InputStream in = connection.getInputStream()) {
                return readBounded(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_BODY_BYTES) {
                throw new IOException("answer larger than " + MAX_BODY_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
