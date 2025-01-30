/*
 *
 * Copyright (C) 2025 Tobias Schoene www.yaacc.de
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
package de.yaacc.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HttpRange {
    private String unit;
    private Integer start;
    private Integer end;
    private Integer suffixLength;

    public HttpRange() {

    }

    public HttpRange(String unit, Integer start, Integer end, Integer suffixLength) {
        this.unit = unit;
        this.start = start;
        this.end = end;
        this.suffixLength = suffixLength;
    }

    @Override
    public String toString() {
        return "HttpRange{" +
                "unit=" + unit +
                "start=" + start +
                ", end=" + end +
                ", suffixLength=" + suffixLength +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HttpRange httpRange = (HttpRange) o;
        return Objects.equals(unit, httpRange.unit) && Objects.equals(start, httpRange.start) && Objects.equals(end, httpRange.end) && Objects.equals(suffixLength, httpRange.suffixLength);
    }

    @Override
    public int hashCode() {
        return Objects.hash(unit, start, end, suffixLength);
    }

    public static String toHeaderString(List<HttpRange> range) {
        if (range == null || range.isEmpty()) {
            return null;
        }
        return String.format("bytes=%s", range.stream().map(it -> it.getSuffixLength() != null ? "-" + it.getSuffixLength() : it.getStart() + "-" + (it.getEnd() != null ? it.getEnd() : "")).collect(Collectors.joining(",")));
    }

    public static List<HttpRange> parseRangeHeader(String rangeHeader) {
        List<HttpRange> ranges = new ArrayList<>();
        if (rangeHeader == null || rangeHeader.equals("")) {
            return ranges;
        }
        String byteRangeSetRegex = "(((?<byteRangeSpec>(?<firstBytePos>\\d+)-(?<lastBytePos>\\d+)?)|(?<suffixByteRangeSpec>-(?<suffixLength>\\d+)))(,|$))";
        String byteRangesSpecifierRegex = "bytes=(?<byteRangeSet>" + byteRangeSetRegex + "{1,})";
        Pattern byteRangeSetPattern = Pattern.compile(byteRangeSetRegex);
        Pattern byteRangesSpecifierPattern = Pattern.compile(byteRangesSpecifierRegex);
        Matcher byteRangesSpecifierMatcher = byteRangesSpecifierPattern.matcher(rangeHeader);
        if (byteRangesSpecifierMatcher.matches()) {
            String byteRangeSet = byteRangesSpecifierMatcher.group("byteRangeSet");
            Matcher byteRangeSetMatcher = byteRangeSetPattern.matcher(byteRangeSet);
            while (byteRangeSetMatcher.find()) {
                HttpRange range = new HttpRange();
                range.unit = "bytes";
                if (byteRangeSetMatcher.group("byteRangeSpec") != null) {
                    String start = byteRangeSetMatcher.group("firstBytePos");
                    String end = byteRangeSetMatcher.group("lastBytePos");
                    range.start = Integer.valueOf(start);
                    range.end = end == null ? null : Integer.valueOf(end);
                } else if (byteRangeSetMatcher.group("suffixByteRangeSpec") != null) {
                    range.suffixLength = Integer.valueOf(byteRangeSetMatcher.group("suffixLength"));
                } else {
                    throw new RuntimeException("Invalid range header");
                }
                ranges.add(range);
            }
        } else {
            throw new RuntimeException("Invalid or unsupported range header");
        }
        return ranges;
    }

    public Integer getStart() {
        return start;
    }

    public Integer getSuffixLength() {
        return suffixLength;
    }

    public Integer getEnd() {
        return end;
    }
}
