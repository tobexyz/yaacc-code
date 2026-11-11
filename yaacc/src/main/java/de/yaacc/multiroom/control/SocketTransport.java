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
package de.yaacc.multiroom.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A plain TCP connection to the control port of a server (default 1705). No TLS and no authentication: it assumes a
 * trusted local network.
 */
public final class SocketTransport implements SnapcastTransport {

    /** The default port of the raw TCP control interface. */
    public static final int DEFAULT_PORT = 1705;

    private final String host;
    private final InetAddress address;
    private final int port;

    /**
     * Connects to an address that is known already (a discovered server, or a name that was resolved by the caller
     * with a time limit): nothing is looked up. This is the constructor the app uses.
     */
    public SocketTransport(InetAddress address, int port) {
        if (address == null) {
            throw new IllegalArgumentException("no address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        this.host = null;
        this.address = address;
        this.port = port;
    }

    public SocketTransport(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("no host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        this.host = host.trim();
        this.address = null;
        this.port = port;
    }

    /**
     * Opens the connection. A host <b>name</b> is resolved by the system resolver without a timeout of ours and
     * {@code close()} can not end that wait; the calls are safe (they end at their deadline), the reader thread is
     * held until the resolver returns. Prefer the address when it is known (a discovered server has one).
     */
    @Override
    public Connection open(int connectTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(address != null ? new InetSocketAddress(address, port) : new InetSocketAddress(host, port),
                    connectTimeoutMillis);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the connect failed already
            }
            throw e;
        }
        return new SocketConnection(socket);
    }

    private static final class SocketConnection implements Connection {
        private final Socket socket;

        SocketConnection(Socket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void setReadTimeout(int millis) throws IOException {
            socket.setSoTimeout(millis);
        }

        @Override
        public InetAddress getPeerAddress() {
            return socket.getInetAddress();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }
}
