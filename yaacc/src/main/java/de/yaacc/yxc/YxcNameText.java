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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The display names a device gives its zones, inputs and sound programs ({@code system/getNameText}: lists of
 * {@code {id, text}}). The texts are the owner's own naming and come from the network: cleaned and capped.
 */
public final class YxcNameText {
    private static final int MAX_ENTRIES = 128;
    private static final YxcNameText EMPTY = new YxcNameText(Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap());

    private final Map<String, String> zones;
    private final Map<String, String> inputs;
    private final Map<String, String> soundPrograms;

    private YxcNameText(Map<String, String> zones, Map<String, String> inputs, Map<String, String> soundPrograms) {
        this.zones = zones;
        this.inputs = inputs;
        this.soundPrograms = soundPrograms;
    }

    /** No names known: every lookup answers {@code null}. */
    public static YxcNameText empty() {
        return EMPTY;
    }

    static YxcNameText fromJson(JSONObject json) {
        return new YxcNameText(read(json, "zone_list"), read(json, "input_list"), read(json, "sound_program_list"));
    }

    private static Map<String, String> read(JSONObject json, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JSONObject entry : YxcJson.objects(json, key)) {
            if (result.size() >= MAX_ENTRIES) {
                break;
            }
            String id = YxcJson.string(entry, "id");
            String text = YxcNames.clean(YxcJson.string(entry, "text"));
            if (id != null && text != null) {
                result.put(id, text);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** @return the name of the input, {@code null} when the device gave none */
    public String inputText(String id) {
        return inputs.get(id);
    }

    public String soundProgramText(String id) {
        return soundPrograms.get(id);
    }

    public String zoneText(String id) {
        return zones.get(id);
    }
}
