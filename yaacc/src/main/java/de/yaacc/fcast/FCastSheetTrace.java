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
package de.yaacc.fcast;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import de.yaacc.fcast.protocol.FCastText;

/**
 * The troubleshooting trace of the settings sheet (task 5.3): the last events the sheet saw (the connection, the protocol version,
 * the playback state, errors), bounded to {@value #MAX_LINES} lines of at most {@value #MAX_LINE} characters, each line cleaned. A URL
 * in a line is shortened to its host and path ({@link #shorten}): no user information, no query string, no fragment. The trace is for
 * the screen and the clipboard; it never goes to the log. Pure and thread safe.
 */
public final class FCastSheetTrace {

    public static final int MAX_LINES = 50;
    public static final int MAX_LINE = 160;

    private final LinkedList<String> lines = new LinkedList<>();

    /** Adds a line (cleaned and cut); the oldest line goes when the trace is full. */
    public synchronized void add(String line) {
        String clean = FCastText.clean(shortenUrls(line == null ? "" : line), MAX_LINE);
        if (clean == null || clean.isEmpty()) {
            return;
        }
        lines.add(clean);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    /** The lines, oldest first. */
    public synchronized List<String> lines() {
        return new ArrayList<>(lines);
    }

    /** The trace as one text for the clipboard. */
    public synchronized String text() {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** Replaces every http or https address in the text by its shortened form. */
    static String shortenUrls(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int http = indexOfUrl(text, i);
            if (http < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, http);
            int end = http;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '"' && text.charAt(end) != '\'') {
                end++;
            }
            out.append(shorten(text.substring(http, end)));
            i = end;
        }
        return out.toString();
    }

    private static int indexOfUrl(String text, int from) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int http = lower.indexOf("http://", from);
        int https = lower.indexOf("https://", from);
        if (http < 0) {
            return https;
        }
        return https < 0 ? http : Math.min(http, https);
    }

    /** @return {@code scheme://host:port/path} of an http or https address, without user information, query and fragment; other text unchanged */
    public static String shorten(String url) {
        if (url == null) {
            return "";
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        int schemeEnd = lower.startsWith("https://") ? 8 : lower.startsWith("http://") ? 7 : -1;
        if (schemeEnd < 0) {
            return url;
        }
        String rest = url.substring(schemeEnd);
        int cut = rest.length();
        for (char stop : new char[]{'?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        rest = rest.substring(0, cut);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        return url.substring(0, schemeEnd) + authority + path;
    }
}
