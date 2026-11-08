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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.json.JSONObject;

/**
 * The entries a device can switch to by itself: the presets of its network source and of its tuner (FM and DAB) and the
 * recently played radio stations and streaming entries. All names come from the device: they are cleaned and capped, at
 * most {@link YxcSourceCapabilities#MAX_PRESETS} entries of a list are read, and an empty slot is not an entry.
 * (docs/tech.md, section 12: the position in the list is the number to recall; an empty slot is accepted by the device
 * and does nothing, so only non-empty slots are offered.) Pure.
 */
public final class YxcPresets {

    public enum Kind {
        NET_PRESET, RECENT, FM_PRESET, DAB_PRESET
    }

    /** The longest label shown. */
    static final int MAX_LABEL_CHARS = 100;

    private YxcPresets() {
    }

    /** One entry the user can recall. */
    public static final class Entry {
        private final Kind kind;
        private final int number;
        private final String label;
        private final String detail;

        Entry(Kind kind, int number, String label, String detail) {
            this.kind = kind;
            this.number = number;
            this.label = label;
            this.detail = detail;
        }

        public Kind getKind() {
            return kind;
        }

        /** @return the number to recall: the position in the device's list, 1 to 100 */
        public int getNumber() {
            return number;
        }

        /** @return the cleaned name, never empty */
        public String getLabel() {
            return label;
        }

        /** @return a second text (the FM frequency), {@code null} if none */
        public String getDetail() {
            return detail;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry other = (Entry) o;
            return kind == other.kind && number == other.number && label.equals(other.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, number, label);
        }
    }

    /** The presets of the network source ({@code netusb/getPresetInfo}). */
    static List<Entry> netPresets(JSONObject json) {
        List<Entry> result = new ArrayList<>();
        List<JSONObject> slots = YxcJson.objects(json, "preset_info");
        for (int i = 0; i < slots.size() && i < YxcSourceCapabilities.MAX_PRESETS; i++) {
            JSONObject slot = slots.get(i);
            String input = YxcJson.string(slot, "input");
            String label = YxcNames.clean(YxcJson.string(slot, "text"), MAX_LABEL_CHARS);
            if (!isEntryInput(input) || label == null) {
                continue;
            }
            result.add(new Entry(Kind.NET_PRESET, i + 1, label, null));
        }
        return Collections.unmodifiableList(result);
    }

    /** The recently played list ({@code netusb/getRecentInfo}): radio and streaming entries, not tracks of UPnP servers. */
    static List<Entry> recentItems(JSONObject json) {
        List<Entry> result = new ArrayList<>();
        List<JSONObject> slots = YxcJson.objects(json, "recent_info");
        for (int i = 0; i < slots.size() && i < YxcSourceCapabilities.MAX_PRESETS; i++) {
            JSONObject slot = slots.get(i);
            String input = YxcJson.string(slot, "input");
            String label = YxcNames.clean(YxcJson.string(slot, "text"), MAX_LABEL_CHARS);
            if (!isEntryInput(input) || "server".equalsIgnoreCase(input) || label == null) {
                continue;
            }
            result.add(new Entry(Kind.RECENT, i + 1, label, null));
        }
        return Collections.unmodifiableList(result);
    }

    /** The presets of one tuner band ({@code tuner/getPresetInfo?band=...}). */
    static List<Entry> tunerPresets(JSONObject json, String band) {
        boolean fm = "fm".equals(band);
        List<Entry> result = new ArrayList<>();
        List<JSONObject> slots = YxcJson.objects(json, "preset_info");
        for (int i = 0; i < slots.size() && i < YxcSourceCapabilities.MAX_PRESETS; i++) {
            JSONObject slot = slots.get(i);
            String slotBand = YxcJson.string(slot, "band");
            if (slotBand == null || !slotBand.equalsIgnoreCase(band)) {
                continue; // an empty slot says "unknown"
            }
            Integer number = YxcJson.integer(slot, "number");
            String detail = null;
            if (fm && number != null && number > 0) {
                detail = String.format(Locale.ROOT, "%.2f MHz", number / 1000.0);
            }
            String label = YxcNames.clean(YxcJson.string(slot, "text"), MAX_LABEL_CHARS);
            if (label == null) {
                label = detail;
                detail = null;
            }
            if (label == null) {
                continue;
            }
            result.add(new Entry(fm ? Kind.FM_PRESET : Kind.DAB_PRESET, i + 1, label, detail));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean isEntryInput(String input) {
        return input != null && !input.isEmpty() && !"unknown".equalsIgnoreCase(input);
    }
}
