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
package de.yaacc.upnp.server.http;

import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;

import java.util.Collections;
import java.util.List;

import de.yaacc.util.HttpRange;

/**
 * The status line and {@code Content-Range} of the answer to one request of the content handler
 * (RFC 9110 section 14), decided in one place for every kind of resource (local file, SAF document,
 * album art, thumbnail, external link), from the requested ranges and the FULL size of the resource.
 * Plain JVM; nothing here throws for any input.
 * <ul>
 * <li>No range: 200, the whole resource, no {@code Content-Range}.</li>
 * <li>Size unknown (negative, e.g. an external link whose origin does not tell): with a range 206
 * without {@code Content-Range} (the range goes on to the origin, as before), without a range 200.
 * There is no {@link #window()}.</li>
 * <li>Satisfiable range of a known size: 206 and the {@code Content-Range} of the {@link RangeWindow}
 * (end clipped to the resource, a suffix is the tail); the window says which bytes to send.</li>
 * <li>Unsatisfiable range of a known size (first position at or beyond the size, last below first,
 * suffix 0, any range of an empty resource): 416 with {@code Content-Range: bytes *}{@code /size}
 * and NO body; a caller must not open the resource ({@link #hasBody()}).</li>
 * <li>Several ranges: only the first is served (no multipart/byteranges); the others are ignored.</li>
 * </ul>
 * A {@code Range} header that cannot be used is no range at all ({@link #parseHeader}): the whole
 * resource is sent, which RFC 9110 allows for any Range header.
 */
final class RangeResponsePlan {

    /** A header longer than this is ignored; the server caps the request head anyway. */
    static final int MAX_HEADER_LENGTH = 1024;

    private final int status;
    private final long size;
    private final RangeWindow window;
    private final String contentRange;

    private RangeResponsePlan(int status, long size, RangeWindow window, String contentRange) {
        this.status = status;
        this.size = size;
        this.window = window;
        this.contentRange = contentRange;
    }

    /**
     * The ranges of a raw {@code Range} header value. Never throws: no header, an empty one, one
     * over {@link #MAX_HEADER_LENGTH}, a unit other than {@code bytes} (the unit is matched
     * case-sensitively by {@link HttpRange}, {@code Bytes=} is ignored as well), a malformed piece
     * or a number beyond {@code long} give the EMPTY list: the header is ignored, the answer is 200.
     */
    static List<HttpRange> parseHeader(String headerValue) {
        if (headerValue == null || headerValue.isEmpty() || headerValue.length() > MAX_HEADER_LENGTH) {
            return Collections.emptyList();
        }
        try {
            return HttpRange.parseRangeHeader(headerValue.trim());
        } catch (RuntimeException e) {
            // the parser throws for anything it does not understand (also NumberFormatException)
            return Collections.emptyList();
        }
    }

    /**
     * @param ranges the requested ranges (from {@link #parseHeader}); null or empty = none
     * @param size   the FULL size of the resource in bytes, negative if unknown
     */
    static RangeResponsePlan of(List<HttpRange> ranges, long size) {
        HttpRange first = ranges == null || ranges.isEmpty() ? null : ranges.get(0);
        if (size < 0) {
            return new RangeResponsePlan(first == null ? HttpStatus.SC_OK : HttpStatus.SC_PARTIAL_CONTENT,
                    size, null, null);
        }
        RangeWindow window = RangeWindow.resolve(first, size);
        if (first == null) {
            return new RangeResponsePlan(HttpStatus.SC_OK, size, window, null);
        }
        if (!window.isSatisfiable()) {
            return new RangeResponsePlan(HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE, size, window,
                    "bytes */" + size);
        }
        return new RangeResponsePlan(HttpStatus.SC_PARTIAL_CONTENT, size, window, window.contentRange(size));
    }

    /** 200, 206 or 416. */
    int status() {
        return status;
    }

    /** The full size of the resource, negative if unknown. */
    long size() {
        return size;
    }

    /** The {@code Content-Range} value, null for 200 and for a 206 of an unknown size. */
    String contentRange() {
        return contentRange;
    }

    /**
     * The bytes to send (the whole resource for status 200); null if the size is unknown. Of a 416
     * the window is empty and must not be used to open anything.
     */
    RangeWindow window() {
        return window;
    }

    /** True if the answer is a slice of a resource of known size: status 206 with a window. */
    boolean isPartial() {
        return status == HttpStatus.SC_PARTIAL_CONTENT && window != null;
    }

    /** False for 416: the response has no entity and the resource is not opened. */
    boolean hasBody() {
        return status != HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE;
    }

    /** Sets the status and, if there is one, the {@code Content-Range} header. */
    void applyTo(AsyncResponseBuilder builder) {
        builder.setStatus(status);
        if (contentRange != null) {
            builder.setHeader(HttpHeaders.CONTENT_RANGE, contentRange);
        }
    }
}
