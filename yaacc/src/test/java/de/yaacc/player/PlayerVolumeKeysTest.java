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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Decision D32: which player takes the hardware volume keys on the receivers page. */
public class PlayerVolumeKeysTest {

    private static <T extends Player> T player(Class<T> type, boolean playing, boolean paused, boolean hasVolume) {
        T player = mock(type);
        when(player.isPlaying()).thenReturn(playing);
        when(player.isPaused()).thenReturn(paused);
        when(player.hasActionGetVolume()).thenReturn(hasVolume);
        return player;
    }

    @Test
    public void theOneActiveRemotePlayerTakesTheKeys() {
        FCastPlayer fcast = player(FCastPlayer.class, true, false, true);
        AVTransportPlayer idle = player(AVTransportPlayer.class, false, false, true);
        assertSame(fcast, PlayerVolumeKeys.target(Arrays.<Player>asList(idle, fcast)));
    }

    @Test
    public void aPausedPlayerCountsAsActive() {
        OpenHomePlayer paused = player(OpenHomePlayer.class, false, true, true);
        assertSame(paused, PlayerVolumeKeys.target(Collections.<Player>singletonList(paused)));
    }

    @Test
    public void everyKindOfRemotePlayerIsRemote() {
        for (Class<? extends Player> type : Arrays.<Class<? extends Player>>asList(AVTransportPlayer.class, OpenHomePlayer.class, FCastPlayer.class,
                SnapcastPlayer.class)) {
            Player player = player(type, true, false, true);
            assertSame(type.getSimpleName(), player, PlayerVolumeKeys.target(Collections.singletonList(player)));
        }
    }

    @Test
    public void noActivePlayerLeavesTheKeysToTheSystem() {
        assertNull(PlayerVolumeKeys.target(Collections.<Player>emptyList()));
        assertNull(PlayerVolumeKeys.target(null));
        assertNull(PlayerVolumeKeys.target(Collections.<Player>singletonList(player(FCastPlayer.class, false, false, true))));
    }

    @Test
    public void moreThanOneActivePlayerLeavesTheKeysToTheSystem() {
        List<Player> players = new ArrayList<>();
        players.add(player(FCastPlayer.class, true, false, true));
        players.add(player(AVTransportPlayer.class, false, true, true));
        assertNull(PlayerVolumeKeys.target(players));
    }

    @Test
    public void aLocalPlayerThatIsActiveMeansTheSystemEvenIfARemoteOneIsActiveToo() {
        List<Player> players = new ArrayList<>();
        players.add(player(FCastPlayer.class, true, false, true));
        players.add(player(LocalMediaSessionPlayer.class, true, false, true));
        assertNull(PlayerVolumeKeys.target(players));
        assertNull("alone, a local player is the system's", PlayerVolumeKeys.target(Collections.<Player>singletonList(
                player(LocalMediaSessionPlayer.class, true, false, true))));
    }

    @Test
    public void aRemotePlayerWithoutAVolumeIsLeftAlone() {
        assertNull(PlayerVolumeKeys.target(Collections.<Player>singletonList(player(AVTransportPlayer.class, true, false, false))));
    }

    @Test
    public void aStepIsFivePercentAndStaysBetween0And100() {
        assertEquals(55, PlayerVolumeKeys.step(50, true));
        assertEquals(45, PlayerVolumeKeys.step(50, false));
        assertEquals(100, PlayerVolumeKeys.step(98, true));
        assertEquals(0, PlayerVolumeKeys.step(2, false));
    }
}
