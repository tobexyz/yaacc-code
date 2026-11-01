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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

import de.yaacc.player.PlayMode.RepeatMode;

/** The repeat mode of a player decides whether a song or a TTS sentence is repeated, and for ExoPlayer which constant. */
public class RepeatPolicyTest {

    @Test
    public void withRepeatOffThePlaylistIsPlayedOnce() {
        PlayMode mode = PlayMode.of(RepeatMode.OFF, false);

        assertEquals(Player.REPEAT_MODE_OFF, RepeatPolicy.repeatMode(mode));
        assertTrue(RepeatPolicy.stopsAtEnd(mode));
    }

    @Test
    public void withRepeatAllAllItemsRepeat() {
        PlayMode mode = PlayMode.of(RepeatMode.ALL, false);

        assertEquals(Player.REPEAT_MODE_ALL, RepeatPolicy.repeatMode(mode));
        assertFalse(RepeatPolicy.stopsAtEnd(mode));
    }

    @Test
    public void withRepeatOneTheItemRepeatsAndNeverEnds() {
        PlayMode mode = PlayMode.of(RepeatMode.ONE, true);

        assertEquals(Player.REPEAT_MODE_ONE, RepeatPolicy.repeatMode(mode));
        assertFalse(RepeatPolicy.stopsAtEnd(mode));
    }

    @Test
    public void shuffleDoesNotChangeTheRepeatMode() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatPolicy.repeatMode(PlayMode.of(RepeatMode.OFF, true)));
        assertTrue(RepeatPolicy.stopsAtEnd(PlayMode.of(RepeatMode.OFF, true)));
    }

    @Test
    public void exoRepeatModesMapBack() {
        assertEquals(RepeatMode.OFF, RepeatPolicy.fromExoRepeatMode(Player.REPEAT_MODE_OFF));
        assertEquals(RepeatMode.ALL, RepeatPolicy.fromExoRepeatMode(Player.REPEAT_MODE_ALL));
        assertEquals(RepeatMode.ONE, RepeatPolicy.fromExoRepeatMode(Player.REPEAT_MODE_ONE));
        assertEquals(RepeatMode.OFF, RepeatPolicy.fromExoRepeatMode(99));
    }

    // --- a media controller changes the mode of ExoPlayer directly (system controls, Bluetooth, Wear)

    @Test
    public void anExternalRepeatChangeIsTakenOverAndKeepsShuffle() {
        PlayMode current = PlayMode.of(RepeatMode.OFF, true);

        assertEquals(PlayMode.of(RepeatMode.ALL, true),
                RepeatPolicy.adoptExoRepeatMode(current, Player.REPEAT_MODE_ALL, false));
        assertEquals(PlayMode.of(RepeatMode.ONE, true),
                RepeatPolicy.adoptExoRepeatMode(current, Player.REPEAT_MODE_ONE, false));
        assertEquals(PlayMode.of(RepeatMode.OFF, true),
                RepeatPolicy.adoptExoRepeatMode(PlayMode.of(RepeatMode.ONE, true), Player.REPEAT_MODE_OFF, false));
    }

    @Test
    public void anExternalShuffleChangeIsTakenOverAndKeepsRepeat() {
        assertEquals(PlayMode.of(RepeatMode.ONE, true),
                RepeatPolicy.adoptExoShuffleMode(PlayMode.of(RepeatMode.ONE, false), true, false));
        assertEquals(PlayMode.of(RepeatMode.ALL, false),
                RepeatPolicy.adoptExoShuffleMode(PlayMode.of(RepeatMode.ALL, true), false, false));
    }

    @Test
    public void valuesEqualToThePlayModeAreNoChange() {
        PlayMode current = PlayMode.of(RepeatMode.ALL, true);

        assertNull(RepeatPolicy.adoptExoRepeatMode(current, Player.REPEAT_MODE_ALL, false));
        assertNull(RepeatPolicy.adoptExoShuffleMode(current, true, false));
    }

    @Test
    public void theEchoOfOurOwnChangeIsIgnored() {
        PlayMode current = PlayMode.of(RepeatMode.ALL, true);

        // applyPlayMode sets the modes of ExoPlayer one after the other: the first callback can still see
        // a value that differs from the play mode
        assertNull(RepeatPolicy.adoptExoRepeatMode(current, Player.REPEAT_MODE_OFF, true));
        assertNull(RepeatPolicy.adoptExoShuffleMode(current, false, true));
    }

    @Test
    public void everyExoRepeatConstantMapsToItsRepeatModeAndBack() {
        for (RepeatMode repeat : RepeatMode.values()) {
            assertEquals(repeat, RepeatPolicy.fromExoRepeatMode(RepeatPolicy.repeatMode(repeat)));
        }
    }
}
