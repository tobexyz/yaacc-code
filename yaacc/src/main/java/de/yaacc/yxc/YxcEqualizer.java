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

/** The {@code equalizer} object of a zone status: the mode word and the three band values, each absent when not reported. */
public final class YxcEqualizer {
    private final String mode;
    private final Integer low;
    private final Integer mid;
    private final Integer high;

    private YxcEqualizer(JSONObject json) {
        mode = YxcJson.string(json, "mode");
        low = YxcJson.integer(json, "low");
        mid = YxcJson.integer(json, "mid");
        high = YxcJson.integer(json, "high");
    }

    /** @return the equalizer, {@code null} when the status has no {@code equalizer} object */
    static YxcEqualizer fromStatus(JSONObject status) {
        JSONObject block = YxcJson.object(status, "equalizer");
        return block == null ? null : new YxcEqualizer(block);
    }

    /** @return for example {@code manual}, {@code null} if not reported */
    public String getMode() {
        return mode;
    }

    /** @return the value of the band in the range of the zone's {@code equalizer} range, {@code null} if not reported */
    public Integer getLow() {
        return low;
    }

    public Integer getMid() {
        return mid;
    }

    public Integer getHigh() {
        return high;
    }
}
