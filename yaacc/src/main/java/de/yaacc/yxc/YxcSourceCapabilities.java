/*
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

import java.util.Objects;

import org.json.JSONObject;

/**
 * What a device says about its own sources in {@code system/getFeatures}: the bands of the tuner and how many presets the
 * network source and the tuner keep. Static device facts, read once; a device without the blocks has none of them.
 */
public final class YxcSourceCapabilities {
    /** More presets than this are never offered. */
    public static final int MAX_PRESETS = 100;

    private final boolean fm;
    private final boolean dab;
    private final int tunerPresets;
    private final int netPresets;
    private final boolean recent;

    private YxcSourceCapabilities(boolean fm, boolean dab, int tunerPresets, int netPresets, boolean recent) {
        this.fm = fm;
        this.dab = dab;
        this.tunerPresets = tunerPresets;
        this.netPresets = netPresets;
        this.recent = recent;
    }

    static YxcSourceCapabilities fromJson(JSONObject features) {
        JSONObject tuner = YxcJson.object(features, "tuner");
        JSONObject netusb = YxcJson.object(features, "netusb");
        java.util.List<String> tunerFunctions = YxcJson.strings(tuner, "func_list");
        java.util.List<String> netFunctions = YxcJson.strings(netusb, "func_list");
        return new YxcSourceCapabilities(tunerFunctions.contains("fm"), tunerFunctions.contains("dab"),
                presets(tuner), presets(netusb), netFunctions.contains("recent_info"));
    }

    private static int presets(JSONObject block) {
        JSONObject preset = YxcJson.object(block, "preset");
        Integer num = preset == null ? null : YxcJson.integer(preset, "num");
        return num == null ? 0 : Math.max(0, Math.min(num, MAX_PRESETS));
    }

    /** No facts known: nothing that needs them is offered. */
    public static YxcSourceCapabilities none() {
        return new YxcSourceCapabilities(false, false, 0, 0, false);
    }

    public boolean hasFm() {
        return fm;
    }

    public boolean hasDab() {
        return dab;
    }

    /** @return the number of tuner presets (0 when none, at most {@link #MAX_PRESETS}) */
    public int getTunerPresets() {
        return tunerPresets;
    }

    /** @return the number of presets of the network source (0 when none, at most {@link #MAX_PRESETS}) */
    public int getNetPresets() {
        return netPresets;
    }

    /** @return whether the network source keeps a list of recently played items */
    public boolean hasRecent() {
        return recent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof YxcSourceCapabilities)) {
            return false;
        }
        YxcSourceCapabilities other = (YxcSourceCapabilities) o;
        return fm == other.fm && dab == other.dab && tunerPresets == other.tunerPresets && netPresets == other.netPresets
                && recent == other.recent;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fm, dab, tunerPresets, netPresets, recent);
    }
}
