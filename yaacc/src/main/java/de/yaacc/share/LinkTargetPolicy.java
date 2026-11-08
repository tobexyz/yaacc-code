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

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Which http/https link targets the share intake accepts. Plain Java, no {@code android.*}.
 * <p>
 * A shared link is fetched by the phone (through the media proxy) or played from the phone, so a
 * hostile link must not point the phone at itself or at link-local addresses (cloud metadata
 * style endpoints, the loopback services of other apps). Private LAN ranges stay allowed: the
 * typical share is a NAS or a media server.
 * <p>
 * The host is taken from the text by hand: no {@code URL}, {@code URI} or {@code InetAddress}, so
 * nothing is resolved and no DNS request is made. Rejected (fail closed):
 * <ul>
 * <li>not http/https (see {@link HttpLinkTarget#isFetchable(String)});</li>
 * <li>an empty host, an empty label, a bad port, a backslash in the authority, a percent sign in
 * the host, characters that are not valid in a host name, a host over 253 chars;</li>
 * <li>IPv4 loopback {@code 127.0.0.0/8}, {@code 0.0.0.0/8} (includes the unspecified address),
 * link-local {@code 169.254.0.0/16}; IPv6 {@code ::1}, {@code ::}, {@code fe80::/10}, and IPv4
 * addresses hidden in IPv6 (mapped {@code ::ffff:a.b.c.d}, the deprecated compatible form
 * {@code ::a.b.c.d}, NAT64 {@code 64:ff9b::a.b.c.d}); a zone id is rejected as a percent sign;</li>
 * <li>the host {@code localhost} and every {@code *.localhost}, any case, with or without a
 * trailing dot;</li>
 * <li>every address of the phone itself ({@code ownAddresses}, normalized by
 * {@link ProxyAccessPolicy#normalizeAll}; host names in that set are ignored);</li>
 * <li>every IPv4 spelling that is not strict dotted decimal, because it can encode loopback:
 * {@code 2130706433}, {@code 0177.0.0.1}, {@code 0x7f.1}, {@code 127.1}. A host whose last label
 * is numeric (decimal digits, or {@code 0x} and hex digits) must be a strict dotted-decimal
 * address (four parts, no leading zeros), else it is rejected;</li>
 * <li>Unicode look-alikes: the host is folded with NFKC (full-width digits, the ideographic full
 * stop and similar become ASCII) and, because IDNA drops invisible characters, the ASCII-only part
 * of a non-ASCII host is checked as well. A real international name stays allowed.</li>
 * </ul>
 * The authority is what follows the LAST {@code @} before the first {@code /}, {@code ?} or
 * {@code #}, so {@code http://good@127.0.0.1/} is loopback and {@code http://127.0.0.1:80@evil/}
 * is {@code evil}.
 */
public final class LinkTargetPolicy {

    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_PORT_DIGITS = 5;
    private static final int MAX_PORT = 65535;

    private LinkTargetPolicy() {
    }

    /**
     * @param url          the link as text, may be null
     * @param ownAddresses the phone's own IP addresses as text (brackets, zone ids and mapped forms
     *                     are normalized); may be null
     * @return true if the link may be shared
     */
    public static boolean isAllowedTarget(String url, Set<String> ownAddresses) {
        if (url == null || !HttpLinkTarget.isFetchable(url)) {
            return false;
        }
        String authority = authorityOf(url);
        if (authority == null || authority.indexOf('\\') >= 0) {
            return false;
        }
        String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
        Set<String> own = ProxyAccessPolicy.normalizeAll(ownAddresses);
        if (hostPort.startsWith("[")) {
            return isAllowedBracketedHost(hostPort, own);
        }
        return isAllowedNamedOrIpv4Host(hostPort, own);
    }

    /** The text between {@code scheme://} and the first {@code /}, {@code ?} or {@code #}. */
    private static String authorityOf(String url) {
        int start = url.indexOf("//");
        if (start < 0) {
            return null;
        }
        start += 2;
        int end = start;
        while (end < url.length()) {
            char c = url.charAt(end);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            end++;
        }
        return url.substring(start, end);
    }

    // ---- bracketed IPv6 host

    private static boolean isAllowedBracketedHost(String hostPort, Set<String> own) {
        int close = hostPort.indexOf(']');
        if (close < 0) {
            return false;
        }
        String inner = hostPort.substring(1, close);
        if (inner.indexOf(':') < 0 || inner.indexOf('%') >= 0 || inner.indexOf('[') >= 0
                || !isValidPortSuffix(hostPort.substring(close + 1))) {
            return false;
        }
        String normalized = ProxyAccessPolicy.normalize(inner);
        if (normalized == null) {
            return false;
        }
        if (normalized.indexOf(':') < 0) {
            // an IPv4-mapped address came back as plain IPv4 text
            return !isForbiddenIpv4(normalized, own);
        }
        return !isForbiddenIpv6(normalized, own);
    }

    private static boolean isForbiddenIpv6(String normalized, Set<String> own) {
        if (own.contains(normalized)) {
            return true;
        }
        int[] g = groupsOf(normalized);
        if (g == null) {
            return true;
        }
        boolean firstSixZero = g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0 && g[5] == 0;
        if (firstSixZero && g[6] == 0 && g[7] <= 1) {
            return true; // :: and ::1
        }
        if ((g[0] & 0xffc0) == 0xfe80) {
            return true; // fe80::/10
        }
        boolean nat64 = g[0] == 0x64 && g[1] == 0xff9b && g[2] == 0 && g[3] == 0 && g[4] == 0 && g[5] == 0;
        if (firstSixZero || nat64) {
            String embedded = (g[6] >> 8) + "." + (g[6] & 0xff) + "." + (g[7] >> 8) + "." + (g[7] & 0xff);
            return isForbiddenIpv4(embedded, own);
        }
        return false;
    }

    /** The eight groups of a normalized (uncompressed, lower-case) IPv6 text, or null. */
    private static int[] groupsOf(String normalized) {
        String[] parts = normalized.split(":", -1);
        if (parts.length != 8) {
            return null;
        }
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            try {
                groups[i] = Integer.parseInt(parts[i], 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return groups;
    }

    // ---- host names and IPv4

    private static boolean isAllowedNamedOrIpv4Host(String hostPort, Set<String> own) {
        int colon = hostPort.indexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        if (colon >= 0 && !isValidPortSuffix(hostPort.substring(colon))) {
            return false;
        }
        if (host.isEmpty() || host.indexOf('%') >= 0) {
            return false;
        }
        String folded = Normalizer.normalize(host, Normalizer.Form.NFKC).replace('\u3002', '.');
        if (!isValidHostSyntax(folded)) {
            return false;
        }
        if (isForbiddenHost(folded, own)) {
            return false;
        }
        // IDNA drops invisible characters, so what is left of the ASCII part must not be forbidden either
        String asciiOnly = asciiPart(folded);
        return asciiOnly.length() == folded.length() || !isForbiddenHost(asciiOnly, own);
    }

    /** Letters, digits, '-', '_' and '.' (non-ASCII passes), no empty label, within the length limit. */
    private static boolean isValidHostSyntax(String host) {
        String name = stripOneTrailingDot(host);
        if (name.isEmpty() || name.length() > MAX_HOST_LENGTH) {
            return false;
        }
        boolean labelStart = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (labelStart) {
                    return false;
                }
                labelStart = true;
                continue;
            }
            labelStart = false;
            boolean ok = c > 0x7f || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return !labelStart;
    }

    private static boolean isForbiddenHost(String host, Set<String> own) {
        String name = stripOneTrailingDot(host).toLowerCase(Locale.ROOT);
        if (name.equals("localhost") || name.endsWith(".localhost")) {
            return true;
        }
        if (isNumericLabel(lastLabel(name))) {
            // looks like an IPv4 address in some spelling: only strict dotted decimal is understood
            String ipv4 = ProxyAccessPolicy.normalize(name);
            return ipv4 == null || isForbiddenIpv4(ipv4, own);
        }
        return false;
    }

    private static boolean isForbiddenIpv4(String dotted, Set<String> own) {
        if (own.contains(dotted)) {
            return true;
        }
        String[] octets = dotted.split("\\.", -1);
        if (octets.length != 4) {
            return true;
        }
        try {
            int a = Integer.parseInt(octets[0]);
            int b = Integer.parseInt(octets[1]);
            return a == 127 || a == 0 || (a == 169 && b == 254);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Decimal digits, or {@code 0x} followed by hex digits (possibly none): a number to an IPv4 parser. */
    private static boolean isNumericLabel(String label) {
        if (label.isEmpty()) {
            return false;
        }
        if (label.length() >= 2 && label.charAt(0) == '0' && (label.charAt(1) == 'x' || label.charAt(1) == 'X')) {
            for (int i = 2; i < label.length(); i++) {
                if (Character.digit(label.charAt(i), 16) < 0 || label.charAt(i) > 0x7f) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String lastLabel(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String stripOneTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static String asciiPart(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x7f) {
                sb.append(text.charAt(i));
            }
        }
        return sb.toString();
    }

    /** Empty, or {@code :} followed by up to five digits not above 65535 (an empty port is fine). */
    private static boolean isValidPortSuffix(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        if (suffix.charAt(0) != ':' || suffix.length() - 1 > MAX_PORT_DIGITS) {
            return false;
        }
        int port = 0;
        for (int i = 1; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            port = port * 10 + (c - '0');
        }
        return port <= MAX_PORT;
    }
}
