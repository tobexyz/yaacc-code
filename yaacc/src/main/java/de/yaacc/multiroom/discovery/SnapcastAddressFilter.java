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
package de.yaacc.multiroom.discovery;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Which addresses a <b>discovered</b> snapserver may have. mDNS is not authenticated, so any host of the network (or
 * a router that forwards multicast) can announce a server at any address; the phone connects only to addresses of
 * the local network: loopback, IPv4 private ranges (10/8, 172.16/12, 192.168/16), link-local (169.254/16 and
 * fe80::/10) and the IPv6 unique-local range fc00::/7. Everything else (public addresses, the carrier-grade NAT range
 * 100.64/10, the deprecated IPv6 site-local range fec0::/10, unspecified and multicast addresses) is refused. A server
 * that the user typed in is not filtered by range (see {@link SnapcastEndpoint}).
 */
public final class SnapcastAddressFilter {

    private SnapcastAddressFilter() {
    }

    /** @return true if a discovered server may be at this address */
    public static boolean isAccepted(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte[] raw = address.getAddress();
            if ((raw[0] & 0xfe) == 0xfc) {
                return true; // fc00::/7, unique local
            }
            return address.isLinkLocalAddress(); // fe80::/10
        }
        // IPv4: Java's "site local" is exactly 10/8, 172.16/12 and 192.168/16
        return address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }
}
