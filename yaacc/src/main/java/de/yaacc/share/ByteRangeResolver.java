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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Turns the {@code Range} header of a request into the one answer a single-range server gives:
 * status 200, 206 or 416, the bytes to send and the {@code Content-Range} text. Plain Java (no
 * {@code android.*}), long arithmetic only, and nothing here throws for any input: the header is
 * attacker-controlled.
 * <p>
 * Decisions (RFC 9110 section 14):
 * <ul>
 * <li>No usable {@code Range} header gives 200 with the whole resource and no
 * {@code Content-Range}. "Not usable" means: null, empty, a unit other than {@code bytes}
 * (compared ignoring ASCII case), or a syntactically invalid header (a malformed piece, a range
 * whose last position is below its first, a header longer than {@link #MAX_HEADER_LENGTH}). An
 * invalid header is IGNORED, never answered with 416.</li>
 * <li>A syntactically valid header that selects nothing gives 416 with {@code bytes *}{@code /len}:
 * the first position is at or beyond the length, a suffix of length 0, or any range on an empty
 * resource (also a suffix: nothing to select).</li>
 * <li>More than one range is not served (no multipart/byteranges): 200 with the whole resource.</li>
 * <li>A satisfiable single range gives 206, also when it covers the whole resource
 * ({@code bytes 0-999/1000}). A last position at or beyond the length is clipped to
 * {@code length - 1}; a suffix longer than the resource gives everything from 0.</li>
 * <li>Digits that overflow {@code long} saturate at {@link Long#MAX_VALUE} (they only ever
 * mean "far beyond any length"); they never throw.</li>
 * <li>An unknown (negative) total length cannot be ranged: 200, {@code length()} is -1.</li>
 * <li>The header may have leading and trailing spaces or tabs (and only those: a line break or
 * other control character makes it invalid). List elements are separated by commas with optional
 * spaces or tabs around them; empty elements are ignored (RFC 9110 5.6.1.2); whitespace anywhere
 * else (inside {@code 0-99}, after {@code =}) makes the header invalid. Digits are ASCII only,
 * leading zeros are fine.</li>
 * </ul>
 */
public final class ByteRangeResolver {

    /** A header longer than this is ignored (a request line is capped by the server anyway). */
    public static final int MAX_HEADER_LENGTH = 1024;

    /** The last-position value that means "open" ({@code bytes=100-}). */
    public static final long OPEN = -1L;

    private static final String UNIT = "bytes";

    private ByteRangeResolver() {
    }

    /**
     * One requested range, immutable. Build it with {@link #of(long, long)}, {@link #from(long)}
     * or {@link #suffix(long)}. Out-of-contract values (negative positions, last below first) can
     * be built but make {@link #isValid()} false; {@link ByteRangeResolver#resolve} then ignores
     * the range request.
     */
    public static final class Range {
        private final long first;
        private final long last;
        private final long suffixLength;
        private final boolean suffix;

        private Range(long first, long last, long suffixLength, boolean suffix) {
            this.first = first;
            this.last = last;
            this.suffixLength = suffixLength;
            this.suffix = suffix;
        }

        /** {@code bytes=first-last}; {@code last == }{@link #OPEN} (-1) means "to the end". */
        public static Range of(long first, long last) {
            return new Range(first, last, 0L, false);
        }

        /** {@code bytes=first-}. */
        public static Range from(long first) {
            return new Range(first, OPEN, 0L, false);
        }

        /** {@code bytes=-length}: the last {@code length} bytes. */
        public static Range suffix(long length) {
            return new Range(0L, OPEN, length, true);
        }

        public boolean isSuffix() {
            return suffix;
        }

        /** The first position; 0 for a suffix range. */
        public long first() {
            return first;
        }

        /** The last position, {@link #OPEN} if open; {@link #OPEN} for a suffix range. */
        public long last() {
            return last;
        }

        /** The suffix length; 0 if this is not a suffix range. */
        public long suffixLength() {
            return suffixLength;
        }

        /**
         * @return false for a negative position or suffix length, a last position (other than
         * {@link #OPEN}) below the first, and any other value outside the contract
         */
        public boolean isValid() {
            if (suffix) {
                return suffixLength >= 0L;
            }
            return first >= 0L && (last == OPEN || last >= first);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Range)) {
                return false;
            }
            Range other = (Range) o;
            return first == other.first && last == other.last
                    && suffixLength == other.suffixLength && suffix == other.suffix;
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, last, suffixLength, suffix);
        }

        @Override
        public String toString() {
            return suffix ? "Range{-" + suffixLength + "}"
                    : "Range{" + first + "-" + (last == OPEN ? "" : String.valueOf(last)) + "}";
        }
    }

    /** The answer for one request, immutable. */
    public static final class Result {
        private final int status;
        private final long start;
        private final long endInclusive;
        private final long length;
        private final String contentRangeHeader;

        private Result(int status, long start, long endInclusive, long length,
                       String contentRangeHeader) {
            this.status = status;
            this.start = start;
            this.endInclusive = endInclusive;
            this.length = length;
            this.contentRangeHeader = contentRangeHeader;
        }

        /** 200 (whole resource), 206 (partial) or 416 (range not satisfiable). */
        public int status() {
            return status;
        }

        /** First byte to send (inclusive); 0 for 416. */
        public long start() {
            return start;
        }

        /** Last byte to send (inclusive); {@code start() - 1} (an empty range) for 416 and for an empty resource. */
        public long endInclusive() {
            return endInclusive;
        }

        /** Number of bytes to send: 0 for 416 and for an empty resource, -1 for an unknown total. */
        public long length() {
            return length;
        }

        /**
         * The {@code Content-Range} header value: {@code bytes 0-99/1000} for 206,
         * {@code bytes *}{@code /1000} for 416, null for 200.
         */
        public String contentRangeHeader() {
            return contentRangeHeader;
        }
    }

    /**
     * Parses the raw {@code Range} header value. Never throws, never returns null.
     *
     * @param rangeHeader the header value, may be null
     * @return the requested ranges in header order; EMPTY (= serve 200 and ignore the header) for
     * null, empty, a unit other than {@code bytes}, a header longer than {@link #MAX_HEADER_LENGTH},
     * a header without any range ({@code bytes=}, {@code bytes=,}) and ANY syntactically invalid
     * piece (the whole header is then ignored, e.g. {@code bytes=0-9,x} or {@code bytes=10-5}).
     * Numbers that overflow {@code long} saturate at {@link Long#MAX_VALUE}.
     */
    public static List<Range> parseHeader(String rangeHeader) {
        if (rangeHeader == null || rangeHeader.length() > MAX_HEADER_LENGTH) {
            return Collections.emptyList();
        }
        int begin = 0;
        int end = rangeHeader.length();
        while (begin < end && isBlank(rangeHeader.charAt(begin))) {
            begin++;
        }
        while (end > begin && isBlank(rangeHeader.charAt(end - 1))) {
            end--;
        }
        String header = rangeHeader.substring(begin, end);
        int equals = header.indexOf('=');
        if (equals < 0 || !header.substring(0, equals).equalsIgnoreCase(UNIT)) {
            return Collections.emptyList();
        }
        // RFC 9110: no whitespace between "=" and the first element
        if (equals + 1 < header.length() && isBlank(header.charAt(equals + 1))) {
            return Collections.emptyList();
        }
        List<Range> parsed = new ArrayList<>();
        int pos = equals + 1;
        while (pos <= header.length()) {
            int comma = header.indexOf(',', pos);
            int pieceEnd = comma < 0 ? header.length() : comma;
            int from = pos;
            int to = pieceEnd;
            while (from < to && isBlank(header.charAt(from))) {
                from++;
            }
            while (to > from && isBlank(header.charAt(to - 1))) {
                to--;
            }
            if (from < to) {
                Range range = parseRange(header, from, to);
                if (range == null) {
                    return Collections.emptyList();
                }
                parsed.add(range);
            }
            if (comma < 0) {
                break;
            }
            pos = comma + 1;
        }
        return parsed;
    }

    /** One {@code first-last}, {@code first-} or {@code -suffix} piece, null if invalid. */
    private static Range parseRange(String text, int from, int to) {
        int dash = -1;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '-') {
                if (dash >= 0) {
                    return null;
                }
                dash = i;
            } else if (c < '0' || c > '9') {
                return null;
            }
        }
        if (dash < 0) {
            return null;
        }
        boolean hasFirst = dash > from;
        boolean hasLast = dash + 1 < to;
        if (!hasFirst && !hasLast) {
            return null;
        }
        if (!hasFirst) {
            return Range.suffix(parseSaturating(text, dash + 1, to));
        }
        long first = parseSaturating(text, from, dash);
        if (!hasLast) {
            return Range.from(first);
        }
        long last = parseSaturating(text, dash + 1, to);
        return last < first ? null : Range.of(first, last);
    }

    /** ASCII digits only (checked by the caller); too large values stop at Long.MAX_VALUE. */
    private static long parseSaturating(String text, int from, int to) {
        long value = 0L;
        for (int i = from; i < to; i++) {
            int digit = text.charAt(i) - '0';
            if (value > (Long.MAX_VALUE - digit) / 10L) {
                return Long.MAX_VALUE;
            }
            value = value * 10L + digit;
        }
        return value;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * Resolves the requested ranges against the resource length. Never throws.
     *
     * @param requested   the ranges from {@link #parseHeader(String)}; null or empty means no
     *                    range (200, whole resource); more than one range, a null element or an
     *                    invalid range ({@link Range#isValid()}) also gives 200 with the whole
     *                    resource
     * @param totalLength the length of the resource in bytes; negative means unknown (200)
     */
    public static Result resolve(List<Range> requested, long totalLength) {
        if (totalLength < 0L) {
            return new Result(200, 0L, -1L, -1L, null);
        }
        Range range = singleValidRange(requested);
        if (range == null) {
            return whole(totalLength);
        }
        if (totalLength == 0L) {
            return unsatisfiable(totalLength);
        }
        long start;
        long endInclusive;
        if (range.isSuffix()) {
            if (range.suffixLength() == 0L) {
                return unsatisfiable(totalLength);
            }
            start = Math.max(0L, totalLength - range.suffixLength());
            endInclusive = totalLength - 1L;
        } else {
            if (range.first() >= totalLength) {
                return unsatisfiable(totalLength);
            }
            start = range.first();
            endInclusive = range.last() == OPEN ? totalLength - 1L
                    : Math.min(range.last(), totalLength - 1L);
        }
        return new Result(206, start, endInclusive, endInclusive - start + 1L,
                "bytes " + start + "-" + endInclusive + "/" + totalLength);
    }

    /** The one range to serve, or null when the whole resource is to be served. */
    private static Range singleValidRange(List<Range> requested) {
        if (requested == null || requested.size() != 1) {
            return null;
        }
        Range range = requested.get(0);
        return range != null && range.isValid() ? range : null;
    }

    private static Result whole(long totalLength) {
        return new Result(200, 0L, totalLength - 1L, totalLength, null);
    }

    private static Result unsatisfiable(long totalLength) {
        return new Result(416, 0L, -1L, 0L, "bytes */" + totalLength);
    }
}
