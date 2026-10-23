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

import java.util.Collections;
import java.util.List;

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
 * STUB (spec 2026-10-23-unified-device-sheet, Group 1): the API is final,
 * the behavior is implemented in Group 2.
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
     */
    public static List<Row> group(List<String> receiverDeviceIds, List<PlayerRef> players) {
        return Collections.emptyList();
    }

    /**
     * Playing session first, else highest playerId; null if none.
     */
    public static PlayerRef primarySession(String deviceId, List<PlayerRef> players) {
        return null;
    }
}
