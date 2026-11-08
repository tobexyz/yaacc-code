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

import java.util.List;

/** Where "add to playlist" puts the items. Plain JVM. */
public class PlaylistTargetsTest {

    @Test
    public void receiversWithAPlayerGetTheItemsAddedAndOthersGetANewPlayer() {
        PlaylistTargets targets = PlaylistTargets.split(List.of("tv", "radio", "phone"), List.of("tv", "kitchen"));

        assertEquals(List.of("tv"), targets.withPlayer());
        assertEquals(List.of("radio", "phone"), targets.withoutPlayer());
    }

    @Test
    public void noPlayerAtAllMeansANewPlayerForEveryChosenReceiver() {
        PlaylistTargets targets = PlaylistTargets.split(List.of("tv", "radio"), List.of());

        assertEquals(List.of(), targets.withPlayer());
        assertEquals(List.of("tv", "radio"), targets.withoutPlayer());
    }

    @Test
    public void everyChosenReceiverWithAPlayerCreatesNoPlayer() {
        PlaylistTargets targets = PlaylistTargets.split(List.of("tv", "radio"), List.of("radio", "tv", "other"));

        assertEquals(List.of("tv", "radio"), targets.withPlayer());
        assertEquals(List.of(), targets.withoutPlayer());
    }

    @Test
    public void playersOfReceiversThatAreNotChosenAreIgnored() {
        PlaylistTargets targets = PlaylistTargets.split(List.of("tv"), List.of("kitchen"));

        assertEquals(List.of(), targets.withPlayer());
        assertEquals(List.of("tv"), targets.withoutPlayer());
    }

    @Test
    public void nothingChosenMeansNothingToDo() {
        PlaylistTargets targets = PlaylistTargets.split(List.of(), List.of("tv"));

        assertEquals(List.of(), targets.withPlayer());
        assertEquals(List.of(), targets.withoutPlayer());
    }
}
