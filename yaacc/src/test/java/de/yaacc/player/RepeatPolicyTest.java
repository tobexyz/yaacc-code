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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

/** The setting "repeat playlist" decides whether a song or a TTS sentence is repeated. */
public class RepeatPolicyTest {

    @Test
    public void withTheSettingOffThePlaylistIsPlayedOnce() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatPolicy.repeatMode(false));
        assertTrue(RepeatPolicy.stopsAtEnd(false));
    }

    @Test
    public void withTheSettingOnAllItemsRepeat() {
        assertEquals(Player.REPEAT_MODE_ALL, RepeatPolicy.repeatMode(true));
        assertFalse(RepeatPolicy.stopsAtEnd(true));
    }
}
