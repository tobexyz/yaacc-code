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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;

/** Opens the connection of the control client, so that tests can use a loopback fake server or a broken stream. */
public interface SnapcastTransport {

    /**
     * Opens a connection.
     *
     * @param connectTimeoutMillis how long the connect may take
     * @throws IOException when it fails
     */
    Connection open(int connectTimeoutMillis) throws IOException;

    /** An open connection. {@link #close()} may be called from any thread and ends a blocked read. */
    interface Connection extends Closeable {

        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        /** Sets how long a single read may block (0: forever). */
        void setReadTimeout(int millis) throws IOException;

        /** The address that was connected to (the later feed connects to this address and no other); may be null. */
        InetAddress getPeerAddress();

        /**
         * Tells the peer that nothing more will be sent (a TCP FIN) without closing the connection, so that the server
         * sees an orderly end and answers with its own; the default does nothing.
         */
        default void shutdownOutput() {
        }

        @Override
        void close();
    }
}
