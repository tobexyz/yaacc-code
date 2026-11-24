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
package de.yaacc.sendspin.client;

import de.yaacc.sendspin.session.ControllerMessages;

/**
 * The position in the current track from a {@code metadata} state and the server clock (spec, "Calculating current track
 * position"), and the handling of scheduled metadata updates: the client keeps the current state and at most one pending update
 * (spec, "Scheduled metadata updates"). Pure.
 */
public final class TrackPosition {

    private TrackPosition() {
    }

    /**
     * @param serverNowMicros the server clock now (see {@link ServerClock})
     * @return the position in milliseconds, clamped to the track, or -1 if the state has no progress
     */
    public static long positionMs(ControllerMessages.Metadata m, long serverNowMicros) {
        if (m == null || m.progress == null) {
            return -1;
        }
        ControllerMessages.Progress p = m.progress;
        // microseconds * speed (times 1000) / 1000000 is milliseconds times 1000: one division by 1,000,000 gives milliseconds
        long calculated = p.trackProgressMs + Math.floorDiv((serverNowMicros - m.timestamp) * (long) p.playbackSpeed, 1_000_000L);
        if (p.trackDurationMs != 0) {
            calculated = Math.min(calculated, p.trackDurationMs);
        }
        return Math.max(calculated, 0);
    }

    /** The current state and at most one pending update. One thread, or locked by the caller. */
    public static final class Tracker {
        private ControllerMessages.Metadata current;
        private ControllerMessages.Metadata pending;

        /** A {@code metadata} object arrived: in the past or present it is the current state, in the future it is the pending one. */
        public void accept(ControllerMessages.Metadata m, long serverNowMicros) {
            if (m.timestamp > serverNowMicros) {
                pending = m;
            } else {
                current = m;
                pending = null;
            }
        }

        /** The state to show at this time: a pending update whose time has come is applied first. */
        public ControllerMessages.Metadata current(long serverNowMicros) {
            if (pending != null && pending.timestamp <= serverNowMicros) {
                current = pending;
                pending = null;
            }
            return current;
        }

        public ControllerMessages.Metadata pending() {
            return pending;
        }

        public void clear() {
            current = null;
            pending = null;
        }
    }
}
