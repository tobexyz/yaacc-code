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
package de.yaacc.player;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Tracks {@link Listener}s for the set of active players and notifies them when a
 * player is added to or removed from that set.
 * <p>
 * Plain Java (no Android types) so the bookkeeping can be unit-tested on the JVM.
 * {@link PlayerService} passes a main-thread executor; tests pass a synchronous one.
 * Public only so callers outside this package can implement {@link Listener}; register
 * through {@link PlayerService#addPlayerListChangeListener}.
 */
public final class PlayerListChangeNotifier {

    /**
     * Notified when the set of active players changes. Callbacks run on the
     * delivery executor (the main thread in {@link PlayerService}).
     */
    public interface Listener {
        void onPlayerAdded(Player player);

        void onPlayerRemoved(Player player);
    }

    // Copy-on-write: a listener may unregister itself (or others) from inside a
    // callback, and registration may happen on a different thread than delivery.
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Executor deliveryExecutor;

    PlayerListChangeNotifier(Executor deliveryExecutor) {
        this.deliveryExecutor = Objects.requireNonNull(deliveryExecutor, "deliveryExecutor");
    }

    void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Puts the player into the map and notifies "added" only if the map held no
     * player under that id before. {@code addPlayer} is called more than once for
     * the same player, so the decision must come from this {@code put}.
     */
    void putAndNotify(Map<Integer, Player> players, Player player) {
        Player previous = players.put(player.getId(), player);
        if (previous == null) {
            post(player, true);
        }
    }

    /**
     * Removes the player with the given id and notifies "removed" only if the map
     * actually held it. {@code shutdown(Player)} removes the player and then
     * {@code onDestroy()} calls {@code removePlayer} again, so the decision must
     * come from this {@code remove}.
     *
     * @return the removed player, or {@code null} if none was registered
     */
    Player removeAndNotify(Map<Integer, Player> players, int playerId) {
        Player removed = players.remove(playerId);
        if (removed != null) {
            post(removed, false);
        }
        return removed;
    }

    private void post(Player player, boolean added) {
        if (listeners.isEmpty()) {
            return;
        }
        // Only the notification is posted; the added/removed decision was already
        // made at the point of mutation, on whichever thread mutated the map.
        deliveryExecutor.execute(() -> {
            for (Listener listener : listeners) {
                // Skip listeners unregistered after this notification was queued or
                // earlier in this loop, so a removed listener never fires again.
                if (!listeners.contains(listener)) {
                    continue;
                }
                if (added) {
                    listener.onPlayerAdded(player);
                } else {
                    listener.onPlayerRemoved(player);
                }
            }
        });
    }
}
