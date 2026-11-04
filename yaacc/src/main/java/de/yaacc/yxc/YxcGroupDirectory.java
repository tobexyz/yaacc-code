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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who belongs to whose group, found from the states of the watched devices. The answer of a client does not name its
 * server and its group name is its own ("(Linked) ..."), so the members of a group are matched by the <b>group id</b>:
 * the server of a client is the watched device that is a server with the same id, the clients of a server are the
 * watched devices at the addresses it lists (and the ones that report its id as clients). Pure; built from one
 * snapshot of the monitor.
 */
public final class YxcGroupDirectory {
    private final Map<String, YxcDeviceSnapshot> byUdn = new LinkedHashMap<>();

    public YxcGroupDirectory(Collection<YxcDeviceSnapshot> devices) {
        for (YxcDeviceSnapshot device : devices) {
            byUdn.put(device.getUdn(), device);
        }
    }

    /**
     * @return the UDN of the server of the group this client is in. Only when exactly one watched device is a server with
     * this group id <b>and lists this client's address</b>: group ids can be read by anybody on the network, so a
     * second claimant or a server that does not list the client is not believed. {@code null} otherwise.
     */
    public String serverOf(String udn) {
        YxcDeviceSnapshot device = byUdn.get(udn);
        if (device == null || device.getState().getRole() != YxcDistributionInfo.Role.CLIENT
                || device.getState().getGroupId() == null) {
            return null;
        }
        String found = null;
        for (YxcDeviceSnapshot other : byUdn.values()) {
            if (!other.getUdn().equals(udn) && other.getState().getRole() == YxcDistributionInfo.Role.SERVER
                    && device.getState().getGroupId().equalsIgnoreCase(String.valueOf(other.getState().getGroupId()))) {
                if (found != null) {
                    return null; // two claim the group
                }
                found = other.getUdn();
            }
        }
        if (found == null) {
            return null;
        }
        for (String address : byUdn.get(found).getState().getClientAddresses()) {
            if (address.equalsIgnoreCase(device.getHost())) {
                return found;
            }
        }
        return null;
    }

    /** @return the UDNs of the watched devices that are clients of this server, in the order the server lists them; empty for any other role */
    public List<String> clientsOf(String udn) {
        List<String> result = new ArrayList<>();
        YxcDeviceSnapshot server = byUdn.get(udn);
        if (server == null || server.getState().getRole() != YxcDistributionInfo.Role.SERVER) {
            return result;
        }
        for (String address : server.getState().getClientAddresses()) {
            for (YxcDeviceSnapshot other : byUdn.values()) {
                if (!other.getUdn().equals(udn) && other.getHost().equalsIgnoreCase(address) && !result.contains(other.getUdn())) {
                    result.add(other.getUdn());
                }
            }
        }
        String id = server.getState().getGroupId();
        for (YxcDeviceSnapshot other : byUdn.values()) {
            if (id != null && !other.getUdn().equals(udn) && other.getState().getRole() == YxcDistributionInfo.Role.CLIENT
                    && id.equalsIgnoreCase(String.valueOf(other.getState().getGroupId())) && !result.contains(other.getUdn())) {
                result.add(other.getUdn());
            }
        }
        return result;
    }

    /** @return the UDNs of the whole group (server first), empty if the device is in none or its server is not watched */
    public List<String> membersOf(String udn) {
        List<String> result = new ArrayList<>();
        YxcDeviceSnapshot device = byUdn.get(udn);
        if (device == null) {
            return result;
        }
        String server = device.getState().getRole() == YxcDistributionInfo.Role.SERVER ? udn : serverOf(udn);
        if (server == null) {
            return result;
        }
        result.add(server);
        result.addAll(clientsOf(server));
        return result;
    }

    /** @return the snapshot of a device, {@code null} if it is not watched */
    public YxcDeviceSnapshot get(String udn) {
        return byUdn.get(udn);
    }

    public Collection<YxcDeviceSnapshot> all() {
        return new ArrayList<>(byUdn.values());
    }
}
