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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answer of {@code system/getFeatures}: the device-level functions, inputs and ranges, the zones, whether the device
 * has a network source section and its {@code distribution} block. Unknown keys and function names are ignored.
 */
public final class YxcFeatures {
    private final List<String> systemFunctions;
    private final Map<String, YxcRange> systemRanges;
    private final List<YxcSystemInput> systemInputs;
    private final List<YxcZoneFeatures> zones;
    private final boolean netUsb;
    private final YxcDistributionFeatures distribution;
    private final YxcSourceCapabilities sources;

    private YxcFeatures(List<String> systemFunctions, Map<String, YxcRange> systemRanges, List<YxcSystemInput> systemInputs,
                        List<YxcZoneFeatures> zones, boolean netUsb, YxcDistributionFeatures distribution,
                        YxcSourceCapabilities sources) {
        this.systemFunctions = systemFunctions;
        this.systemRanges = systemRanges;
        this.systemInputs = systemInputs;
        this.zones = zones;
        this.netUsb = netUsb;
        this.distribution = distribution;
        this.sources = sources;
    }

    static YxcFeatures fromJson(JSONObject json) {
        JSONObject system = YxcJson.object(json, "system");
        Map<String, YxcRange> systemRanges = new LinkedHashMap<>();
        for (JSONObject entry : YxcJson.objects(system, "range_step")) {
            YxcRange range = YxcRange.fromJson(entry);
            if (range != null) {
                systemRanges.put(range.getId(), range);
            }
        }
        List<YxcSystemInput> inputs = new ArrayList<>();
        for (JSONObject entry : YxcJson.objects(system, "input_list")) {
            YxcSystemInput input = YxcSystemInput.fromJson(entry);
            if (input != null) {
                inputs.add(input);
            }
        }
        List<YxcZoneFeatures> zones = new ArrayList<>();
        for (JSONObject entry : YxcJson.objects(json, "zone")) {
            YxcZoneFeatures zone = YxcZoneFeatures.fromJson(entry);
            if (zone != null) {
                zones.add(zone);
            }
        }
        // a network source section counts only with functions, like the open-source library does
        boolean netUsb = !YxcJson.strings(YxcJson.object(json, "netusb"), "func_list").isEmpty();
        return new YxcFeatures(
                YxcJson.strings(system, "func_list"),
                Collections.unmodifiableMap(systemRanges),
                Collections.unmodifiableList(inputs),
                Collections.unmodifiableList(zones),
                netUsb,
                YxcDistributionFeatures.fromFeatures(json),
                YxcSourceCapabilities.fromJson(json));
    }

    /** @return the bands of the tuner and the preset counts of the sources, as the device reports them */
    public YxcSourceCapabilities getSourceCapabilities() {
        return sources;
    }

    public List<String> getSystemFunctions() {
        return systemFunctions;
    }

    public boolean hasSystemFunction(String name) {
        return systemFunctions.contains(name);
    }

    /** @return the device-level range with that id (for example {@code dimmer}), {@code null} if not reported */
    public YxcRange getSystemRange(String rangeId) {
        return systemRanges.get(rangeId);
    }

    public List<YxcSystemInput> getSystemInputs() {
        return systemInputs;
    }

    /** @return the device-level input with that id, {@code null} if not reported */
    public YxcSystemInput getSystemInput(String inputId) {
        for (YxcSystemInput input : systemInputs) {
            if (input.getId().equals(inputId)) {
                return input;
            }
        }
        return null;
    }

    public List<YxcZoneFeatures> getZones() {
        return zones;
    }

    /** @return the zone with that id (for example {@code main}), {@code null} if the device has no such zone */
    public YxcZoneFeatures getZone(String zoneId) {
        for (YxcZoneFeatures zone : zones) {
            if (zone.getId().equals(zoneId)) {
                return zone;
            }
        }
        return null;
    }

    public boolean hasNetUsb() {
        return netUsb;
    }

    /** @return the {@code distribution} block, {@code null} when the device did not report one */
    public YxcDistributionFeatures getDistribution() {
        return distribution;
    }

    /** The zones the device allows as group server ({@code distribution.server_zone_list}); empty when absent. */
    public List<String> getServerZones() {
        return distribution == null ? Collections.<String>emptyList() : distribution.getServerZones();
    }
}
