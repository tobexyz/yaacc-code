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
package de.yaacc.multiroom;

import java.io.IOException;
import java.net.InetAddress;

import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.control.SocketTransport;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastResolver;

/**
 * The transport of a session: resolves the endpoint with a time limit ({@link SnapcastResolver}; nothing is looked up
 * for a discovered server or an IP literal) and then connects to that {@link InetAddress} with a
 * {@link SocketTransport}. The name is resolved again on every attempt (an address may change), and the control
 * connection never does an unbounded lookup on the reader thread.
 */
public final class ResolvingTransport implements SnapcastTransport {

    private final SnapcastEndpoint endpoint;
    private final SnapcastResolver resolver;

    public ResolvingTransport(SnapcastEndpoint endpoint, SnapcastResolver resolver) {
        this.endpoint = endpoint;
        this.resolver = resolver;
    }

    @Override
    public Connection open(int connectTimeoutMillis) throws IOException {
        InetAddress address = resolver.resolve(endpoint);
        return new SocketTransport(address, endpoint.getPort()).open(connectTimeoutMillis);
    }
}
