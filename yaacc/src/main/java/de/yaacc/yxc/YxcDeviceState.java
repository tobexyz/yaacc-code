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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What yaacc knows about one device that speaks the protocol, for the receiver row: power, input, volume, group
 * role and what plays. Immutable; every {@code with...} method returns a new state, so a listener can compare
 * two states with {@link #equals} to see whether anything changed. Built from the answers of the device
 * ({@link #withZone}, {@link #withDistribution}, {@link #withPlayInfo}) and from events ({@link #withEvent}).
 */
public final class YxcDeviceState {

    /** The zone the row shows; further zones come with the settings screen. */
    public static final String MAIN_ZONE = "main";

    private static final YxcDeviceState UNKNOWN = new YxcDeviceState(false, null, null, null, null, null, null,
            YxcDistributionInfo.Role.UNKNOWN, null, null, Collections.<String>emptyList(), null, null, null);

    private final boolean reachable;
    private final String power;
    private final String input;
    private final String inputText;
    private final Integer volume;
    private final Integer maxVolume;
    private final Boolean mute;
    private final YxcDistributionInfo.Role role;
    private final String groupName;
    private final String groupId;
    private final List<String> clients;
    private final String playback;
    private final String artist;
    private final String track;

    private YxcDeviceState(boolean reachable, String power, String input, String inputText, Integer volume, Integer maxVolume,
                           Boolean mute, YxcDistributionInfo.Role role, String groupName, String groupId, List<String> clients,
                           String playback, String artist, String track) {
        this.reachable = reachable;
        this.power = power;
        this.input = input;
        this.inputText = inputText;
        this.volume = volume;
        this.maxVolume = maxVolume;
        this.mute = mute;
        this.role = role;
        this.groupName = groupName;
        this.groupId = groupId;
        this.clients = clients;
        this.playback = playback;
        this.artist = artist;
        this.track = track;
    }

    /** Nothing known yet: not reachable, role unknown. */
    public static YxcDeviceState unknown() {
        return UNKNOWN;
    }

    /** The device did not answer; what it said before is dropped except the group, which is kept for the row. */
    public YxcDeviceState unreachable() {
        return new YxcDeviceState(false, null, null, null, null, null, null, role, groupName, groupId, clients, null, null, null);
    }

    public YxcDeviceState withZone(YxcZoneStatus status) {
        return new YxcDeviceState(true, status.getPower(), status.getInput(), status.getInputText(), status.getVolume(),
                status.getMaxVolume(), status.getMute(), role, groupName, groupId, clients, playback, artist, track);
    }

    public YxcDeviceState withDistribution(YxcDistributionInfo info) {
        return new YxcDeviceState(true, power, input, inputText, volume, maxVolume, mute, info.getRole(),
                info.isNullGroup() ? null : info.getGroupName(), info.isNullGroup() ? null : info.getGroupId(),
                Collections.unmodifiableList(new ArrayList<>(info.getClients())), playback, artist, track);
    }

    /** @param info the play info, or {@code null} when the device has none (the row then shows no title) */
    public YxcDeviceState withPlayInfo(YxcPlayInfo info) {
        if (info == null) {
            return new YxcDeviceState(reachable, power, input, inputText, volume, maxVolume, mute, role, groupName, groupId, clients,
                    null, null, null);
        }
        return new YxcDeviceState(true, power, input, inputText, volume, maxVolume, mute, role, groupName, groupId, clients,
                info.getPlayback(), info.getArtist(), info.getTrack());
    }

    /**
     * Applies what an event says directly (power, volume, mute, input of the zone). Flags that say "read again"
     * are not applied here, see {@link YxcEventPlan}.
     */
    public YxcDeviceState withEvent(YxcEvent event) {
        YxcEvent.ZoneBlock block = event.getZones().get(MAIN_ZONE);
        if (block == null) {
            return this;
        }
        return new YxcDeviceState(reachable,
                block.getPower() != null ? block.getPower() : power,
                block.getInput() != null ? block.getInput() : input,
                block.getInput() != null && !block.getInput().equalsIgnoreCase(String.valueOf(input)) ? null : inputText,
                block.getVolume() != null ? block.getVolume() : volume,
                maxVolume,
                block.getMute() != null ? block.getMute() : mute,
                role, groupName, groupId, clients, playback, artist, track);
    }

    public boolean isReachable() {
        return reachable;
    }

    /** @return {@code on}, {@code standby} (the words of the device) or {@code null} when unknown */
    public String getPower() {
        return power;
    }

    public boolean isPowerOn() {
        return "on".equalsIgnoreCase(power);
    }

    public String getInput() {
        return input;
    }

    /** @return the name the device gives the input, {@code null} when it did not (or the input changed by an event since) */
    public String getInputText() {
        return inputText;
    }

    public boolean isLinkInput() {
        return YxcZoneFeatures.GROUP_INPUT_ID.equalsIgnoreCase(input);
    }

    public Integer getVolume() {
        return volume;
    }

    public Integer getMaxVolume() {
        return maxVolume;
    }

    public Boolean getMute() {
        return mute;
    }

    /**
     * True for a device that left its group but still reports the client role: the group id is zeros and the input is
     * the link input. It stays so until the input is set to something else (docs/tech.md), so it needs a "leave".
     */
    public boolean isStuckClient() {
        return role == YxcDistributionInfo.Role.CLIENT && groupId == null;
    }

    public YxcDistributionInfo.Role getRole() {
        return role;
    }

    /** @return the name of the group the device is in, {@code null} when it is in none or the name is not known */
    public String getGroupName() {
        return groupName;
    }

    /** @return the number of clients of a server, 0 for other roles */
    public int getClientCount() {
        return role == YxcDistributionInfo.Role.SERVER ? clients.size() : 0;
    }

    /**
     * @return the id of the group the device is in, {@code null} when it is in none. Used to find the other members;
     * it is not shown.
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * @return the addresses a server lists as its clients (empty for any other role: a client's own list says
     * nothing useful, see docs/tech.md)
     */
    public List<String> getClientAddresses() {
        return role == YxcDistributionInfo.Role.SERVER ? clients : Collections.<String>emptyList();
    }

    /** @return {@code play}, {@code pause}, {@code stop} (the words of the device) or {@code null} */
    public String getPlayback() {
        return playback;
    }

    public String getArtist() {
        return artist;
    }

    public String getTrack() {
        return track;
    }

    /**
     * Whether the receiver row would look the same: power, input, group and reachability. Volume, mute and the
     * playing track are kept in the state but not shown yet, so a change of those alone does not need a redraw.
     */
    public boolean sameForRow(YxcDeviceState other) {
        return other != null && reachable == other.reachable && Objects.equals(power, other.power)
                && Objects.equals(input, other.input) && Objects.equals(inputText, other.inputText)
                && role == other.role && Objects.equals(groupName, other.groupName)
                && Objects.equals(groupId, other.groupId) && getClientAddresses().equals(other.getClientAddresses());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof YxcDeviceState)) {
            return false;
        }
        YxcDeviceState other = (YxcDeviceState) o;
        return reachable == other.reachable && clients.equals(other.clients) && Objects.equals(groupId, other.groupId)
                && Objects.equals(power, other.power) && Objects.equals(input, other.input)
                && Objects.equals(inputText, other.inputText)
                && Objects.equals(volume, other.volume) && Objects.equals(maxVolume, other.maxVolume)
                && Objects.equals(mute, other.mute) && role == other.role
                && Objects.equals(groupName, other.groupName) && Objects.equals(playback, other.playback)
                && Objects.equals(artist, other.artist) && Objects.equals(track, other.track);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reachable, power, input, inputText, volume, maxVolume, mute, role, groupName, groupId, clients,
                playback, artist, track);
    }

    /** Names and tracks are not part of the text, so a log line does not carry them. */
    @Override
    public String toString() {
        return "YxcDeviceState{reachable=" + reachable + ", power=" + power + ", input=" + input + ", role=" + role + "}";
    }
}
