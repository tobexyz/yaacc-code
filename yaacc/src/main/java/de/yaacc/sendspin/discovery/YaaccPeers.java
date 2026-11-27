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
package de.yaacc.sendspin.discovery;

import de.yaacc.sendspin.hub.Endpoint;

import java.util.List;

/**
 * Joins the two announcements of one YAACC phone (issue #325, K14): its player ({@code _sendspin._tcp}) and its server
 * ({@code _sendspin-server._tcp}) carry the same {@code yaacc} id, so that another phone shows one card for it. Only the id joins
 * them: an address is no proof (a Music Assistant server and a Kodi player can share one). Pure.
 */
public final class YaaccPeers {

    private YaaccPeers() {
    }

    /** @return the endpoint of the server that belongs to the same phone as this player, or null */
    public static Endpoint serverOf(SendspinDirectory.Entry player, List<SendspinDirectory.Entry> servers) {
        if (player == null || player.yaacc() == null) {
            return null;
        }
        for (SendspinDirectory.Entry server : servers) {
            if (player.yaacc().equals(server.yaacc())) {
                return server.endpoint();
            }
        }
        return null;
    }

    /** @return true if a player of the same phone is listed: this server is shown inside that player's card, not as a card of its own */
    public static boolean isMergedServer(SendspinDirectory.Entry server, List<SendspinDirectory.Entry> players) {
        if (server == null || server.yaacc() == null) {
            return false;
        }
        for (SendspinDirectory.Entry player : players) {
            if (server.yaacc().equals(player.yaacc())) {
                return true;
            }
        }
        return false;
    }
}
