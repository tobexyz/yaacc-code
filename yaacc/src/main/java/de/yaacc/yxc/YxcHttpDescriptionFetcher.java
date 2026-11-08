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
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.concurrent.ScheduledFuture;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * {@link YxcDescriptionFetcher} over {@code HttpURLConnection}: plain http only, no redirects, short timeouts,
 * the size of the answer is bounded. The URL comes from yaacc's own device registry, never from user input.
 */
public final class YxcHttpDescriptionFetcher implements YxcDescriptionFetcher {
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int READ_TIMEOUT_MILLIS = 5000;
    // the limit is in characters; a character is at most 3 bytes in UTF-8 (4 for a pair, which counts as 2)
    private static final int MAX_BYTES = YxcDescriptionParser.MAX_DESCRIPTION_CHARS * 3;

    private final long totalMillis;

    public YxcHttpDescriptionFetcher() {
        this(YxcWatchdog.DEFAULT_TOTAL_MILLIS);
    }

    YxcHttpDescriptionFetcher(long totalMillis) {
        this.totalMillis = totalMillis;
    }

    @Override
    public String fetch(String descriptorUrl) throws IOException {
        URL url = checkedUrl(descriptorUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        // each read has its own timeout; the watchdog bounds connect, headers and body together
        ScheduledFuture<?> watchdog = YxcWatchdog.arm(connection, totalMillis);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("description: HTTP status " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                long deadline = System.nanoTime() + totalMillis * 1_000_000L;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    if (System.nanoTime() - deadline > 0) {
                        throw new IOException("description: takes too long");
                    }
                    if (out.size() > MAX_BYTES) {
                        throw new IOException("description: too large");
                    }
                }
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                if (text.length() > YxcDescriptionParser.MAX_DESCRIPTION_CHARS) {
                    throw new IOException("description: too large");
                }
                return text;
            }
        } finally {
            watchdog.cancel(false);
            connection.disconnect();
        }
    }

    static URL checkedUrl(String descriptorUrl) throws IOException {
        try {
            URI uri = new URI(descriptorUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("description: not a plain http URL");
            }
            return uri.toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("description: not a URL");
        }
    }
}
