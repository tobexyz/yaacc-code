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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import de.yaacc.share.ShareKeyTracker;

/**
 * Ties the lifetime of a share to the players that play it: a share is marked active while a
 * player plays it and released (its grace period starts) when the last player that used it ends.
 * <p>
 * The keys come from {@link ShareKeyTracker}, not from the item list at removal time: a player
 * clears its list in {@code onDestroy()}, which runs right after the service removed it and
 * before the "removed" notification reaches the main thread. The keys are therefore recorded
 * when the player is added, when its items are set ({@link #itemsSet}) and, synchronously,
 * at the moment of removal ({@link #onPlayerRemoving}), while the list is still intact.
 * <p>
 * Plain Java so the sequence can be tested on the JVM; the Android glue supplies how to read the
 * keys of a player and the registry calls.
 */
public final class ShareLifetimeListener implements PlayerListChangeNotifier.Listener {

    private final ShareKeyTracker<Player> tracker = new ShareKeyTracker<>();
    private final Function<Player, Set<String>> keysOf;
    private final Supplier<Collection<Player>> currentPlayers;
    private final Consumer<String> markActive;
    private final Consumer<String> release;

    /**
     * @param keysOf         the share keys in the items of a player right now (may throw)
     * @param currentPlayers the players that are active now
     * @param markActive     cancels the grace-period countdown of a share
     * @param release        starts the grace-period countdown of a share
     */
    public ShareLifetimeListener(Function<Player, Set<String>> keysOf,
                                 Supplier<Collection<Player>> currentPlayers,
                                 Consumer<String> markActive, Consumer<String> release) {
        this.keysOf = keysOf;
        this.currentPlayers = currentPlayers;
        this.markActive = markActive;
        this.release = release;
    }

    /**
     * The glue calls this after it set the items of a player (they may be set after the player
     * was added): remembers and activates the shares in them. Safe on any thread.
     */
    public void itemsSet(Player player) {
        Set<String> keys = safeKeysOf(player);
        tracker.refresh(player, keys);
        for (String key : keys) {
            markActive.accept(key);
        }
    }

    @Override
    public void onPlayerAdded(Player player) {
        itemsSet(player);
    }

    /** Synchronous, on the removing thread: the item list is still intact here. */
    @Override
    public void onPlayerRemoving(Player player) {
        tracker.refresh(player, safeKeysOf(player));
    }

    @Override
    public void onPlayerRemoved(Player player) {
        // what the player still shows now (if anything), and what the others play: a share is
        // released only when no other player uses it
        tracker.refresh(player, safeKeysOf(player));
        Collection<Player> others;
        try {
            others = currentPlayers.get();
        } catch (RuntimeException e) {
            others = Collections.emptyList();
        }
        for (Player other : others) {
            if (other != player) {
                tracker.refresh(other, safeKeysOf(other));
            }
        }
        for (String key : tracker.onPlayerRemoved(player)) {
            release.accept(key);
        }
    }

    private Set<String> safeKeysOf(Player player) {
        try {
            Set<String> keys = keysOf.apply(player);
            return keys == null ? Collections.emptySet() : new HashSet<>(keys);
        } catch (RuntimeException e) {
            // the list changed under us: what the tracker already knows still counts
            return Collections.emptySet();
        }
    }
}
