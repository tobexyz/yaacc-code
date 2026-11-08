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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a request to the media proxy may be served, from the remote address alone.
 * Plain Java so it can be unit tested on the JVM. The key and the expiry are checked elsewhere
 * ({@link ShareRegistry#lookup(String)}); this is only the IP step.
 * <p>
 * With {@code restrictToReceivers} off every remote address passes this step (even a missing
 * one). With it on, the remote address must equal one of the entry's allowed addresses after
 * {@link #normalize(String)}; anything unclear is denied (fail closed). Loopback is not special:
 * it passes only if the set contains it (the caller adds loopback and the own address when the
 * receiver is the local device). Names are never resolved, networks and wildcards never match.
 * <p>
 * Both sides are normalized before comparing: the remote address AND every element of the allowed
 * set, so the glue may add raw strings (mapped IPv6 vs IPv4, upper case, zone ids, whitespace);
 * elements that do not normalize to an IP literal are ignored (they never match).
 */
public final class ProxyAccessPolicy {

    private ProxyAccessPolicy() {
    }

    /**
     * @param restrictToReceivers the "Restrict the media proxy to the chosen receivers" setting
     * @param allowedAddresses    the entry's allowed receiver addresses, may be null (denies
     *                            when restricting)
     * @param remoteAddress       the request's remote address as text, may be null
     * @return true if the request may be served as far as the address is concerned
     */
    public static boolean isAllowed(boolean restrictToReceivers, Set<String> allowedAddresses,
                                    String remoteAddress) {
        if (!restrictToReceivers) {
            return true;
        }
        if (allowedAddresses == null || allowedAddresses.isEmpty()) {
            return false;
        }
        String remote = normalize(remoteAddress);
        if (remote == null) {
            return false;
        }
        for (String allowed : allowedAddresses) {
            if (remote.equals(normalize(allowed))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Brings an IP literal into the one form used for comparing:
     * <ul>
     * <li>surrounding whitespace and an IPv6 zone id ({@code %wlan0}) are removed;</li>
     * <li>IPv4 stays dotted decimal (four parts 0-255, no leading zeros, no short forms);</li>
     * <li>IPv6 becomes eight lowercase hex groups without leading zeros and without
     * {@code ::} compression, e.g. {@code ::1} becomes {@code 0:0:0:0:0:0:0:1};</li>
     * <li>an IPv4-mapped IPv6 address ({@code ::ffff:192.168.1.5} or {@code ::ffff:c0a8:105})
     * becomes the plain IPv4 text; the deprecated IPv4-compatible form ({@code ::192.168.1.5})
     * is NOT mapped.</li>
     * </ul>
     *
     * @return the normalized text, or null for null, empty, brackets, host names, a zone on an
     * IPv4 address and every other text that is not an IP literal
     */
    public static String normalize(String address) {
        if (address == null) {
            return null;
        }
        return normalizeTrimmed(trimAscii(address));
    }

    /**
     * Builds a ready-to-use allowed set from raw address texts (e.g. a URL host or
     * {@code InetAddress#getHostAddress()}): for each element surrounding whitespace is removed,
     * then ONE pair of surrounding brackets ({@code [::1]}, as in a URL host) is stripped, then
     * {@link #normalize(String)} is applied. Elements that are null or do not normalize to an IP
     * literal (host names, garbage, {@code [[::1]]}, a bracket pair around an IPv4 address with
     * a zone, ...) are dropped; duplicates collapse.
     * <p>
     * {@link #isAllowed(boolean, Set, String)} itself does NOT strip brackets (it only
     * normalizes, so a bracketed element of a raw set never matches): the glue passes its raw
     * texts through this method first.
     *
     * @param rawAddresses may be null
     * @return a new unmodifiable set, never null (null input gives an empty set)
     */
    public static Set<String> normalizeAll(Collection<String> rawAddresses) {
        if (rawAddresses == null) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String raw : rawAddresses) {
            if (raw == null) {
                continue;
            }
            String text = trimAscii(raw);
            if (text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
                text = text.substring(1, text.length() - 1);
            }
            String normalized = normalizeTrimmed(text);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    // ---- parsing by hand: no InetAddress, no name resolution, ever

    private static final int MAX_ADDRESS_LENGTH = 128;

    private static String trimAscii(String text) {
        int begin = 0;
        int end = text.length();
        while (begin < end && isWhitespace(text.charAt(begin))) {
            begin++;
        }
        while (end > begin && isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(begin, end);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000b';
    }

    /** Normalizes text that is already trimmed (no further trimming, no brackets). */
    private static String normalizeTrimmed(String text) {
        if (text.isEmpty() || text.length() > MAX_ADDRESS_LENGTH) {
            return null;
        }
        if (text.indexOf(':') < 0) {
            int[] octets = parseIpv4(text);
            return octets == null ? null : formatIpv4(octets);
        }
        String address = text;
        int percent = text.indexOf('%');
        if (percent >= 0) {
            if (!isValidZone(text, percent + 1)) {
                return null;
            }
            address = text.substring(0, percent);
        }
        int[] groups = parseIpv6(address);
        if (groups == null) {
            return null;
        }
        if (isIpv4Mapped(groups)) {
            return formatIpv4(new int[]{groups[6] >> 8, groups[6] & 0xff, groups[7] >> 8,
                    groups[7] & 0xff});
        }
        StringBuilder sb = new StringBuilder(39);
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
        }
        return sb.toString();
    }

    private static boolean isValidZone(String text, int from) {
        if (from >= text.length()) {
            return false;
        }
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '~';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(int[] groups) {
        for (int i = 0; i < 5; i++) {
            if (groups[i] != 0) {
                return false;
            }
        }
        return groups[5] == 0xffff;
    }

    private static String formatIpv4(int[] octets) {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    /** Strict dotted decimal: four parts of 1-3 digits, 0-255, no leading zeros. Null if invalid. */
    private static int[] parseIpv4(String text) {
        int[] octets = new int[4];
        int part = 0;
        int i = 0;
        int length = text.length();
        while (true) {
            int start = i;
            int value = 0;
            while (i < length && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                value = value * 10 + (text.charAt(i) - '0');
                i++;
                if (i - start > 3) {
                    return null;
                }
            }
            int digits = i - start;
            if (digits == 0 || value > 255 || (digits > 1 && text.charAt(start) == '0')) {
                return null;
            }
            octets[part++] = value;
            if (part == 4) {
                return i == length ? octets : null;
            }
            if (i >= length || text.charAt(i) != '.') {
                return null;
            }
            i++;
        }
    }

    /** Eight 16-bit groups, or null if the text is not an IPv6 literal without zone. */
    private static int[] parseIpv6(String text) {
        int compression = text.indexOf("::");
        if (compression >= 0 && text.indexOf("::", compression + 1) >= 0) {
            return null;
        }
        List<Integer> head = new ArrayList<>(8);
        List<Integer> tail = new ArrayList<>(8);
        if (compression < 0) {
            if (!collectGroups(text, head, true)) {
                return null;
            }
            if (head.size() != 8) {
                return null;
            }
        } else {
            if (!collectGroups(text.substring(0, compression), head, false)
                    || !collectGroups(text.substring(compression + 2), tail, true)) {
                return null;
            }
            if (head.size() + tail.size() > 7) {
                return null;
            }
        }
        int[] groups = new int[8];
        for (int i = 0; i < head.size(); i++) {
            groups[i] = head.get(i);
        }
        for (int i = 0; i < tail.size(); i++) {
            groups[8 - tail.size() + i] = tail.get(i);
        }
        return groups;
    }

    /**
     * Reads colon separated groups of 1-4 hex digits; the last one may be an embedded IPv4
     * address (two groups) if {@code allowIpv4} is set. An empty text has no groups.
     */
    private static boolean collectGroups(String text, List<Integer> out, boolean allowIpv4) {
        if (text.isEmpty()) {
            return true;
        }
        int start = 0;
        while (true) {
            int colon = text.indexOf(':', start);
            boolean last = colon < 0;
            String piece = text.substring(start, last ? text.length() : colon);
            if (last && piece.indexOf('.') >= 0) {
                int[] octets = allowIpv4 ? parseIpv4(piece) : null;
                if (octets == null) {
                    return false;
                }
                out.add((octets[0] << 8) | octets[1]);
                out.add((octets[2] << 8) | octets[3]);
                return true;
            }
            int group = parseHexGroup(piece);
            if (group < 0) {
                return false;
            }
            out.add(group);
            if (last) {
                return true;
            }
            start = colon + 1;
        }
    }

    /** 1-4 ASCII hex digits, or -1. */
    private static int parseHexGroup(String piece) {
        if (piece.isEmpty() || piece.length() > 4) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }
}
