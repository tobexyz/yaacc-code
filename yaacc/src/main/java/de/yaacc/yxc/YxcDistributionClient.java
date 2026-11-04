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

import org.json.JSONObject;

/** One entry of the {@code client_list} of a server: the address of the client and its {@code data_type} (seen: {@code base}). */
public final class YxcDistributionClient {
    private final String ipAddress;
    private final String dataType;

    private YxcDistributionClient(String ipAddress, String dataType) {
        this.ipAddress = ipAddress;
        this.dataType = dataType;
    }

    /** @return the entry, or {@code null} if it has no address */
    static YxcDistributionClient fromJson(JSONObject json) {
        String ip = YxcJson.string(json, "ip_address");
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        return new YxcDistributionClient(ip, YxcJson.string(json, "data_type"));
    }

    public String getIpAddress() {
        return ipAddress;
    }

    /** @return the data type word, {@code null} if the device did not report one */
    public String getDataType() {
        return dataType;
    }
}
