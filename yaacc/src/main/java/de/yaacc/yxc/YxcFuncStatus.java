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

/** Answer of {@code system/getFuncStatus}: device-level settings; each is absent when the device does not have it. */
public final class YxcFuncStatus {
    private final Boolean speakerA;
    private final Boolean speakerB;
    private final Integer dimmer;
    private final Boolean autoPowerStandby;

    YxcFuncStatus(Boolean speakerA, Boolean speakerB, Integer dimmer, Boolean autoPowerStandby) {
        this.speakerA = speakerA;
        this.speakerB = speakerB;
        this.dimmer = dimmer;
        this.autoPowerStandby = autoPowerStandby;
    }

    static YxcFuncStatus fromJson(JSONObject json) {
        return new YxcFuncStatus(
                YxcJson.bool(json, "speaker_a"),
                YxcJson.bool(json, "speaker_b"),
                YxcJson.integer(json, "dimmer"),
                YxcJson.bool(json, "auto_power_standby"));
    }

    public Boolean getSpeakerA() {
        return speakerA;
    }

    public Boolean getSpeakerB() {
        return speakerB;
    }

    /** @return the display brightness step (-1 means automatic), {@code null} if absent */
    public Integer getDimmer() {
        return dimmer;
    }

    /** @return whether the device switches to standby by itself, {@code null} if absent */
    public Boolean getAutoPowerStandby() {
        return autoPowerStandby;
    }
}
