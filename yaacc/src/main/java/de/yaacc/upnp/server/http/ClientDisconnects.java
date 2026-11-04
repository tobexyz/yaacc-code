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
package de.yaacc.upnp.server.http;

import java.io.IOException;
import java.util.Locale;

/**
 * Tells whether an exception of the HTTP server only means that the client hung up while the server was
 * reading or writing: a control point that gives up (its own timeout, the user left the screen, the app
 * was closed) closes the socket and the next write fails with {@code Broken pipe} or {@code Connection reset
 * by peer}. That is normal and not worth an error with a stack trace.
 */
public final class ClientDisconnects {

    private static final int MAX_CAUSES = 8;
    private static final String[] MARKERS = {
            "broken pipe",
            "connection reset",
            "software caused connection abort",
            "connection abort"
    };

    private ClientDisconnects() {
    }

    /**
     * @return true if the exception, or one of its causes, is an {@link IOException} whose message says that the peer
     * closed or reset the connection. Other exceptions, and exceptions without a message, are not.
     */
    public static boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (current instanceof IOException && mentionsDisconnect(current.getMessage())) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean mentionsDisconnect(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
