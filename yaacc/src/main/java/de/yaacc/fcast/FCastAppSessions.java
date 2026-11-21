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
package de.yaacc.fcast;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledExecutorService;

import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.protocol.FCastConnection;
import de.yaacc.fcast.protocol.FCastSession;
import de.yaacc.multiroom.control.MonotonicClock;

/** Makes the session of the app for a receiver: TCP to its address, the default times. The name is looked up on the session's thread. */
public final class FCastAppSessions {

    private FCastAppSessions() {
    }

    public static FCastSession create(final FCastEndpoint target, FCastSession.Listener listener, ScheduledExecutorService executor,
                                      final String appVersion) {
        final MonotonicClock clock = MonotonicClock.SYSTEM;
        FCastSession.Connector connector = connectionListener -> FCastSession.connectorFor(
                new InetSocketAddress(target.getHost(), target.getPort()), new FCastConnection.Config(), clock,
                "YAACC", "YAACC", appVersion).connect(connectionListener);
        return new FCastSession(connector, listener, new FCastSession.Config(), clock, executor);
    }
}
