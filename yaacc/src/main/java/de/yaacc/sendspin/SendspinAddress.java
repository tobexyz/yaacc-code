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
package de.yaacc.sendspin;

import de.yaacc.sendspin.hub.Endpoint;

/**
 * What a user types for a player and the receiver id of the lists. Accepted: {@code host}, {@code host:port}, {@code [ipv6]} and
 * {@code [ipv6]:port}; the port defaults to 8928, the path is always {@code /sendspin}. Nothing else: no scheme, no path, no user
 * information, no spaces. Pure.
 */
public final class SendspinAddress {

    /** The prefix of every receiver id of the lists. */
    public static final String ID_PREFIX = "sendspin:";
    private static final int MAX_HOST = 253;

    private SendspinAddress() {
    }

    /** The prefix of the receiver id of a player that connected to this phone. */
    public static final String INBOUND_ID_PREFIX = "sendspin-in:";

    /** The prefix of the receiver ids of the Sendspin servers (cards of the controller). */
    public static final String SERVER_ID_PREFIX = "sendspin-server:";
    /** The receiver id of the card of this phone as a Sendspin player (there is one). */
    public static final String SELF_ID = "sendspin-self:phone";

    public static boolean isSelfId(String id) {
        return SELF_ID.equals(id);
    }

    /** The port a Sendspin server listens on by default (the player's is 8928). */
    public static final int DEFAULT_SERVER_PORT = 8927;

    /** @return the endpoint of a player, or null if the text is not an address this app accepts */
    public static Endpoint parse(String text) {
        return parse(text, Endpoint.DEFAULT_PORT);
    }

    /** @return the endpoint of a server (default port 8927), or null */
    public static Endpoint parseServer(String text) {
        return parse(text, DEFAULT_SERVER_PORT);
    }

    /** The receiver id of a server card. */
    public static String serverIdOf(Endpoint endpoint) {
        return SERVER_ID_PREFIX + format(endpoint);
    }

    public static boolean isServerId(String id) {
        return id != null && id.startsWith(SERVER_ID_PREFIX);
    }

    /** @return the endpoint of a server card id, or null if the id is not one of ours or does not parse */
    public static Endpoint serverEndpointOf(String id) {
        return isServerId(id) ? parseServer(id.substring(SERVER_ID_PREFIX.length())) : null;
    }

    static Endpoint parse(String text, int defaultPort) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.length() > MAX_HOST + 8) {
            return null;
        }
        String host;
        String portText = null;
        if (t.startsWith("[")) {
            int close = t.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = t.substring(1, close);
            String rest = t.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return null;
                }
                portText = rest.substring(1);
            }
            if (!isIpv6(host)) {
                return null;
            }
        } else {
            int colon = t.indexOf(':');
            if (colon >= 0) {
                if (t.indexOf(':', colon + 1) >= 0) {
                    return null; // an IPv6 literal needs brackets
                }
                host = t.substring(0, colon);
                portText = t.substring(colon + 1);
            } else {
                host = t;
            }
            if (!isHostName(host)) {
                return null;
            }
        }
        int port = defaultPort;
        if (portText != null) {
            if (portText.isEmpty() || portText.length() > 5) {
                return null;
            }
            for (int i = 0; i < portText.length(); i++) {
                if (portText.charAt(i) < '0' || portText.charAt(i) > '9') {
                    return null;
                }
            }
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                return null;
            }
        }
        return new Endpoint(host, port, Endpoint.DEFAULT_PATH);
    }

    private static boolean isIpv6(String host) {
        if (host.length() < 2 || host.indexOf(':') < 0) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHostName(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return host.charAt(0) != '.' && host.charAt(0) != '-' && host.charAt(host.length() - 1) != '-';
    }

    /** The text of an endpoint as it is stored and shown: {@code host:port}, an IPv6 host in brackets. */
    public static String format(Endpoint endpoint) {
        String host = endpoint.host();
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return endpoint.isInbound() ? shown : shown + ":" + endpoint.port();
    }

    /** The receiver id of the lists for an endpoint. */
    public static String idOf(Endpoint endpoint) {
        if (endpoint.isInbound()) {
            String path = endpoint.path();
            String port = path.startsWith(Endpoint.INBOUND_PATH + "/") ? "#" + path.substring(Endpoint.INBOUND_PATH.length() + 1) : "";
            return INBOUND_ID_PREFIX + format(endpoint) + port;
        }
        return ID_PREFIX + format(endpoint);
    }

    public static boolean isSendspinId(String id) {
        return id != null && (id.startsWith(ID_PREFIX) || id.startsWith(INBOUND_ID_PREFIX));
    }

    /** @return the endpoint of a receiver id, or null if the id is not one of ours or does not parse */
    public static Endpoint endpointOf(String id) {
        if (id != null && id.startsWith(INBOUND_ID_PREFIX)) {
            String text = id.substring(INBOUND_ID_PREFIX.length());
            int hash = text.lastIndexOf('#');
            Endpoint host = parse(hash >= 0 ? text.substring(0, hash) : text);
            if (host == null) {
                return null;
            }
            if (hash < 0) {
                return Endpoint.inbound(host.host());
            }
            try {
                int port = Integer.parseInt(text.substring(hash + 1));
                return port >= 1 && port <= 65535 ? Endpoint.inbound(host.host(), port) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return id != null && id.startsWith(ID_PREFIX) ? parse(id.substring(ID_PREFIX.length())) : null;
    }
}
