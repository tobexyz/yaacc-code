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

import de.yaacc.util.HttpRange;

/**
 * The bytes of a resource of a known size that one parsed {@code Range} entry selects, with the
 * edge cases settled in one place (RFC 9110 section 14.1.2): an open end runs to the last byte, an
 * end beyond the resource is clipped, a suffix longer than the resource gives all of it, and an
 * end of 0 is the single byte 0 ({@code bytes=0-0}), not "no end". A range that selects nothing
 * (first position at or beyond the size, a suffix of 0, a last position below the first, any range
 * of an empty resource) is {@link #isSatisfiable() not satisfiable} and has length 0.
 */
final class RangeWindow {

    private final long start;
    private final long length;
    private final boolean satisfiable;

    private RangeWindow(long start, long length, boolean satisfiable) {
        this.start = start;
        this.length = length;
        this.satisfiable = satisfiable;
    }

    /**
     * @param range the requested range, null for the whole resource
     * @param size  the size of the resource in bytes, not negative
     */
    static RangeWindow resolve(HttpRange range, long size) {
        if (range == null) {
            return new RangeWindow(0, size, true);
        }
        if (range.getSuffixLength() != null) {
            long wanted = range.getSuffixLength();
            if (wanted <= 0 || size <= 0) {
                return unsatisfiable(size);
            }
            long length = Math.min(wanted, size);
            return new RangeWindow(size - length, length, true);
        }
        long first = range.getStart() == null ? 0 : range.getStart();
        if (first < 0 || first >= size) {
            return unsatisfiable(size);
        }
        long last = size - 1;
        if (range.getEnd() != null) {
            if (range.getEnd() < first) {
                return unsatisfiable(size);
            }
            last = Math.min(range.getEnd(), size - 1);
        }
        return new RangeWindow(first, last - first + 1, true);
    }

    private static RangeWindow unsatisfiable(long size) {
        return new RangeWindow(Math.max(size, 0), 0, false);
    }

    /** The first byte to send. */
    long start() {
        return start;
    }

    /** The number of bytes to send, 0 if the range is not satisfiable. */
    long length() {
        return length;
    }

    boolean isSatisfiable() {
        return satisfiable;
    }

    /** The {@code Content-Range} value of a satisfiable window of a resource of {@code size} bytes. */
    String contentRange(long size) {
        return "bytes " + start + "-" + (start + length - 1) + "/" + size;
    }
}
