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

import java.util.List;

/**
 * The {@code distribution} block of {@code system/getFeatures}: what a device can do in a group. The block was present
 * on all three devices the format was checked with (2026-11-02): {@code version}, {@code compatible_client} (a list of
 * numbers), {@code client_max} (9 on one device, 19 on two others), {@code server_zone_list} and, on a device that can
 * be part of a surround set, {@code mc_surround}. The content of {@code mc_surround} is not interpreted; only that it
 * is there. A key that is absent reads as {@code null} or empty.
 */
public final class YxcDistributionFeatures {
    private final Double version;
    private final List<Integer> compatibleClients;
    private final Integer clientMax;
    private final List<String> serverZones;
    private final boolean mcSurround;

    private YxcDistributionFeatures(JSONObject json) {
        version = YxcJson.decimal(json, "version");
        compatibleClients = YxcJson.integers(json, "compatible_client");
        clientMax = YxcJson.integer(json, "client_max");
        serverZones = YxcJson.strings(json, "server_zone_list");
        mcSurround = YxcJson.object(json, "mc_surround") != null;
    }

    /** @return the block, or {@code null} when the answer has no {@code distribution} object */
    static YxcDistributionFeatures fromFeatures(JSONObject features) {
        JSONObject block = YxcJson.object(features, "distribution");
        return block == null ? null : new YxcDistributionFeatures(block);
    }

    /** @return the version of the group function, {@code null} if not reported */
    public Double getVersion() {
        return version;
    }

    /** @return the versions of clients this device can serve, empty if not reported */
    public List<Integer> getCompatibleClients() {
        return compatibleClients;
    }

    /** @return the most clients a server of this device takes, {@code null} if not reported */
    public Integer getClientMax() {
        return clientMax;
    }

    /** @return the zones that may serve a group, empty if not reported */
    public List<String> getServerZones() {
        return serverZones;
    }

    /** @return true when the block has an {@code mc_surround} object (its content is not read) */
    public boolean hasMcSurround() {
        return mcSurround;
    }
}
