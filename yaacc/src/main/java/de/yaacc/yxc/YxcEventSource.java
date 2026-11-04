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
package de.yaacc.yxc;

import java.net.InetAddress;

/** Where events of the devices come in from; the real one is {@link YxcEventListener}, tests bring their own. */
public interface YxcEventSource {

    /** Called for every datagram on the thread of the source; must not block. */
    interface Receiver {
        /**
         * Asked before a datagram is parsed: whether anything from this address can matter. A datagram from
         * anybody else is dropped without being read, so a stranger on the network costs no parsing.
         */
        default boolean accepts(InetAddress from) {
            return true;
        }

        void onEvent(InetAddress from, YxcEvent event);
    }

    /** @return the UDP port the devices are told to send events to */
    int getPort();

    /** Stops receiving; the port is free again. Idempotent. */
    void close();

    /** Opens a source that delivers to the receiver. */
    interface Factory {
        YxcEventSource open(Receiver receiver) throws java.io.IOException;
    }
}
