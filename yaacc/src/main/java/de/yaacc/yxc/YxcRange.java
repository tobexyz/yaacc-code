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

/**
 * Range of a numeric control as a device reports it in its features: {@code min}, {@code max} and {@code step}.
 * The scale is the device's own (a volume is not a percentage).
 */
public final class YxcRange {
    private final String id;
    private final int min;
    private final int max;
    private final int step;

    public YxcRange(String id, int min, int max, int step) {
        if (max < min) {
            throw new IllegalArgumentException("max < min");
        }
        this.id = id;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
    }

    /** @return the range, or {@code null} when the entry has no id, min or max */
    static YxcRange fromJson(JSONObject json) {
        String id = YxcJson.string(json, "id");
        Integer min = YxcJson.integer(json, "min");
        Integer max = YxcJson.integer(json, "max");
        if (id == null || min == null || max == null || max < min) {
            return null;
        }
        Integer step = YxcJson.integer(json, "step");
        return new YxcRange(id, min, max, step == null ? 1 : step);
    }

    public String getId() {
        return id;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getStep() {
        return step;
    }

    /** Within the range and on a step counted from the minimum (the range need not start at a multiple of the step). */
    public boolean accepts(int value) {
        return value >= min && value <= max && (value - min) % step == 0;
    }

    @Override
    public String toString() {
        return id + "[" + min + ".." + max + " step " + step + "]";
    }
}
