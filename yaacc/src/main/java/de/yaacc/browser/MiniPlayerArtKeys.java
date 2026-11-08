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

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Remembers, per player id, the album-art key (see {@link MiniPlayerRows#artKey}) that a
 * mini-player row asked for last, so the art is requested (and {@code Player#getIcon()} read)
 * once per key however often the row is refreshed, scrolled out and in or rebound by a different
 * view holder. Entries of players that are gone are dropped with {@link #retainOnly}, so the map
 * never outgrows the list. Plain Java, main thread only.
 */
final class MiniPlayerArtKeys {

    private final Map<Integer, String> lastKeyByPlayer = new HashMap<>();

    /**
     * True the first time {@code key} is seen for this player (and when it differs from the last
     * key of that player); remembers it. False for the same key again and for a null key.
     */
    boolean isNewKey(int playerId, String key) {
        if (key == null || key.equals(lastKeyByPlayer.get(playerId))) {
            return false;
        }
        lastKeyByPlayer.put(playerId, key);
        return true;
    }

    /** The key last registered for the player, or null. */
    String lastKey(int playerId) {
        return lastKeyByPlayer.get(playerId);
    }

    /** Forgets every player that is not in {@code playerIds} (null means none). */
    void retainOnly(Collection<Integer> playerIds) {
        if (playerIds == null) {
            lastKeyByPlayer.clear();
            return;
        }
        Iterator<Integer> iterator = lastKeyByPlayer.keySet().iterator();
        while (iterator.hasNext()) {
            if (!playerIds.contains(iterator.next())) {
                iterator.remove();
            }
        }
    }

    void clear() {
        lastKeyByPlayer.clear();
    }
}
