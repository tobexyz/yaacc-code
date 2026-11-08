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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The notification of the service says how many players there are and what they do. */
public class PlayerServiceStatusTextTest {

    private static String text(int total, int playing, int paused) {
        return PlayerService.statusText(total, playing, paused, "playing", "paused", "stopped");
    }

    @Test
    public void onePlayerInEachState() {
        assertEquals("1 player (1 playing)", text(1, 1, 0));
        assertEquals("1 player (1 paused)", text(1, 0, 1));
        assertEquals("1 player (1 stopped)", text(1, 0, 0));
    }

    @Test
    public void severalPlayersListEveryStateThatOccurs() {
        assertEquals("3 players (1 playing, 1 paused, 1 stopped)", text(3, 1, 1));
        assertEquals("2 players (2 stopped)", text(2, 0, 0));
        assertEquals("2 players (1 playing, 1 stopped)", text(2, 1, 0));
    }

    @Test
    public void noPlayersIsJustRunning() {
        assertEquals("running", text(0, 0, 0));
    }
}
