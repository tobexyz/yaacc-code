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
     * Android-free snapshot of a running player: its id (identity only, used
     * to look the player up again), the id of the device it plays on
     * ({@code Player#getDeviceId()}), whether it is currently playing, and its
     * creation sequence ({@code PlayerService#getPlayerCreationSequence}),
     * where higher means added later. Player ids say nothing about age:
     * {@code AVTransportPlayer} ids are random and local ids are fixed per type.
     */
    public static final class PlayerRef {
        private final int playerId;
        private final String deviceId;
        private final boolean playing;
        private final long sequence;

        public PlayerRef(int playerId, String deviceId, boolean playing, long sequence) {
            this.playerId = playerId;
            this.deviceId = deviceId;
            this.playing = playing;
            this.sequence = sequence;
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

        public long sequence() {
            return sequence;
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
     * Receivers in the given order, each followed by its sessions, oldest
     * first (ascending creation sequence); sessions with an unknown deviceId
     * go last, grouped per device. Never drops a session.
     * <p>
     * Unknown-device sessions get no RECEIVER row (the caller labels them
     * from {@link Row#deviceId()}); their device groups are ordered by each
     * group's oldest session. Equal sequences fall back to playerId so the
     * result never depends on input order. A duplicate receiver id is listed only at its
     * first occurrence, a {@code null} receiver id is skipped, and sessions
     * with a {@code null} deviceId form their own trailing group. With no
     * receivers at all, every session is still listed. {@code null} lists
     * are treated as empty.
     */
    public static List<Row> group(List<String> receiverDeviceIds, List<PlayerRef> players) {
        // Sorting once up front gives creation order inside every group and
        // orders the trailing groups by their oldest session.
        Map<String, List<PlayerRef>> sessionsByDevice = new LinkedHashMap<>();
        for (PlayerRef player : sortedOldestFirst(players)) {
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
     * Among the device's playing sessions, the newest (highest creation
     * sequence); if none is playing, the newest session overall; null if the
     * device has no sessions. Independent of input order (players arrive in
     * HashMap order); equal sequences fall back to the higher playerId.
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

    private static final Comparator<PlayerRef> OLDEST_FIRST =
            Comparator.comparingLong(PlayerRef::sequence).thenComparingInt(PlayerRef::playerId);

    private static boolean ranksAbove(PlayerRef candidate, PlayerRef current) {
        if (candidate.playing() != current.playing()) {
            return candidate.playing();
        }
        return OLDEST_FIRST.compare(candidate, current) > 0;
    }

    /** Copy without null entries, oldest first (also the mini-player list's order, see {@link MiniPlayerRows}). */
    static List<PlayerRef> sortedOldestFirst(List<PlayerRef> players) {
        List<PlayerRef> sorted = new ArrayList<>();
        if (players != null) {
            for (PlayerRef player : players) {
                if (player != null) {
                    sorted.add(player);
                }
            }
        }
        sorted.sort(OLDEST_FIRST);
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
