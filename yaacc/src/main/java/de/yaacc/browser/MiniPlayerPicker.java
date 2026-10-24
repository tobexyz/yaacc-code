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

import java.util.List;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;

/**
 * Picks the one "featured" session the mini-player bar shows: the playing
 * session with the highest creation sequence, else the highest sequence
 * overall, across all devices. Same ordering rules as
 * {@link DeviceSessionGrouper#primarySession}, minus the device filter.
 * <p>
 * Deliberately has no {@code android.*} imports so it can be unit-tested on
 * the plain JVM.
 */
public final class MiniPlayerPicker {

    private MiniPlayerPicker() {
    }

    /**
     * The featured session, or null when there is nothing to show. A playing
     * session beats any non-playing one; then the higher creation sequence
     * wins; equal sequences fall back to the higher playerId, so the result
     * never depends on input order. Null entries are skipped, a null or
     * unknown deviceId is fine, and the list is not modified.
     */
    public static PlayerRef pick(List<PlayerRef> players) {
        if (players == null) {
            return null;
        }
        PlayerRef best = null;
        for (PlayerRef candidate : players) {
            if (candidate == null) {
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
        if (candidate.sequence() != current.sequence()) {
            return candidate.sequence() > current.sequence();
        }
        return candidate.playerId() > current.playerId();
    }
}
