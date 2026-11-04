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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The YXC calls of one device (the HTTP/JSON control API of Yamaha MusicCast devices) as methods that return small
 * immutable values. Unlike the open-source libraries it checks every answer: an HTTP status outside 200..299 or a
 * {@code response_code} other than 0 is a {@link YxcException}. Thread-safe if the transport is.
 */
public final class YxcClient {
    /**
     * A sanity bound on the client addresses of one {@code setServerInfo} call, so that a request stays small. The limit
     * of a device is {@code distribution.client_max} of its features (9 or 19 on devices seen); the group service checks it.
     */
    public static final int MAX_CLIENT_ADDRESSES = 64;

    private static final Pattern ZONE = Pattern.compile("main|zone2|zone3|zone4");
    private static final Pattern GROUP_ID = Pattern.compile("[0-9A-Fa-f]{32}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Za-z.:-]{1,64}");

    private final YxcTransport transport;

    public YxcClient(YxcTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport is null");
        }
        this.transport = transport;
    }

    // ---- reads

    public YxcDeviceInfo getDeviceInfo() throws YxcException {
        return YxcDeviceInfo.fromJson(get("system/getDeviceInfo"));
    }

    public YxcFeatures getFeatures() throws YxcException {
        return YxcFeatures.fromJson(get("system/getFeatures"));
    }

    public YxcFuncStatus getFuncStatus() throws YxcException {
        return YxcFuncStatus.fromJson(get("system/getFuncStatus"));
    }

    /** @param zone {@code main}, {@code zone2}, {@code zone3} or {@code zone4} */
    public YxcZoneStatus getZoneStatus(String zone) throws YxcException {
        return YxcZoneStatus.fromJson(get(checkedZone(zone) + "/getStatus"));
    }

    public YxcDistributionInfo getDistributionInfo() throws YxcException {
        return YxcDistributionInfo.fromJson(get("dist/getDistributionInfo"));
    }

    /** The names of zones, inputs and sound programs as the owner set them. */
    public YxcNameText getNameText() throws YxcException {
        return YxcNameText.fromJson(get("system/getNameText"));
    }

    public YxcPlayInfo getPlayInfo() throws YxcException {
        return YxcPlayInfo.fromJson(get("netusb/getPlayInfo"));
    }

    /** What the tuner plays; only meaningful while the zone input is the tuner. */
    public YxcTunerInfo getTunerPlayInfo() throws YxcException {
        return YxcTunerInfo.fromJson(get("tuner/getPlayInfo"));
    }

    // ---- zone setters used by the group logic

    public void setPower(String zone, boolean on) throws YxcException {
        get(checkedZone(zone) + "/setPower?power=" + (on ? "on" : "standby"));
    }

    /**
     * Sets the volume of a zone to a value of the device's own scale ({@code 0} to the {@code max_volume} of the status).
     * The devices of a group keep their own volumes, so this changes only this device.
     */
    public void setVolume(String zone, int volume) throws YxcException {
        if (volume < 0) {
            throw new IllegalArgumentException("volume < 0");
        }
        get(checkedZone(zone) + "/setVolume?volume=" + volume);
    }

    public void setMute(String zone, boolean muted) throws YxcException {
        get(checkedZone(zone) + "/setMute?enable=" + muted);
    }

    /** @param program an id from the zone's {@code sound_program_list} */
    public void setSoundProgram(String zone, String program) throws YxcException {
        get(checkedZone(zone) + "/setSoundProgram?program=" + encode(checkedToken(program)));
    }

    /** @param minutes {@code 0} (off), {@code 30}, {@code 60}, {@code 90} or {@code 120} */
    public void setSleep(String zone, int minutes) throws YxcException {
        if (minutes < 0 || minutes > 120) {
            throw new IllegalArgumentException("sleep timer out of range");
        }
        get(checkedZone(zone) + "/setSleep?sleep=" + minutes);
    }

    /** @param mode an id from the zone's {@code equalizer_mode_list} */
    public void setEqualizerMode(String zone, String mode) throws YxcException {
        get(checkedZone(zone) + "/setEqualizer?mode=" + encode(checkedToken(mode)));
    }

    /**
     * Sets one band of the equalizer.
     *
     * @param band {@code low}, {@code mid} or {@code high}
     */
    public void setEqualizerBand(String zone, String band, int value) throws YxcException {
        if (!"low".equals(band) && !"mid".equals(band) && !"high".equals(band)) {
            throw new IllegalArgumentException("not an equalizer band");
        }
        get(checkedZone(zone) + "/setEqualizer?" + band + "=" + value);
    }

    /** @param control an id from the zone's {@code link_control_list} */
    public void setLinkControl(String zone, String control) throws YxcException {
        get(checkedZone(zone) + "/setLinkControl?control=" + encode(checkedToken(control)));
    }

    /** @param delay an id from the zone's {@code link_audio_delay_list} */
    public void setLinkAudioDelay(String zone, String delay) throws YxcException {
        get(checkedZone(zone) + "/setLinkAudioDelay?delay=" + encode(checkedToken(delay)));
    }

    /** @param mode an id from the zone's {@code link_audio_quality_list} */
    public void setLinkAudioQuality(String zone, String mode) throws YxcException {
        get(checkedZone(zone) + "/setLinkAudioQuality?mode=" + encode(checkedToken(mode)));
    }

    /** @param mode the mode word, empty for none (the open-source clients send the parameter empty) */
    public void setInput(String zone, String inputId, String mode) throws YxcException {
        String checkedMode = mode == null ? "" : mode;
        if (checkedMode.length() > 64) {
            throw new IllegalArgumentException("mode too long");
        }
        get(checkedZone(zone) + "/setInput?input=" + encode(checkedToken(inputId)) + "&mode=" + encode(checkedMode));
    }

    // ---- device-level settings (system)

    /** @param value the display brightness, inside the {@code dimmer} range of the system features ({@code -1} is automatic where listed) */
    public void setDimmer(int value) throws YxcException {
        get("system/setDimmer?value=" + value);
    }

    public void setAutoPowerStandby(boolean enabled) throws YxcException {
        get("system/setAutoPowerStandby?enable=" + enabled);
    }

    // ---- the device's own source (network source, tuner)

    /** @param playback {@code play}, {@code pause}, {@code stop}, {@code previous} or {@code next} */
    public void setPlayback(String playback) throws YxcException {
        if (!"play".equals(playback) && !"pause".equals(playback) && !"stop".equals(playback)
                && !"previous".equals(playback) && !"next".equals(playback)) {
            throw new IllegalArgumentException("not a playback command");
        }
        get("netusb/setPlayback?playback=" + playback);
    }

    /** @param mode a value of the play info's {@code repeat_available}: {@code off}, {@code one} or {@code all} */
    public void setRepeat(String mode) throws YxcException {
        if (!"off".equals(mode) && !"one".equals(mode) && !"all".equals(mode)) {
            throw new IllegalArgumentException("not a repeat mode");
        }
        get("netusb/setRepeat?mode=" + mode);
    }

    /** @param mode a value of the play info's {@code shuffle_available}: {@code off} or {@code on} */
    public void setShuffle(String mode) throws YxcException {
        if (!"off".equals(mode) && !"on".equals(mode)) {
            throw new IllegalArgumentException("not a shuffle mode");
        }
        get("netusb/setShuffle?mode=" + mode);
    }

    /** Starts the FM seek: the tuner scans up or down by itself until it finds a station (play info {@code auto_scan}). */
    public void seekFm(boolean up) throws YxcException {
        get("tuner/setFreq?band=fm&tuning=" + (up ? "auto_up" : "auto_down") + "&num=0");
    }

    /** Steps the FM frequency by one step (50 kHz on the measured device); the tuner also switches to FM if it was on DAB. */
    public void stepFm(boolean up) throws YxcException {
        get("tuner/setFreq?band=fm&tuning=" + (up ? "up" : "down") + "&num=0");
    }

    /** Stops an FM scan that is running; the tuner stays on the station it reached. */
    public void cancelFmScan() throws YxcException {
        get("tuner/setFreq?band=fm&tuning=cancel&num=0");
    }

    /** @param band {@code fm} or {@code dab}: the tuner changes its band (the zone input stays the tuner) */
    public void setTunerBand(String band) throws YxcException {
        if (!"fm".equals(band) && !"dab".equals(band)) {
            throw new IllegalArgumentException("not a tuner band");
        }
        get("tuner/setBand?band=" + band);
    }

    /** Switches the DAB tuner to the next or previous service. */
    public void stepDabService(boolean next) throws YxcException {
        get("tuner/setDabService?dir=" + (next ? "next" : "previous"));
    }

    // ---- presets and recently played

    /** The presets of the network source; the position in the list is the preset number. */
    public List<YxcPresets.Entry> getNetPresets() throws YxcException {
        return YxcPresets.netPresets(get("netusb/getPresetInfo"));
    }

    /** The recently played radio stations and streaming entries (the tracks of UPnP servers are left out). */
    public List<YxcPresets.Entry> getRecentItems() throws YxcException {
        return YxcPresets.recentItems(get("netusb/getRecentInfo"));
    }

    /** @param band {@code fm} or {@code dab} */
    public List<YxcPresets.Entry> getTunerPresets(String band) throws YxcException {
        return YxcPresets.tunerPresets(get("tuner/getPresetInfo?band=" + checkedBand(band)), band);
    }

    /** @param number the position in the list of {@link #getNetPresets()} (1 to 100) */
    public void recallNetPreset(int number) throws YxcException {
        get("netusb/recallPreset?zone=main&num=" + checkedPresetNumber(number));
    }

    /** @param number the position in the list of {@link #getRecentItems()} as the device lists it (1 to 100) */
    public void recallRecentItem(int number) throws YxcException {
        get("netusb/recallRecentItem?zone=main&num=" + checkedPresetNumber(number));
    }

    /** @param band {@code fm} or {@code dab}; the tuner switches to that band */
    public void recallTunerPreset(String band, int number) throws YxcException {
        get("tuner/recallPreset?zone=main&band=" + checkedBand(band) + "&num=" + checkedPresetNumber(number));
    }

    private static String checkedBand(String band) {
        if (!"fm".equals(band) && !"dab".equals(band)) {
            throw new IllegalArgumentException("not a tuner band");
        }
        return band;
    }

    private static int checkedPresetNumber(int number) {
        if (number < 1 || number > YxcSourceCapabilities.MAX_PRESETS) {
            throw new IllegalArgumentException("preset number out of range");
        }
        return number;
    }

    // ---- group calls ("dist")

    /**
     * Makes the zone of this device the server of a group, or adds / removes clients of the group it serves.
     *
     * @param groupId   32 hex digits
     * @param zone      the zone that serves the group
     * @param clientIps one to {@link #MAX_CLIENT_ADDRESSES} addresses of the other devices
     */
    public void setServerInfo(String groupId, String zone, YxcClientListAction action, List<String> clientIps) throws YxcException {
        checkedGroupId(groupId);
        checkedZone(zone);
        if (action == null) {
            throw new IllegalArgumentException("action is null");
        }
        JSONObject body = new JSONObject();
        put(body, "group_id", groupId);
        put(body, "zone", zone);
        put(body, "type", action.wireValue());
        put(body, "client_list", addresses(clientIps));
        post("dist/setServerInfo", body);
    }

    /** Ends the server role of this device; its group id returns to zeros. */
    public void clearServerInfo() throws YxcException {
        post("dist/setServerInfo", emptyGroup());
    }

    /**
     * Makes a zone of this device a client of the group of a server. The zone goes as a list, the server as a string.
     * The device refuses this while it is a server itself.
     */
    public void setClientInfo(String groupId, String zone, String serverIp) throws YxcException {
        checkedGroupId(groupId);
        checkedZone(zone);
        JSONObject body = new JSONObject();
        put(body, "group_id", groupId);
        put(body, "zone", new JSONArray().put(zone));
        put(body, "server_ip_address", checkedAddress(serverIp));
        post("dist/setClientInfo", body);
    }

    /** Ends the client role of this device; its group id returns to zeros. */
    public void clearClientInfo() throws YxcException {
        post("dist/setClientInfo", emptyGroup());
    }

    /** @param num the distribution number (what the device does with it is not known; see docs/tech.md) */
    public void startDistribution(int num) throws YxcException {
        if (num < 0) {
            throw new IllegalArgumentException("num < 0");
        }
        get("dist/startDistribution?num=" + num);
    }

    public void stopDistribution() throws YxcException {
        get("dist/stopDistribution");
    }

    /** @param name UTF-8, at most 128 bytes; empty selects the default name */
    public void setGroupName(String name) throws YxcException {
        if (name == null || utf8Length(name) > 128) {
            throw new IllegalArgumentException("group name must be set and at most 128 bytes");
        }
        JSONObject body = new JSONObject();
        put(body, "name", name);
        post("dist/setGroupName", body);
    }

    // ---- plumbing

    private JSONObject get(String path) throws YxcException {
        return answer(path, null);
    }

    private JSONObject post(String path, JSONObject body) throws YxcException {
        return answer(path, body);
    }

    private JSONObject answer(String path, JSONObject body) throws YxcException {
        int query = path.indexOf('?');
        String call = query < 0 ? path : path.substring(0, query);
        String text;
        try {
            text = body == null ? transport.get(path) : transport.post(path, body.toString());
        } catch (YxcHttpStatusException e) {
            throw YxcException.httpStatus(call, e.getStatus());
        } catch (IOException e) {
            throw YxcException.transport(call, e);
        }
        JSONObject json;
        try {
            json = YxcJson.parseObject(text, YxcJson.MAX_ANSWER_DEPTH);
        } catch (JSONException | NullPointerException e) {
            throw YxcException.malformed(call, "not a JSON object", e);
        }
        Integer code = YxcJson.integer(json, "response_code");
        if (code == null) {
            throw YxcException.malformed(call, "no response_code", null);
        }
        if (code != 0) {
            throw YxcException.responseCode(call, code);
        }
        return json;
    }

    private static JSONObject emptyGroup() {
        JSONObject body = new JSONObject();
        put(body, "group_id", "");
        return body;
    }

    private static JSONArray addresses(List<String> ips) {
        if (ips == null || ips.isEmpty() || ips.size() > MAX_CLIENT_ADDRESSES) {
            throw new IllegalArgumentException("1 to " + MAX_CLIENT_ADDRESSES + " client addresses are required");
        }
        JSONArray array = new JSONArray();
        for (String ip : ips) {
            array.put(checkedAddress(ip));
        }
        return array;
    }

    private static String checkedZone(String zone) {
        if (zone == null || !ZONE.matcher(zone).matches()) {
            throw new IllegalArgumentException("not a zone id");
        }
        return zone;
    }

    private static String checkedToken(String value) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("not an option id");
        }
        return value;
    }

    private static String checkedGroupId(String groupId) {
        if (groupId == null || !GROUP_ID.matcher(groupId).matches()) {
            throw new IllegalArgumentException("a group id is 32 hex digits");
        }
        return groupId;
    }

    private static String checkedAddress(String address) {
        if (address == null || !ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("not an address");
        }
        return address;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always available", e);
        }
    }

    private static void put(JSONObject body, String key, Object value) {
        try {
            body.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("a JSON object takes any key", e);
        }
    }
}
