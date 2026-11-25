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
package de.yaacc.sendspin.hub;

import java.util.Objects;

/** Where a Sendspin player listens: host, port and the WebSocket path it advertises (default {@code /sendspin}). */
public final class Endpoint {

    public static final String DEFAULT_PATH = "/sendspin";
    public static final int DEFAULT_PORT = 8928;

    /** The port of a player that connected to this phone: it has no address of its own to dial. */
    public static final int INBOUND_PORT = 0;
    public static final String INBOUND_PATH = "/inbound";

    private final String host;
    private final int port;
    private final String path;

    public Endpoint(String host, int port, String path) {
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.path = path == null || path.isEmpty() ? DEFAULT_PATH : path;
    }

    /** The identity of a player that connected to this phone (client-initiated mode): the address it came from. */
    public static Endpoint inbound(String host) {
        return new Endpoint(host, INBOUND_PORT, INBOUND_PATH);
    }

    /** As {@link #inbound(String)}, for one of several players behind one address: the port it connected from is part of the identity. */
    public static Endpoint inbound(String host, int remotePort) {
        return new Endpoint(host, INBOUND_PORT, INBOUND_PATH + "/" + remotePort);
    }

    /** True for a player that connected to this phone: it is never dialed, it has to come back by itself. */
    public boolean isInbound() {
        return port == INBOUND_PORT;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String path() {
        return path;
    }

    /** True for an address the specification says a server should not connect to when it was advertised on the network. */
    public boolean isLoopbackOrUnspecified() {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("0.0.0.0") || h.equals("::");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Endpoint)) {
            return false;
        }
        Endpoint other = (Endpoint) o;
        return host.equals(other.host) && port == other.port && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, path);
    }

    @Override
    public String toString() {
        return host + ":" + port + path;
    }
}
