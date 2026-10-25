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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;

/**
 * Plain-Java decisions of the mini-player list: which rows exist in which order, and the key of
 * a row's album art. No Android types, JVM-testable.
 */
public final class MiniPlayerRows {

    private MiniPlayerRows() {
    }

    /**
     * The rows of the mini-player in the device sheet's session order: creation sequence
     * ascending (oldest first), equal sequences by player id (see {@link DeviceSessionGrouper}),
     * so the result never depends on input order. Null entries are skipped, a null or unknown
     * deviceId is fine, and of two entries with the same player id only the first one in that
     * order is kept (a {@code RecyclerView} with stable ids must never see an id twice). Returns
     * a new list; {@code players} is not modified and may be null.
     */
    public static List<PlayerRef> order(List<PlayerRef> players) {
        List<PlayerRef> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (PlayerRef player : DeviceSessionGrouper.sortedOldestFirst(players)) {
            if (seen.add(player.playerId())) {
                ordered.add(player);
            }
        }
        return ordered;
    }

    /**
     * The identity of what a row shows as album art: the player id and the art URI (null for no
     * art) and nothing else. In particular it does not depend on {@code Player#getIcon()}, which
     * can queue a download of its own on every call; see {@link MiniPlayerArtKeys}.
     */
    public static String artKey(int playerId, String artUri) {
        return playerId + ":" + artUri;
    }
}
