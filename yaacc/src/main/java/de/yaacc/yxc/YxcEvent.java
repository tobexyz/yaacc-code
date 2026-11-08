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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One UDP datagram pushed by a device, reduced to the blocks and update flags yaacc reacts to. Keys it does not know
 * are not kept. A datagram that cannot be read is {@link #unparsable()}: the receiver should then read the
 * whole state again.
 */
public final class YxcEvent {
    private static final YxcEvent UNPARSABLE = new YxcEvent(false, Collections.<String, ZoneBlock>emptyMap(), null,
            Collections.<String>emptySet(), false, false, false, false, null);

    /** The block of a zone ({@code main}, {@code zone2}, ...). Values the event does not carry are {@code null}. */
    public static final class ZoneBlock {
        private final String power;
        private final Integer volume;
        private final Boolean mute;
        private final String input;
        private final boolean statusUpdated;
        private final boolean playInfoUpdated;
        private final boolean signalInfoUpdated;

        ZoneBlock(String power, Integer volume, Boolean mute, String input, boolean statusUpdated, boolean playInfoUpdated,
                  boolean signalInfoUpdated) {
            this.power = power;
            this.volume = volume;
            this.mute = mute;
            this.input = input;
            this.statusUpdated = statusUpdated;
            this.playInfoUpdated = playInfoUpdated;
            this.signalInfoUpdated = signalInfoUpdated;
        }

        public String getPower() {
            return power;
        }

        public Integer getVolume() {
            return volume;
        }

        public Boolean getMute() {
            return mute;
        }

        public String getInput() {
            return input;
        }

        /** The rest of the zone status changed: read {@code <zone>/getStatus}. */
        public boolean isStatusUpdated() {
            return statusUpdated;
        }

        public boolean isPlayInfoUpdated() {
            return playInfoUpdated;
        }

        /** The signal information (format, sample rate) changed; seen with every track change, no need to read anything for it. */
        public boolean isSignalInfoUpdated() {
            return signalInfoUpdated;
        }
    }

    /** The network source block. */
    public static final class NetUsbBlock {
        private final Integer playTime;
        private final boolean playInfoUpdated;
        private final boolean presetInfoUpdated;
        private final boolean playQueueUpdated;
        private final boolean accountUpdated;

        NetUsbBlock(Integer playTime, boolean playInfoUpdated, boolean presetInfoUpdated, boolean playQueueUpdated,
                    boolean accountUpdated) {
            this.playTime = playTime;
            this.playInfoUpdated = playInfoUpdated;
            this.presetInfoUpdated = presetInfoUpdated;
            this.playQueueUpdated = playQueueUpdated;
            this.accountUpdated = accountUpdated;
        }

        /** @return the play position in seconds, {@code null} if the event carries none */
        public Integer getPlayTime() {
            return playTime;
        }

        public boolean isPlayInfoUpdated() {
            return playInfoUpdated;
        }

        public boolean isPresetInfoUpdated() {
            return presetInfoUpdated;
        }

        /** The play queue changed ({@code "play_queue":{"updated":true}}). */
        public boolean isPlayQueueUpdated() {
            return playQueueUpdated;
        }

        /** The account info of the streaming services changed ({@code "account_updated":true}); seen when a device wakes up. */
        public boolean isAccountUpdated() {
            return accountUpdated;
        }
    }

    private final boolean parsable;
    private final Map<String, ZoneBlock> zones;
    private final NetUsbBlock netUsb;
    private final Set<String> blockNames;
    private final boolean tunerPlayInfoUpdated;
    private final boolean distInfoUpdated;
    private final boolean clockSettingsUpdated;
    private final boolean systemFuncStatusUpdated;
    private final String deviceId;

    YxcEvent(boolean parsable, Map<String, ZoneBlock> zones, NetUsbBlock netUsb, Set<String> blockNames,
             boolean tunerPlayInfoUpdated, boolean distInfoUpdated, boolean clockSettingsUpdated,
             boolean systemFuncStatusUpdated, String deviceId) {
        this.parsable = parsable;
        this.zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
        this.netUsb = netUsb;
        this.blockNames = Collections.unmodifiableSet(new LinkedHashSet<>(blockNames));
        this.tunerPlayInfoUpdated = tunerPlayInfoUpdated;
        this.distInfoUpdated = distInfoUpdated;
        this.clockSettingsUpdated = clockSettingsUpdated;
        this.systemFuncStatusUpdated = systemFuncStatusUpdated;
        this.deviceId = deviceId;
    }

    /**
     * @return the {@code device_id} the device puts at the top level of every event (the same value as {@code device_id} of
     * {@code getDeviceInfo} and the last 12 hex digits of the UPnP UDN), {@code null} if the datagram has none. Compare it
     * case-insensitively; it is a device identifier: keep it out of log lines.
     */
    public String getDeviceId() {
        return deviceId;
    }

    /** The result for a datagram that is no JSON object (or too large / nested to be one of ours). */
    public static YxcEvent unparsable() {
        return UNPARSABLE;
    }

    public boolean isParsable() {
        return parsable;
    }

    /** @return the zone blocks by zone id in datagram order */
    public Map<String, ZoneBlock> getZones() {
        return zones;
    }

    /** @return the network source block, {@code null} if the datagram has none */
    public NetUsbBlock getNetUsb() {
        return netUsb;
    }

    /** @return the names of the known blocks that are present: zone ids, {@code netusb}, {@code tuner}, {@code dist}, {@code clock}, {@code system} */
    public Set<String> getBlockNames() {
        return blockNames;
    }

    public boolean hasBlock(String name) {
        return blockNames.contains(name);
    }

    public boolean isTunerPlayInfoUpdated() {
        return tunerPlayInfoUpdated;
    }

    /** The group state changed: read {@code dist/getDistributionInfo}. */
    public boolean isDistInfoUpdated() {
        return distInfoUpdated;
    }

    public boolean isClockSettingsUpdated() {
        return clockSettingsUpdated;
    }

    /** The device-level settings changed: read {@code system/getFuncStatus}. */
    public boolean isSystemFuncStatusUpdated() {
        return systemFuncStatusUpdated;
    }
}
