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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one zone of a device reports in {@code system/getFeatures}: its functions, input ids, sound program ids,
 * the ranges of its numeric controls and the option lists of its choice controls. Nothing is derived from a model
 * name; a function or list the device does not report is simply absent.
 */
public final class YxcZoneFeatures {
    /** The input a device selects on a zone to play what a group server distributes. */
    public static final String GROUP_INPUT_ID = "mc_link";

    private static final List<String> OWN_LISTS = Arrays.asList("func_list", "input_list", "sound_program_list");

    private final String id;
    private final List<String> functions;
    private final List<String> inputIds;
    private final List<String> soundProgramIds;
    private final Map<String, YxcRange> ranges;
    private final Map<String, List<String>> optionLists;

    private YxcZoneFeatures(String id, List<String> functions, List<String> inputIds, List<String> soundProgramIds,
                            Map<String, YxcRange> ranges, Map<String, List<String>> optionLists) {
        this.id = id;
        this.functions = functions;
        this.inputIds = inputIds;
        this.soundProgramIds = soundProgramIds;
        this.ranges = ranges;
        this.optionLists = optionLists;
    }

    /** @return the zone, or {@code null} if the entry has no id */
    static YxcZoneFeatures fromJson(JSONObject json) {
        String id = YxcJson.string(json, "id");
        if (id == null) {
            return null;
        }
        Map<String, YxcRange> ranges = new LinkedHashMap<>();
        for (JSONObject entry : YxcJson.objects(json, "range_step")) {
            YxcRange range = YxcRange.fromJson(entry);
            if (range != null) {
                ranges.put(range.getId(), range);
            }
        }
        // every other "<name>_list" of strings is a list of options (equalizer modes, link control, ...)
        Map<String, List<String>> optionLists = new LinkedHashMap<>();
        for (String key : YxcJson.keys(json)) {
            if (key.endsWith("_list") && !OWN_LISTS.contains(key)) {
                List<String> values = YxcJson.strings(json, key);
                if (!values.isEmpty()) {
                    optionLists.put(key, values);
                }
            }
        }
        return new YxcZoneFeatures(id,
                YxcJson.strings(json, "func_list"),
                YxcJson.strings(json, "input_list"),
                YxcJson.strings(json, "sound_program_list"),
                Collections.unmodifiableMap(ranges),
                Collections.unmodifiableMap(optionLists));
    }

    public String getId() {
        return id;
    }

    public List<String> getFunctions() {
        return functions;
    }

    public boolean hasFunction(String name) {
        return functions.contains(name);
    }

    public List<String> getInputIds() {
        return inputIds;
    }

    public List<String> getSoundProgramIds() {
        return soundProgramIds;
    }

    /** @return the range of the numeric control with that id (for example {@code volume}), {@code null} if not reported */
    public YxcRange getRange(String rangeId) {
        return ranges.get(rangeId);
    }

    public Map<String, YxcRange> getRanges() {
        return ranges;
    }

    /**
     * @param listKey the key as the device reports it, for example {@code equalizer_mode_list}
     * @return the options, empty if the device does not report that list
     */
    public List<String> getOptions(String listKey) {
        List<String> options = optionLists.get(listKey);
        return options == null ? Collections.<String>emptyList() : options;
    }

    public Map<String, List<String>> getOptionLists() {
        return optionLists;
    }

    public List<String> getEqualizerModes() {
        return getOptions("equalizer_mode_list");
    }

    public List<String> getLinkControlOptions() {
        return getOptions("link_control_list");
    }

    public List<String> getLinkAudioDelayOptions() {
        return getOptions("link_audio_delay_list");
    }

    /** @return empty on a device that has no audio quality setting for the link */
    public List<String> getLinkAudioQualityOptions() {
        return getOptions("link_audio_quality_list");
    }

    /** A zone can be a client of a group when it offers the group input. */
    public boolean canJoinGroup() {
        return inputIds.contains(GROUP_INPUT_ID);
    }
}
