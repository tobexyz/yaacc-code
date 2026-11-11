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
package de.yaacc.multiroom.feed;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The real {@link FeedConnection}: a TCP socket with a small send buffer and {@code TCP_NODELAY}. The small send
 * buffer matters: bytes in socket buffers delay a pause or a stop by bytes / bytes-per-second (the feed spike piled up
 * 39 s of audio in buffers when it did not pace and kept the default sizes).
 */
public final class SocketFeedConnection implements FeedConnection {

    /** Opens real sockets. */
    public static final Factory FACTORY = SocketFeedConnection::connect;

    private final Socket socket;
    private final OutputStream out;

    private SocketFeedConnection(Socket socket, OutputStream out) {
        this.socket = socket;
        this.out = out;
    }

    private static FeedConnection connect(InetAddress address, int port, int connectTimeoutMillis, int sendBufferBytes)
            throws IOException {
        Socket socket = new Socket();
        try {
            // the buffer size must be set before the connect to take effect on the window of the connection
            socket.setSendBufferSize(sendBufferBytes);
            socket.setTcpNoDelay(true);
            // an InetSocketAddress built from an InetAddress does no name lookup
            socket.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
            return new SocketFeedConnection(socket, socket.getOutputStream());
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing after a failure
            }
            throw e;
        }
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        out.write(data, offset, length);
        out.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    /** For the tests: the socket options that were applied. */
    Socket socketForTest() {
        return socket;
    }
}
