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
package de.yaacc.browser;

/**
 * Decides which shared text may become a playable URI. Plain Java so it can be unit tested on
 * the JVM.
 * <p>
 * The text comes from another app and is untrusted. With the proxy option on, a {@code file://}
 * or bare-path URI would be served to the whole LAN through the local proxy, so only {@code http}
 * and {@code https} links are accepted. Apps share a link inside a text ("Look at this https://...")
 * as often as alone, so the first link is picked out of the text: it starts with {@code http://} or
 * {@code https://} (compared as ASCII, no locale or Unicode case folding) at the start of the text or
 * after white space or an opening bracket or quote, it ends at the first white space or control
 * character, trailing punctuation is not part of it, and it has a host. Nothing after the link is used.
 */
public final class ShareUriPolicy {

    /** Longer texts are dropped, not truncated. */
    public static final int MAX_TEXT_LENGTH = 2048;

    private static final int MAX_HOST_LENGTH = 253;

    private ShareUriPolicy() {
    }

    /**
     * @param text the shared text, may be null
     * @return true if the text contains a usable http or https link, see {@link #extractUrl(String)}
     */
    public static boolean isAllowed(String text) {
        return extractUrl(text) != null;
    }

    /**
     * @param text the shared text, may be null
     * @return the first http or https link of the text, or null if the text is empty, longer than
     * {@link #MAX_TEXT_LENGTH} or has no link with a host
     */
    public static String extractUrl(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!startsLinkAt(text, i)) {
                continue;
            }
            String candidate = trimTrailing(token(text, i));
            if (hostOf(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The host of an http or https link as the picker shows it, in lower case, or null if the link has no
     * usable host. A user name in front of it ({@code trusted.com@evil.example}) is not the host.
     *
     * @param url a link such as {@link #extractUrl(String)} returns, may be null
     */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        int schemeEnd = hasPrefix(url, "https://") ? 8 : hasPrefix(url, "http://") ? 7 : -1;
        if (schemeEnd < 0) {
            return null;
        }
        int end = url.length();
        for (int i = schemeEnd; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(schemeEnd, end);
        int at = authority.lastIndexOf('@');
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        String host;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = hostPort.substring(1, close);
            String rest = hostPort.substring(close + 1);
            if (!rest.isEmpty() && !isPort(rest)) {
                return null;
            }
            return host.isEmpty() || !isIpv6Literal(host) ? null : host.toLowerCase(java.util.Locale.ROOT);
        }
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            if (!isPort(hostPort.substring(colon))) {
                return null;
            }
            host = hostPort.substring(0, colon);
        } else {
            host = hostPort;
        }
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH || host.startsWith(".") || host.endsWith(".")) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) {
                return null;
            }
        }
        return host.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isPort(String colonAndDigits) {
        if (colonAndDigits.length() < 2 || colonAndDigits.length() > 6 || colonAndDigits.charAt(0) != ':') {
            return false;
        }
        for (int i = 1; i < colonAndDigits.length(); i++) {
            char c = colonAndDigits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Literal(String host) {
        boolean colon = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == ':') {
                colon = true;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.')) {
                return false;
            }
        }
        return colon;
    }

    private static boolean startsLinkAt(String text, int i) {
        if (!(hasPrefix(text, i, "http://") || hasPrefix(text, i, "https://"))) {
            return false;
        }
        if (i == 0) {
            return true;
        }
        char before = text.charAt(i - 1);
        return Character.isWhitespace(before) || before == '(' || before == '<' || before == '[' || before == '"'
                || before == '\'' || before == ':';
    }

    private static boolean hasPrefix(String text, String lowerCasePrefix) {
        return hasPrefix(text, 0, lowerCasePrefix);
    }

    /** ASCII-only, case-insensitive prefix test at {@code from}. */
    private static boolean hasPrefix(String text, int from, String lowerCasePrefix) {
        if (text.length() - from < lowerCasePrefix.length()) {
            return false;
        }
        for (int i = 0; i < lowerCasePrefix.length(); i++) {
            char c = text.charAt(from + i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c != lowerCasePrefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** From {@code start} up to the first white space or control character. */
    private static String token(String text, int start) {
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                break;
            }
            end++;
        }
        return text.substring(start, end);
    }

    /** Punctuation after a link in a sentence is not part of it; a closing bracket only if none was opened. */
    private static String trimTrailing(String link) {
        String result = link;
        while (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            boolean strip = last == '.' || last == ',' || last == ';' || last == '!' || last == '?' || last == '"'
                    || last == '\'' || last == '>' || last == ']'
                    || (last == ')' && result.indexOf('(') < 0);
            if (!strip) {
                break;
            }
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
