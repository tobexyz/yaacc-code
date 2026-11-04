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

import java.util.List;

/**
 * Applies one change of the settings sheet to the device, after checking it against what the device offers: the
 * devices validate very little themselves (see docs/tech.md), so a value that is not one of the offered choices is refused
 * here, before anything is sent.
 */
public final class YxcSettingsWriter {
    private final YxcClient client;
    private final String zone;

    public YxcSettingsWriter(YxcClient client, String zone) {
        this.client = client;
        this.zone = zone;
    }

    /** Switches the power on or to standby. */
    public void setPower(List<YxcSettings.Item> items, boolean on) throws YxcException {
        require(YxcSettings.find(items, YxcSettings.Id.POWER) != null, "the device does not offer power");
        client.setPower(zone, on);
    }

    /** Selects an input; the value must be one the device lists (never the group input). */
    public void setInput(List<YxcSettings.Item> items, String input) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.INPUT);
        require(item != null && item.offers(input), "not an input of the device");
        client.setInput(zone, input, "");
    }

    public void setSoundProgram(List<YxcSettings.Item> items, String program) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.SOUND_PROGRAM);
        require(item != null && item.offers(program), "not a sound program of the device");
        client.setSoundProgram(zone, program);
    }

    /** @param minutes one of {@link YxcSettings#sleepMinutes()} */
    public void setSleep(List<YxcSettings.Item> items, int minutes) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.SLEEP);
        require(item != null && item.offers(String.valueOf(minutes)), "not a sleep timer value of the device");
        client.setSleep(zone, minutes);
    }

    public void setEqualizerMode(List<YxcSettings.Item> items, String mode) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.EQUALIZER_MODE);
        require(item != null && item.offers(mode), "not an equalizer mode of the device");
        client.setEqualizerMode(zone, mode);
    }

    /**
     * Sets one band. The value must be inside the range the device reports and a step from its minimum, and the band can be
     * changed now (the manual mode).
     *
     * @param band {@code EQUALIZER_LOW}, {@code EQUALIZER_MID} or {@code EQUALIZER_HIGH}
     */
    public void setEqualizerBand(List<YxcSettings.Item> items, YxcSettings.Id band, int value) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, band);
        require(item != null && item.getKind() == YxcSettings.Kind.NUMBER, "not an equalizer band of the device");
        require(item.isEnabled(), "the equalizer is not in the manual mode");
        require(item.accepts(value), "value outside of the range of the device");
        String name = band == YxcSettings.Id.EQUALIZER_LOW ? "low" : band == YxcSettings.Id.EQUALIZER_MID ? "mid" : "high";
        client.setEqualizerBand(zone, name, value);
    }

    public void setLinkControl(List<YxcSettings.Item> items, String value) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.LINK_CONTROL);
        require(item != null && item.offers(value), "not a link control of the device");
        client.setLinkControl(zone, value);
    }

    public void setLinkAudioDelay(List<YxcSettings.Item> items, String value) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.LINK_AUDIO_DELAY);
        require(item != null && item.offers(value), "not a link audio delay of the device");
        client.setLinkAudioDelay(zone, value);
    }

    public void setLinkAudioQuality(List<YxcSettings.Item> items, String value) throws YxcException {
        YxcSettings.Item item = YxcSettings.find(items, YxcSettings.Id.LINK_AUDIO_QUALITY);
        require(item != null && item.offers(value), "not a link audio quality of the device");
        client.setLinkAudioQuality(zone, value);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
