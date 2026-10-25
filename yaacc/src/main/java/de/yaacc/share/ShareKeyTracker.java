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
package de.yaacc.share;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which share keys each player used, so that the shares can be released when the
 * player ends. The keys must NOT be read from the player's item list at that moment: a player
 * clears its list when it is destroyed, and the "removed" notification is delivered later (on
 * the main thread), so the list is already empty by then. The glue therefore feeds this tracker
 * early and often ({@link #onPlayerAdded}, {@link #refresh}); keys are only ever added, never
 * forgotten, until the player is removed.
 * <p>
 * Players are tracked by identity (a replaced player can have the same id as its successor).
 * Plain Java and thread safe: it is fed from the thread that removes a player and read on the
 * main thread.
 *
 * @param <P> the player type
 */
public final class ShareKeyTracker<P> {

    private final Object lock = new Object();
    private final Map<P, Set<String>> keysByPlayer = new IdentityHashMap<>();

    /**
     * A player became active. Same as {@link #refresh}: the player's items may be set before or
     * after this call.
     */
    public void onPlayerAdded(P player, Collection<String> keys) {
        refresh(player, keys);
    }

    /**
     * Adds keys the player is known to use now (union with what was remembered). Malformed keys
     * are ignored; a player that never had a key is not stored.
     */
    public void refresh(P player, Collection<String> keys) {
        if (player == null || keys == null || keys.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Set<String> known = keysByPlayer.get(player);
            for (String key : keys) {
                if (ShareRegistry.isWellFormedKey(key)) {
                    if (known == null) {
                        known = new HashSet<>();
                        keysByPlayer.put(player, known);
                    }
                    known.add(key);
                }
            }
        }
    }

    /**
     * The player ended: forgets it.
     *
     * @return the keys of this player that no other tracked player uses, i.e. the shares to
     * release; empty for an unknown player or a second call
     */
    public Set<String> onPlayerRemoved(P player) {
        if (player == null) {
            return Collections.emptySet();
        }
        synchronized (lock) {
            Set<String> own = keysByPlayer.remove(player);
            if (own == null) {
                return Collections.emptySet();
            }
            Set<String> result = new HashSet<>(own);
            for (Set<String> others : keysByPlayer.values()) {
                result.removeAll(others);
            }
            return result;
        }
    }

    /** @return a copy of the keys remembered for the player (empty if unknown) */
    public Set<String> keysOf(P player) {
        synchronized (lock) {
            Set<String> known = player == null ? null : keysByPlayer.get(player);
            return known == null ? Collections.emptySet() : new HashSet<>(known);
        }
    }

    /** @return how many players have at least one remembered key (bookkeeping size, for tests) */
    public int trackedPlayers() {
        synchronized (lock) {
            return keysByPlayer.size();
        }
    }

    /**
     * The share key in the path of a proxy URL ({@code /proxy/<key>} or
     * {@code /proxy/<deviceId>/<key>}): the last segment, if the path starts with the proxy
     * segment and the segment is a well-formed key.
     *
     * @param path         the decoded URL path, may be null
     * @param proxySegment the first path segment of the proxy routes, without slashes
     * @return the key, or null if the path is not a proxy path
     */
    public static String keyOfProxyPath(String path, String proxySegment) {
        if (path == null || proxySegment == null || !path.startsWith("/" + proxySegment + "/")) {
            return null;
        }
        String key = path.substring(path.lastIndexOf('/') + 1);
        return ShareRegistry.isWellFormedKey(key) ? key : null;
    }
}
