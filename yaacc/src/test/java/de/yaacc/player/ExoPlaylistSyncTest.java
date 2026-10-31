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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Added items reach the playing ExoPlayer only when its list is in step with the player's list. */
public class ExoPlaylistSyncTest {

    @Test
    public void aListInStepIsExtended() {
        assertTrue(ExoPlaylistSync.canAppend(3, 3));
    }

    @Test
    public void aSecondAdditionFollowsTheFirstInTheSameOrder() {
        // the first addition made 3 -> 5 on the main thread; the second was added to a list of 5
        assertTrue(ExoPlaylistSync.canAppend(5, 5));
    }

    @Test
    public void anEmptyOrOtherSizedPlaylistIsLeftToTheNextStart() {
        assertFalse(ExoPlaylistSync.canAppend(0, 0));
        assertFalse(ExoPlaylistSync.canAppend(2, 3));
        assertFalse(ExoPlaylistSync.canAppend(4, 3));
    }
}
