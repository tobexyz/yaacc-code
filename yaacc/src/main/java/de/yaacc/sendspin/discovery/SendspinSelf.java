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
package de.yaacc.sendspin.discovery;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Tells whether an address belongs to this device. The phone must not list itself: with the listener switched on it announces
 * itself as a Sendspin server, and its own search of servers finds that announcement (a card of a server that is the phone, and a
 * player that "connects" from the phone's own address).
 */
public final class SendspinSelf {

    private SendspinSelf() {
    }

    /** @return true for an address of this device (any interface, loopback, the wildcard); false for null and for a lookup that failed */
    public static boolean isThisDevice(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return false;
        }
    }
}
