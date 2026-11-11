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
package de.yaacc.multiroom.discovery;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * The control endpoint of a snapserver: a host (a name or an IP literal) and a TCP port. Immutable.
 *
 * <p>{@link #parse} is the one place where a manual entry {@code host[:port]} is checked, and it is strict: a host
 * name (letters, digits and hyphens in dot-separated labels, the last label not all digits) or an IP literal (an IPv6
 * literal in brackets when a port follows), a port of 1 to 65535, and nothing else (no scheme, path, query, user
 * info, white space, zone id or non-ASCII character). Nothing is resolved: the literals are parsed here by hand, so
 * that no input can cause a name lookup (Java would read {@code 1.2.3} as an IPv4 address and an unknown text as a
 * host name to look up). An unspecified, multicast or broadcast literal is refused. A public address is allowed for a
 * manual entry (the user chose it); the range filter for discovered servers is {@link SnapcastAddressFilter}.
 *
 * <p>An IP literal is kept in one canonical form (IPv4 dotted, IPv6 as eight hexadecimal groups without
 * compression), so that two writings of one address are one endpoint, and the same endpoint found by mDNS and typed
 * by hand is one server. The text of an endpoint is an address of the user's network: show it, never log it.
 */
public final class SnapcastEndpoint {

    /** The port of the raw TCP control interface of a snapserver. */
    public static final int DEFAULT_CONTROL_PORT = 1705;

    private static final int MAX_TEXT_LENGTH = 261; // 253 for a name, brackets, colon and 5 digits, with some room
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;

    private final String host;
    private final int port;
    private final InetAddress address;

    private SnapcastEndpoint(String host, int port, InetAddress address) {
        this.host = host;
        this.port = port;
        this.address = address;
    }

    /**
     * Reads a manual entry.
     *
     * @return the endpoint, or null when the text is not a valid {@code host[:port]}
     */
    public static SnapcastEndpoint parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return null; // white space, control characters, non-ASCII
            }
        }
        String hostPart;
        String portPart = null;
        if (text.charAt(0) == '[') {
            int close = text.indexOf(']');
            if (close < 0) {
                return null;
            }
            hostPart = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':') {
                    return null;
                }
                portPart = rest.substring(1);
            }
            if (hostPart.indexOf(':') < 0) {
                return null; // brackets are for IPv6 literals only
            }
        } else {
            int firstColon = text.indexOf(':');
            if (firstColon < 0) {
                hostPart = text;
            } else if (firstColon == text.lastIndexOf(':')) {
                hostPart = text.substring(0, firstColon);
                portPart = text.substring(firstColon + 1);
            } else {
                hostPart = text; // a bare IPv6 literal has no port
            }
        }
        int port = DEFAULT_CONTROL_PORT;
        if (portPart != null) {
            port = parsePort(portPart);
            if (port < 0) {
                return null;
            }
        }
        if (hostPart.indexOf(':') >= 0) {
            byte[] raw = parseIpv6(hostPart);
            return raw == null ? null : ofLiteral(raw, port, null);
        }
        byte[] ipv4 = parseIpv4(hostPart);
        if (ipv4 != null) {
            return ofLiteral(ipv4, port, null);
        }
        if (!isHostName(hostPart)) {
            return null;
        }
        return new SnapcastEndpoint(hostPart.toLowerCase(java.util.Locale.ROOT), port, null);
    }

    /**
     * The endpoint of an address that was found on the network (the result of a service resolution). The address is
     * kept and is what a connection uses: no name is resolved later. The caller checks the range with
     * {@link SnapcastAddressFilter}.
     *
     * @return null for no address or a port outside 1 to 65535
     */
    public static SnapcastEndpoint fromAddress(InetAddress address, int port) {
        if (address == null || port < 1 || port > 65535) {
            return null;
        }
        return ofLiteral(address.getAddress(), port, address);
    }

    private static SnapcastEndpoint ofLiteral(byte[] raw, int port, InetAddress keep) {
        try {
            // from the bytes only: this never looks anything up
            InetAddress address = InetAddress.getByAddress(raw);
            if (address.isAnyLocalAddress() || address.isMulticastAddress() || isBroadcast(raw)) {
                return null;
            }
            return new SnapcastEndpoint(canonicalText(address), port, keep != null ? keep : address);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isBroadcast(byte[] raw) {
        if (raw.length != 4) {
            return false;
        }
        for (byte b : raw) {
            if (b != (byte) 0xff) {
                return false;
            }
        }
        return true;
    }

    /** IPv4 dotted, IPv6 as eight hexadecimal groups (no compression, no zone). */
    private static String canonicalText(InetAddress address) {
        byte[] raw = address.getAddress();
        StringBuilder text = new StringBuilder();
        if (raw.length == 4) {
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    text.append('.');
                }
                text.append(raw[i] & 0xff);
            }
            return text.toString();
        }
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) {
                text.append(':');
            }
            text.append(Integer.toHexString(((raw[i] & 0xff) << 8) | (raw[i + 1] & 0xff)));
        }
        return text.toString();
    }

    /** @return the port, or -1 when the text is not 1 to 5 digits of a value in 1..65535 */
    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value >= 1 && value <= 65535 ? value : -1;
    }

    /** @return 4 bytes, or null when the text is not a dotted quad of four decimal numbers 0..255 without leading zeros */
    private static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] raw = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return null; // no leading zero: some tools read 010 as octal, so the text could mean two hosts
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            raw[i] = (byte) value;
        }
        return raw;
    }

    /** @return 16 bytes, or null when the text is not an IPv6 literal (no zone id; a trailing IPv4 part is allowed) */
    private static byte[] parseIpv6(String text) {
        if (text.isEmpty() || text.indexOf('%') >= 0) {
            return null;
        }
        int compress = text.indexOf("::");
        if (compress >= 0 && text.indexOf("::", compress + 1) >= 0) {
            return null; // ":::" or a second "::"
        }
        String head = compress < 0 ? text : text.substring(0, compress);
        String tail = compress < 0 ? "" : text.substring(compress + 2);
        int[] headWords = new int[8];
        int[] tailWords = new int[8];
        int headCount = readWords(head, headWords, compress < 0);
        int tailCount = compress < 0 ? 0 : readWords(tail, tailWords, true);
        if (headCount < 0 || tailCount < 0) {
            return null;
        }
        int total = headCount + tailCount;
        if (compress < 0 ? total != 8 : total > 7) {
            return null;
        }
        int[] words = new int[8];
        System.arraycopy(headWords, 0, words, 0, headCount);
        System.arraycopy(tailWords, 0, words, 8 - tailCount, tailCount);
        byte[] raw = new byte[16];
        for (int i = 0; i < 8; i++) {
            raw[2 * i] = (byte) (words[i] >> 8);
            raw[2 * i + 1] = (byte) words[i];
        }
        return raw;
    }

    /**
     * Reads colon-separated groups of 1 to 4 hexadecimal digits into {@code words}; the last group may be a dotted
     * IPv4 address (two words) when {@code allowIpv4} is set.
     *
     * @return the number of words, or -1 for a malformed text
     */
    private static int readWords(String text, int[] words, boolean allowIpv4) {
        if (text.isEmpty()) {
            return 0;
        }
        String[] groups = text.split(":", -1);
        int count = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) {
                return -1;
            }
            if (group.indexOf('.') >= 0) {
                byte[] ipv4 = i == groups.length - 1 && allowIpv4 ? parseIpv4(group) : null;
                if (ipv4 == null || count + 2 > words.length) {
                    return -1;
                }
                words[count++] = ((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff);
                words[count++] = ((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff);
                continue;
            }
            if (group.length() > 4 || count >= words.length) {
                return -1;
            }
            int value = 0;
            for (int j = 0; j < group.length(); j++) {
                int digit = Character.digit(group.charAt(j), 16);
                if (digit < 0) {
                    return -1;
                }
                value = value * 16 + digit;
            }
            words[count++] = value;
        }
        return count;
    }

    private static boolean isHostName(String text) {
        if (text.isEmpty() || text.length() > MAX_HOST_LENGTH) {
            return false;
        }
        String[] labels = text.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
                if (!ok) {
                    return false;
                }
            }
        }
        // an all-numeric last label would make "1.2.3" or "12345" a number that resolvers read as an address
        String last = labels[labels.length - 1];
        for (int i = 0; i < last.length(); i++) {
            if (last.charAt(i) < '0' || last.charAt(i) > '9') {
                return true;
            }
        }
        return false;
    }

    /** The host: a lower-case name, or the canonical text of an IP literal. */
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** The address of an IP literal or of a discovered server; null for a host name (it is resolved by the caller). */
    public InetAddress getAddress() {
        return address;
    }

    /** The key of the server at this endpoint. */
    public SnapcastServerKey toKey() {
        return SnapcastServerKey.of(this);
    }

    /** {@code host:port}, an IPv6 literal in brackets; reads back through {@link #parse}. */
    @Override
    public String toString() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastEndpoint)) {
            return false;
        }
        SnapcastEndpoint that = (SnapcastEndpoint) other;
        return port == that.port && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{host, port});
    }
}
