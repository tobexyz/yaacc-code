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
package de.yaacc.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The players that the YAACC renderer made (task 9.1), each with the output it was made for ({@code
 * SnapcastRendererOutput#outputKey}). When the user switches the output, exactly these players of another output are stopped:
 * a player that the user started in the app (also a Snapcast player for the same group) was never tagged and is left alone.
 * Thread safe; no player is held after it was {@link #forget forgotten} (the service forgets at every shutdown).
 */
final class RendererPlayers {

    private final Map<Player, String> outputs = Collections.synchronizedMap(new IdentityHashMap<Player, String>());

    /** The renderer made this player for this output (again: the new output replaces the old). */
    void tag(Player player, String outputKey) {
        if (player != null && outputKey != null) {
            outputs.put(player, outputKey);
        }
    }

    void forget(Player player) {
        if (player != null) {
            outputs.remove(player);
        }
    }

    boolean isTagged(Player player) {
        return player != null && outputs.containsKey(player);
    }

    /** A copy of the tagged players whose output is not {@code currentKey}. */
    List<Player> toStopFor(String currentKey) {
        List<Player> result = new ArrayList<>();
        synchronized (outputs) {
            for (Map.Entry<Player, String> entry : outputs.entrySet()) {
                if (!entry.getValue().equals(currentKey)) {
                    result.add(entry.getKey());
                }
            }
        }
        return result;
    }
}
