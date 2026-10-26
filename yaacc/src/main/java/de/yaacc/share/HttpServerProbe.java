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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * Asks whether the local HTTP server accepts connections: a plain TCP connect to the loopback
 * address of its port, nothing is sent. The server runs whenever the app process lives, so this
 * only tells that it came up (it may still be starting after a cold start).
 */
public final class HttpServerProbe {

    /** Connect timeout; the loopback answers at once or not at all. */
    static final int TIMEOUT_MILLIS = 500;

    private HttpServerProbe() {
    }

    /**
     * Blocks for at most {@link #TIMEOUT_MILLIS}. Do NOT call it on the main thread.
     *
     * @return true if the connection was accepted
     */
    public static boolean isRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", YaaccUpnpServerService.PORT), TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
