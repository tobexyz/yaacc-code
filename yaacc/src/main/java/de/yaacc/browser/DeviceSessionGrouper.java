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
package de.yaacc.browser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groups running player sessions under the receiver device they play on.
 * Receivers and players are not 1:1 (a device can run several players at
 * once), so the result is an ordered list of receiver rows, each followed by
 * zero or more session rows.
 * <p>
 * Deliberately has no {@code android.*} imports - same testability
 * precedent as {@link CardGridSpanCount} - so it can be unit-tested on the
 * plain JVM without Robolectric.
 * <p>
 * Device ids come from untrusted LAN devices (SSDP/UDN), so they are treated
 * as opaque strings: {@code UpnpClient.LOCAL_UID} gets no special handling,
 * a duplicate receiver id is only listed at its first occurrence, and a
 * {@code null} id never throws.
 */
public final class DeviceSessionGrouper {

    private DeviceSessionGrouper() {
    }

    /**
     * Android-free snapshot of a running player: its id, the id of the
     * device it plays on ({@code Player#getDeviceId()}), and whether it is
     * currently playing.
     */
    public static final class PlayerRef {
        private final int playerId;
        private final String deviceId;
        private final boolean playing;

        public PlayerRef(int playerId, String deviceId, boolean playing) {
            this.playerId = playerId;
            this.deviceId = deviceId;
            this.playing = playing;
        }

        public int playerId() {
            return playerId;
        }

        public String deviceId() {
            return deviceId;
        }

        public boolean playing() {
            return playing;
        }
    }

    /**
     * One row of the grouped list: either a receiver or a player session.
     * {@link #playerId()} is 0 for RECEIVER rows ({@code PlayerService}
     * never registers a player with id 0).
     */
    public static final class Row {
        public enum Kind { RECEIVER, PLAYER_SESSION }

        private final Kind kind;
        private final String deviceId;
        private final int playerId;

        private Row(Kind kind, String deviceId, int playerId) {
            this.kind = kind;
            this.deviceId = deviceId;
            this.playerId = playerId;
        }

        public Kind kind() {
            return kind;
        }

        public String deviceId() {
            return deviceId;
        }

        public int playerId() {
            return playerId;
        }
    }

    /**
     * Receivers in the given order, each followed by its sessions
     * (ascending playerId); sessions with an unknown deviceId go last,
     * grouped per device. Never drops a session.
     * <p>
     * Unknown-device sessions get no RECEIVER row (the caller labels them
     * from {@link Row#deviceId()}); their device groups are ordered by each
     * group's lowest playerId. A duplicate receiver id is listed only at its
     * first occurrence, a {@code null} receiver id is skipped, and sessions
     * with a {@code null} deviceId form their own trailing group. With no
     * receivers at all, every session is still listed. {@code null} lists
     * are treated as empty.
     */
    public static List<Row> group(List<String> receiverDeviceIds, List<PlayerRef> players) {
        // Sorting once up front gives ascending playerId inside every group
        // and orders the trailing groups by their lowest playerId.
        Map<String, List<PlayerRef>> sessionsByDevice = new LinkedHashMap<>();
        for (PlayerRef player : sortedByPlayerId(players)) {
            List<PlayerRef> sessions = sessionsByDevice.get(player.deviceId());
            if (sessions == null) {
                sessions = new ArrayList<>();
                sessionsByDevice.put(player.deviceId(), sessions);
            }
            sessions.add(player);
        }

        Set<String> receivers = new LinkedHashSet<>();
        if (receiverDeviceIds != null) {
            for (String receiverId : receiverDeviceIds) {
                if (receiverId != null) {
                    receivers.add(receiverId);
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        for (String receiverId : receivers) {
            rows.add(new Row(Row.Kind.RECEIVER, receiverId, 0));
            addSessionRows(rows, sessionsByDevice.remove(receiverId));
        }
        // Whatever is left belongs to devices that aren't receivers.
        for (List<PlayerRef> sessions : sessionsByDevice.values()) {
            addSessionRows(rows, sessions);
        }
        return rows;
    }

    /**
     * Among the device's playing sessions, the highest playerId; if none
     * is playing, the highest playerId overall; null if the device has no
     * sessions. Independent of input order (players arrive in HashMap
     * order).
     */
    public static PlayerRef primarySession(String deviceId, List<PlayerRef> players) {
        if (players == null) {
            return null;
        }
        PlayerRef best = null;
        for (PlayerRef candidate : players) {
            if (candidate == null || !Objects.equals(deviceId, candidate.deviceId())) {
                continue;
            }
            if (best == null || ranksAbove(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean ranksAbove(PlayerRef candidate, PlayerRef current) {
        if (candidate.playing() != current.playing()) {
            return candidate.playing();
        }
        return candidate.playerId() > current.playerId();
    }

    private static List<PlayerRef> sortedByPlayerId(List<PlayerRef> players) {
        List<PlayerRef> sorted = new ArrayList<>();
        if (players != null) {
            for (PlayerRef player : players) {
                if (player != null) {
                    sorted.add(player);
                }
            }
        }
        sorted.sort(Comparator.comparingInt(PlayerRef::playerId));
        return sorted;
    }

    private static void addSessionRows(List<Row> rows, List<PlayerRef> sessions) {
        if (sessions == null) {
            return;
        }
        for (PlayerRef session : sessions) {
            rows.add(new Row(Row.Kind.PLAYER_SESSION, session.deviceId(), session.playerId()));
        }
    }
}
