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
package de.yaacc.multiroom.control;

import org.json.JSONException;
import org.json.JSONObject;

/** The volume of a client: a percentage of 0 to 100 and a mute flag. Immutable. */
public final class SnapcastVolume {

    private final int percent;
    private final boolean muted;

    /** @param percent clamped into 0..100 */
    public SnapcastVolume(int percent, boolean muted) {
        this.percent = Math.max(0, Math.min(100, percent));
        this.muted = muted;
    }

    /** Reads the {@code volume} object of a client; a missing object gives 100 percent, not muted. */
    static SnapcastVolume fromJson(JSONObject json) {
        return new SnapcastVolume(SnapcastJson.integer(json, "percent", 0, 100, 100), SnapcastJson.bool(json, "muted", false));
    }

    /** The JSON the server expects in {@code Client.SetVolume}. */
    JSONObject toJson() {
        try {
            return new JSONObject().put("percent", percent).put("muted", muted);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public int getPercent() {
        return percent;
    }

    public boolean isMuted() {
        return muted;
    }

    public SnapcastVolume withPercent(int newPercent) {
        return new SnapcastVolume(newPercent, muted);
    }

    public SnapcastVolume withMuted(boolean newMuted) {
        return new SnapcastVolume(percent, newMuted);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastVolume)) {
            return false;
        }
        SnapcastVolume that = (SnapcastVolume) other;
        return percent == that.percent && muted == that.muted;
    }

    @Override
    public int hashCode() {
        return 31 * percent + (muted ? 1 : 0);
    }

    @Override
    public String toString() {
        return "SnapcastVolume{" + percent + (muted ? ", muted" : "") + "}";
    }
}
