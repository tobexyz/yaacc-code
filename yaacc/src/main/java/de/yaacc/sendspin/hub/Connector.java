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

import de.yaacc.sendspin.session.WsConnection;
import de.yaacc.sendspin.session.WsHandshake;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

/** Opens the WebSocket connection to a player; a seam so that a test connects to a fake player instead of a socket. */
public interface Connector {

    /** An open connection after the WebSocket handshake. */
    interface Link extends Closeable {
        WsConnection ws();
    }

    /** @throws IOException if the player can not be reached or does not accept the upgrade (the text names no address) */
    Link open(Endpoint endpoint, SecureRandom random) throws IOException;

    /** The real thing: a TCP connection with timeouts. */
    final class Sockets implements Connector {
        private static final int CONNECT_TIMEOUT_MILLIS = 5000;
        /** Each read waits this long; the connection counts silence over several of them. */
        public static final int READ_TIMEOUT_MILLIS = 5000;
        private static final int MAX_MESSAGE_BYTES = 1 << 20;

        @Override
        public Link open(Endpoint endpoint, SecureRandom random) throws IOException {
            final Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MILLIS);
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                socket.setTcpNoDelay(true);
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                WsHandshake.connect(in, socket.getOutputStream(), endpoint.host(), endpoint.port(), endpoint.path(), random);
                final WsConnection ws = new WsConnection(in, socket.getOutputStream(), true, MAX_MESSAGE_BYTES, random);
                return new Link() {
                    @Override
                    public WsConnection ws() {
                        return ws;
                    }

                    @Override
                    public void close() throws IOException {
                        socket.close();
                    }
                };
            } catch (IOException | RuntimeException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing more to do
                }
                if (e instanceof IOException) {
                    throw new IOException("connection failed");
                }
                throw e;
            }
        }
    }
}
