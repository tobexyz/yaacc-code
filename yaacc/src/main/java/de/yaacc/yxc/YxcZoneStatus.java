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
 * Answer of {@code <zone>/getStatus}. Values are as the device reports them; a key the device does not have reads as
 * {@code null} (three devices checked on 2026-11-02 differ: one has no {@code input_text} and no {@code sound_program},
 * two have no {@code link_audio_quality}).
 */
public final class YxcZoneStatus {
    private static final String POWER_ON = "on";

    private final String power;
    private final Integer sleep;
    private final Integer volume;
    private final Integer maxVolume;
    private final Boolean mute;
    private final String input;
    private final String inputText;
    private final Boolean distributionEnabled;
    private final String soundProgram;
    private final YxcEqualizer equalizer;
    private final String linkControl;
    private final String linkAudioDelay;
    private final String linkAudioQuality;
    private final Integer disableFlags;

    private YxcZoneStatus(JSONObject json) {
        power = YxcJson.string(json, "power");
        sleep = YxcJson.integer(json, "sleep");
        volume = YxcJson.integer(json, "volume");
        maxVolume = YxcJson.integer(json, "max_volume");
        mute = YxcJson.bool(json, "mute");
        input = YxcJson.string(json, "input");
        inputText = YxcJson.string(json, "input_text");
        distributionEnabled = YxcJson.bool(json, "distribution_enable");
        soundProgram = YxcJson.string(json, "sound_program");
        equalizer = YxcEqualizer.fromStatus(json);
        linkControl = YxcJson.string(json, "link_control");
        linkAudioDelay = YxcJson.string(json, "link_audio_delay");
        linkAudioQuality = YxcJson.string(json, "link_audio_quality");
        disableFlags = YxcJson.integer(json, "disable_flags");
    }

    static YxcZoneStatus fromJson(JSONObject json) {
        return new YxcZoneStatus(json);
    }

    /** @return the power word as reported ({@code on} or a standby word), {@code null} if absent */
    public String getPower() {
        return power;
    }

    public boolean isPowerOn() {
        return POWER_ON.equalsIgnoreCase(power);
    }

    /** @return the sleep timer in minutes (0 is off), {@code null} if absent */
    public Integer getSleep() {
        return sleep;
    }

    /** @return the volume in the zone's own scale (see the range of the features), {@code null} if absent */
    public Integer getVolume() {
        return volume;
    }

    /** @return the highest volume the zone accepts now, {@code null} if absent */
    public Integer getMaxVolume() {
        return maxVolume;
    }

    public Boolean getMute() {
        return mute;
    }

    /** @return the id of the selected input, {@code null} if absent */
    public String getInput() {
        return input;
    }

    /** @return the name the device shows for the input, {@code null} on a device that does not report it */
    public String getInputText() {
        return inputText;
    }

    /**
     * True when the zone has the input a group client plays ({@code mc_link}). A server keeps its own input, so this is
     * a hint that the device is a client right now; the authority for group membership is {@code dist/getDistributionInfo}.
     */
    public boolean isLinkInput() {
        return YxcZoneFeatures.GROUP_INPUT_ID.equalsIgnoreCase(input);
    }

    /**
     * Whether what the zone plays can be handed to a group: {@code false} on a client (its input is the link),
     * {@code true} on a server; {@code null} if absent.
     */
    public Boolean getDistributionEnabled() {
        return distributionEnabled;
    }

    public String getSoundProgram() {
        return soundProgram;
    }

    /** @return the equalizer block, {@code null} if the status has none */
    public YxcEqualizer getEqualizer() {
        return equalizer;
    }

    public String getLinkControl() {
        return linkControl;
    }

    public String getLinkAudioDelay() {
        return linkAudioDelay;
    }

    /** @return {@code null} on a device without the audio quality setting */
    public String getLinkAudioQuality() {
        return linkAudioQuality;
    }

    /** @return the bit field of functions the device blocks at the moment (0 is none), {@code null} if absent */
    public Integer getDisableFlags() {
        return disableFlags;
    }
}
