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
import de.yaacc.fcast.protocol.FCastPin;
import de.yaacc.fcast.protocol.FCastSession;
import de.yaacc.multiroom.control.MonotonicClock;

/** Makes the session of the app for a receiver: TCP to its address, the default times. The name is looked up on the session's thread. */
public final class FCastAppSessions {

    private FCastAppSessions() {
    }

    /** Where the key of a receiver comes from (task 7.2): the hub; null for a receiver that is spoken to in v3. */
    public interface PinSource {
        FCastPin pinFor(FCastEndpoint endpoint);
    }

    public static FCastSession create(final FCastEndpoint target, FCastSession.Listener listener, ScheduledExecutorService executor,
                                      final String appVersion) {
        return create(target, listener, executor, appVersion, null);
    }

    /**
     * @param pins asked at every connection attempt (a key stored since the last one counts); with a key and Android 10 or later the
     *             connection speaks v4 and is refused if the key does not match, without a key it speaks v3 (decision D24)
     */
    public static FCastSession create(final FCastEndpoint target, FCastSession.Listener listener, ScheduledExecutorService executor,
                                      final String appVersion, final PinSource pins) {
        final MonotonicClock clock = MonotonicClock.SYSTEM;
        FCastSession.Connector connector = connectionListener -> {
            FCastConnection.Config config = new FCastConnection.Config();
            config.pin = pins == null ? null : pins.pinFor(target);
            return FCastSession.connectorFor(new InetSocketAddress(target.getHost(), target.getPort()), config, clock,
                    "YAACC", "YAACC", appVersion).connect(connectionListener);
        };
        return new FCastSession(connector, listener, new FCastSession.Config(), clock, executor);
    }
}
