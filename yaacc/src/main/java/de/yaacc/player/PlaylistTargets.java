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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where "add to playlist" puts the items: the chosen receivers that already have a player get the items
 * added to it, the chosen receivers without one get a new player. Plain Java so the rule is testable.
 */
public final class PlaylistTargets {

    private final List<String> withPlayer;
    private final List<String> withoutPlayer;

    private PlaylistTargets(List<String> withPlayer, List<String> withoutPlayer) {
        this.withPlayer = withPlayer;
        this.withoutPlayer = withoutPlayer;
    }

    /**
     * @param chosenIds       the ids of the chosen receivers, in the order they should be served
     * @param playerDeviceIds the receiver ids of the players that are running now
     */
    public static PlaylistTargets split(Collection<String> chosenIds, Collection<String> playerDeviceIds) {
        Set<String> running = new HashSet<>(playerDeviceIds);
        List<String> with = new ArrayList<>();
        List<String> without = new ArrayList<>();
        for (String id : chosenIds) {
            (running.contains(id) ? with : without).add(id);
        }
        return new PlaylistTargets(with, without);
    }

    /** Chosen receivers that already have a player: their playlists get the items. */
    public List<String> withPlayer() {
        return withPlayer;
    }

    /** Chosen receivers without a player: each gets a new player with the items. */
    public List<String> withoutPlayer() {
        return withoutPlayer;
    }
}
