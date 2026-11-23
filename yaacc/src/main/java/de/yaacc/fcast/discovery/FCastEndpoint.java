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
package de.yaacc.fcast.discovery;

import java.net.InetAddress;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The address of an FCast receiver: a host (a name or an IP literal) and a TCP port, default {@value #DEFAULT_PORT}. Immutable.
 * A thin wrapper of the checked parser that the Snapcast discovery already has ({@link SnapcastEndpoint}, decision D15): strict
 * {@code host[:port]} (no scheme, path, white space, zone id or non-ASCII character), nothing is resolved while parsing, an IP
 * literal has one canonical form so that the same receiver found by mDNS and typed in by hand is one receiver. The text of an
 * endpoint is an address of the user's network: show it, never log it.
 */
public final class FCastEndpoint {
    /** The TCP port of FCast. */
    public static final int DEFAULT_PORT = 46899;

    private final SnapcastEndpoint inner;
    private final String text;

    private FCastEndpoint(SnapcastEndpoint inner) {
        this.inner = inner;
        this.text = inner.toString();
    }

    /** @return the endpoint of {@code host[:port]} (the port defaults to {@value #DEFAULT_PORT}), or null if the text is not valid */
    public static FCastEndpoint parse(String text) {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text, DEFAULT_PORT);
        return endpoint == null ? null : new FCastEndpoint(endpoint);
    }

    /** @return the endpoint for exactly this canonical text ({@link #toString()} of an endpoint), or null (also for another writing) */
    public static FCastEndpoint parseCanonical(String text) {
        FCastEndpoint endpoint = parse(text);
        return endpoint != null && endpoint.text.equals(text) ? endpoint : null;
    }

    /** @return the endpoint of a resolved address, or null if the address is not usable (unspecified, multicast, broadcast) */
    public static FCastEndpoint fromAddress(InetAddress address, int port) {
        if (address == null || port < 1 || port > 65535) {
            return null;
        }
        SnapcastEndpoint endpoint = SnapcastEndpoint.fromAddress(address, port);
        return endpoint == null ? null : new FCastEndpoint(endpoint);
    }

    public String getHost() {
        return inner.getHost();
    }

    public int getPort() {
        return inner.getPort();
    }

    /** The address if the host is an IP literal, null for a host name (nothing is looked up here). */
    public InetAddress getAddress() {
        return inner.getAddress();
    }

    /** The canonical text {@code host:port} ({@code [ipv6]:port} for IPv6). */
    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FCastEndpoint && text.equals(((FCastEndpoint) other).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
