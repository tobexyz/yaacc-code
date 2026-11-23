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
package de.yaacc.fcast.discovery;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A network of the phone, an address and a prefix length (CIDR), used to find out which of the addresses in a connection link are on the
 * same network as the phone (task 5.5): those are tried first, the others only if none of them answers. Pure.
 */
public final class FCastSubnet {

    private final byte[] network;
    private final int prefixLength;

    /** @param address an address of the phone on that network; @param prefixLength the number of bits of the network part */
    public FCastSubnet(InetAddress address, int prefixLength) {
        this.network = address == null ? new byte[0] : address.getAddress();
        this.prefixLength = Math.max(0, Math.min(prefixLength, network.length * 8));
    }

    /** True if the address is in this network (the same kind of address: IPv4 with IPv4, IPv6 with IPv6). */
    public boolean contains(InetAddress address) {
        if (address == null || network.length == 0) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (raw[i] != network[i]) {
                return false;
            }
        }
        int rest = prefixLength % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xff << (8 - rest)) & 0xff;
        return (raw[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /** True if the endpoint is an IP address in any of the networks; a host name is in none. */
    static boolean anyContains(List<FCastSubnet> subnets, FCastEndpoint endpoint) {
        InetAddress address = endpoint.getAddress();
        if (address == null) {
            return false;
        }
        for (FCastSubnet subnet : subnets) {
            if (subnet.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** The endpoints that are in one of the networks, in their order. */
    static List<FCastEndpoint> inside(List<FCastSubnet> subnets, List<FCastEndpoint> endpoints) {
        List<FCastEndpoint> result = new ArrayList<>();
        for (FCastEndpoint endpoint : endpoints) {
            if (anyContains(subnets, endpoint)) {
                result.add(endpoint);
            }
        }
        return result;
    }

    /** The endpoints that are in none of the networks, in their order. */
    static List<FCastEndpoint> outside(List<FCastSubnet> subnets, List<FCastEndpoint> endpoints) {
        List<FCastEndpoint> result = new ArrayList<>();
        for (FCastEndpoint endpoint : endpoints) {
            if (!anyContains(subnets, endpoint)) {
                result.add(endpoint);
            }
        }
        return result;
    }
}
