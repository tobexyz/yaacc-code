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

/**
 * A device taking part in a group operation: its client, the zone that takes part, and its address as the other
 * devices see it (the address the transport of the client talks to). The address must come from our own discovery.
 */
public final class YxcGroupMember {
    private final String ip;
    private final String zone;
    private final YxcClient client;

    public YxcGroupMember(String ip, String zone, YxcClient client) {
        if (ip == null || ip.isEmpty() || zone == null || zone.isEmpty() || client == null) {
            throw new IllegalArgumentException("ip, zone and client are required");
        }
        this.ip = ip;
        this.zone = zone;
        this.client = client;
    }

    public String getIp() {
        return ip;
    }

    public String getZone() {
        return zone;
    }

    public YxcClient getClient() {
        return client;
    }
}
