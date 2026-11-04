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

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the text of a UDP datagram of a device into a {@link YxcEvent}. Never throws: whatever arrives from the
 * network that is not a JSON object of reasonable size gives {@link YxcEvent#unparsable()}.
 */
public final class YxcEventParser {
    /** Events are small; a larger datagram is not one of ours. */
    static final int MAX_LENGTH = 16 * 1024;
    /** Guards the recursive JSON parser against a datagram made of brackets. */
    static final int MAX_DEPTH = 16;

    private static final List<String> ZONE_IDS = Arrays.asList("main", "zone2", "zone3", "zone4");

    public YxcEvent parse(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset > data.length - length || length > MAX_LENGTH) {
            return YxcEvent.unparsable();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            return YxcEvent.unparsable();
        }
        return parse(text);
    }

    public YxcEvent parse(String text) {
        if (text == null || text.length() > MAX_LENGTH || nestingDepth(text) > MAX_DEPTH) {
            return YxcEvent.unparsable();
        }
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (JSONException | RuntimeException e) {
            return YxcEvent.unparsable();
        }
        return fromJson(json);
    }

    private static YxcEvent fromJson(JSONObject json) {
        Set<String> blocks = new LinkedHashSet<>();
        Map<String, YxcEvent.ZoneBlock> zones = new LinkedHashMap<>();
        for (String key : YxcJson.keys(json)) {
            if (ZONE_IDS.contains(key)) {
                JSONObject block = YxcJson.object(json, key);
                if (block != null) {
                    blocks.add(key);
                    zones.put(key, new YxcEvent.ZoneBlock(
                            YxcJson.string(block, "power"),
                            YxcJson.integer(block, "volume"),
                            YxcJson.bool(block, "mute"),
                            YxcJson.string(block, "input"),
                            YxcJson.flag(block, "status_updated"),
                            YxcJson.flag(block, "play_info_updated"),
                            YxcJson.flag(block, "signal_info_updated")));
                }
            }
        }
        YxcEvent.NetUsbBlock netUsb = null;
        JSONObject netUsbJson = YxcJson.object(json, "netusb");
        if (netUsbJson != null) {
            blocks.add("netusb");
            netUsb = new YxcEvent.NetUsbBlock(YxcJson.integer(netUsbJson, "play_time"),
                    YxcJson.flag(netUsbJson, "play_info_updated"), YxcJson.flag(netUsbJson, "preset_info_updated"),
                    YxcJson.flag(YxcJson.object(netUsbJson, "play_queue"), "updated"),
                    YxcJson.flag(netUsbJson, "account_updated"));
        }
        JSONObject tuner = YxcJson.object(json, "tuner");
        JSONObject dist = YxcJson.object(json, "dist");
        JSONObject clock = YxcJson.object(json, "clock");
        JSONObject system = YxcJson.object(json, "system");
        addIfPresent(blocks, "tuner", tuner);
        addIfPresent(blocks, "dist", dist);
        addIfPresent(blocks, "clock", clock);
        addIfPresent(blocks, "system", system);
        return new YxcEvent(true, zones, netUsb, blocks,
                YxcJson.flag(tuner, "play_info_updated"),
                YxcJson.flag(dist, "dist_info_updated"),
                YxcJson.flag(clock, "settings_updated"),
                YxcJson.flag(system, "func_status_updated"),
                YxcJson.string(json, "device_id"));
    }

    private static void addIfPresent(Set<String> blocks, String name, JSONObject block) {
        if (block != null) {
            blocks.add(name);
        }
    }

    /** Maximum nesting of brackets outside of strings. */
    private static int nestingDepth(String text) {
        int depth = 0;
        int max = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                max = Math.max(max, depth);
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return max;
    }
}
