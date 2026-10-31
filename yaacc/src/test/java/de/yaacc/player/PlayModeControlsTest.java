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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import de.yaacc.player.PlayMode.RepeatMode;

/** What the repeat and shuffle controls show for a play mode: every state is distinguishable without color. */
public class PlayModeControlsTest {

    @Test
    public void everyRepeatStateHasItsOwnText() {
        Set<Integer> labels = new HashSet<>();
        for (RepeatMode repeat : RepeatMode.values()) {
            assertTrue(PlayModeControls.repeatLabel(repeat) != 0);
            labels.add(PlayModeControls.repeatLabel(repeat));
        }
        assertEquals(3, labels.size());
    }

    @Test
    public void everyShuffleStateHasItsOwnText() {
        assertNotEquals(PlayModeControls.shuffleLabel(true), PlayModeControls.shuffleLabel(false));
        assertTrue(PlayModeControls.shuffleLabel(false) != 0);
    }

    @Test
    public void repeatOneHasItsOwnIconAndOffAndAllShareTheRepeatIcon() {
        assertNotEquals(PlayModeControls.repeatIcon(RepeatMode.ONE), PlayModeControls.repeatIcon(RepeatMode.ALL));
        assertEquals(PlayModeControls.repeatIcon(RepeatMode.OFF), PlayModeControls.repeatIcon(RepeatMode.ALL));
    }

    @Test
    public void inactiveControlsAreHalfTransparent() {
        assertEquals(1f, PlayModeControls.alpha(true), 0f);
        assertEquals(0.5f, PlayModeControls.alpha(false), 0f);
    }
}
