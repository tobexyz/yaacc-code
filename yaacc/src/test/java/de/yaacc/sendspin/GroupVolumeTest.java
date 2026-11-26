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
package de.yaacc.sendspin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The group volume of the controller role ({@code roles/controller/v1.md}, "Setting group volume"). */
public class GroupVolumeTest {

    @Test
    public void everyPlayerMovesByTheSameDeltaWhenNobodyIsClamped() {
        assertArrayEquals(new int[]{40, 60}, GroupVolume.targets(new int[]{30, 50}, 50)); // average 40 to 50: +10 each
        assertArrayEquals(new int[]{20, 40}, GroupVolume.targets(new int[]{30, 50}, 30)); // -10 each
    }

    @Test
    public void whatAClampedPlayerCouldNotTakeGoesToTheOthers() {
        // average 70, requested 100: +30 each; the first is clamped at 100 (20 lost) and the second takes them
        assertArrayEquals(new int[]{100, 100}, GroupVolume.targets(new int[]{90, 50}, 100));
        // downwards: average 30, requested 0
        assertArrayEquals(new int[]{0, 0}, GroupVolume.targets(new int[]{10, 50}, 0));
    }

    @Test
    public void theRelativeLevelsAreKeptWhileThereIsRoom() {
        int[] result = GroupVolume.targets(new int[]{20, 40, 60}, 60); // average 40 to 60: +20 each
        assertArrayEquals(new int[]{40, 60, 80}, result);
    }

    @Test
    public void theFinalAverageMatchesTheRequestAsFarAsTheBoundariesAllow() {
        int[] result = GroupVolume.targets(new int[]{100, 20, 20}, 90); // average 46.67 to 90: asks 43 each; the first is clamped
        int sum = 0;
        for (int v : result) {
            assertEquals(true, v >= 0 && v <= 100);
            sum += v;
        }
        assertEquals(90, Math.round(sum / (float) result.length));
        assertEquals(100, result[0]);
    }

    @Test
    public void everyoneAtTheBoundaryStaysThereAndAnEmptyGroupIsEmpty() {
        assertArrayEquals(new int[]{100, 100}, GroupVolume.targets(new int[]{100, 100}, 100));
        assertArrayEquals(new int[]{}, GroupVolume.targets(new int[]{}, 50));
        assertArrayEquals(new int[]{50}, GroupVolume.targets(new int[]{80}, 50));
    }

    @Test
    public void aRequestOutsideTheRangeIsClamped() {
        assertArrayEquals(new int[]{100, 100}, GroupVolume.targets(new int[]{10, 20}, 500));
        assertArrayEquals(new int[]{0, 0}, GroupVolume.targets(new int[]{10, 20}, -5));
    }
}
