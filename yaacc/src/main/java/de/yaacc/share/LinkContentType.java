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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Finds the media type of a shared http(s) link with a bounded HTTP {@code HEAD}, for the case
 * that the media metadata reader could not read it: a failed read must not silently make every
 * link an image. Plain Java ({@code java.net}); blocking, so worker threads only.
 * <ul>
 * <li>Only http and https are requested, redirects are followed by hand (at most
 * {@value #MAX_REDIRECTS}) and every hop must be a fetchable http(s) link that {@code hopAllowed}
 * accepts, so a redirect cannot lead to {@code file:}, to another scheme or to a target the link
 * policy refuses.</li>
 * <li>Connect and read time out after the given time; the body is never read.</li>
 * <li>The answer is a concrete audio, video or image type (see
 * {@link SharedContentPolicy#isAcceptableMimeType}) in lower case, else null.</li>
 * <li>Nothing is logged here and no exception escapes: the link may carry a token.</li>
 * </ul>
 */
public final class LinkContentType {

    static final int MAX_REDIRECTS = 3;

    private LinkContentType() {
    }

    /**
     * @param url        the link, http or https
     * @param hopAllowed called with the link and with every redirect target before it is requested
     * @param timeoutMillis connect and read timeout per request
     * @return the media type from the {@code Content-Type} header, or null
     */
    public static String find(String url, Predicate<String> hopAllowed, int timeoutMillis) {
        try {
            String current = url;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!HttpLinkTarget.isFetchable(current) || (hopAllowed != null && !hopAllowed.test(current))) {
                    return null;
                }
                URL target = new URL(current);
                HttpURLConnection connection = (HttpURLConnection) target.openConnection();
                try {
                    connection.setRequestMethod("HEAD");
                    connection.setConnectTimeout(timeoutMillis);
                    connection.setReadTimeout(timeoutMillis);
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isEmpty()) {
                            return null;
                        }
                        // relative locations are resolved against the current link
                        current = new URL(target, location).toString();
                        continue;
                    }
                    if (code >= 200 && code < 300) {
                        return concreteMediaType(connection.getContentType());
                    }
                    return null;
                } finally {
                    connection.disconnect();
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * @param contentType a {@code Content-Type} header value, may be null
     * @return the type without parameters in lower case if it is a concrete audio, video or image
     * type, else null
     */
    public static String concreteMediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        return SharedContentPolicy.isAcceptableMimeType(type) ? type.toLowerCase(Locale.ROOT) : null;
    }
}
