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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The group volume of a server that has none: average, relative change, clamping with redistribution. */
public class GroupVolumeTest {

    private static Map<String, Integer> levels(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return result;
    }

    private static int sum(Map<String, Integer> levels) {
        int sum = 0;
        for (int level : levels.values()) {
            sum += level;
        }
        return sum;
    }

    // ---- average

    @Test
    public void theGroupVolumeIsTheAverageOfTheMembers() {
        assertEquals(50, GroupVolume.average(levels("a", 40, "b", 60)));
        assertEquals(34, GroupVolume.average(levels("a", 33, "b", 34, "c", 36)));
        assertEquals(100, GroupVolume.average(levels("a", 100)));
        assertEquals(0, GroupVolume.average(levels("a", 0, "b", 0)));
        assertEquals("rounded half up", 63, GroupVolume.average(levels("a", 70, "b", 55)));
    }

    @Test
    public void noMembersGiveMinusOne() {
        assertEquals(-1, GroupVolume.average(new LinkedHashMap<String, Integer>()));
    }

    @Test
    public void levelsListConnectedMembersInOrder() {
        SnapcastVolume v70 = new SnapcastVolume(70, false);
        List<SnapcastClient> clients = new ArrayList<>();
        clients.add(new SnapcastClient("a", "A", "", true, v70, 0));
        clients.add(new SnapcastClient("b", "B", "", false, new SnapcastVolume(90, false), 0));
        clients.add(new SnapcastClient("c", "C", "", true, new SnapcastVolume(30, true), 0));
        SnapcastGroup group = new SnapcastGroup("g", "", false, "s", clients);

        assertEquals("a disconnected member does not count, a muted one does",
                levels("a", 70, "c", 30), GroupVolume.levels(group));
    }

    // ---- relative change

    @Test
    public void everyMemberMovesByTheSameNumberOfPointsAndKeepsItsRelativeLevel() {
        Map<String, Integer> result = GroupVolume.apply(levels("a", 40, "b", 60), 10);

        assertEquals(levels("a", 50, "b", 70), result);
        assertEquals(20, result.get("b") - result.get("a"));
    }

    @Test
    public void noMovementChangesNothing() {
        Map<String, Integer> start = levels("a", 12, "b", 77);

        assertEquals(start, GroupVolume.apply(start, 0));
        assertTrue(GroupVolume.apply(new LinkedHashMap<String, Integer>(), 20).isEmpty());
    }

    @Test
    public void aClampedMembersRemainderGoesToTheOthers() {
        // a would be 110: it stops at 100 and the 10 points that do not fit are shared by b and c
        assertEquals(levels("a", 100, "b", 75, "c", 35), GroupVolume.apply(levels("a", 90, "b", 50, "c", 10), 20));
    }

    @Test
    public void theRemainderIsAlsoRedistributedDownwards() {
        // a would be -5: it stops at 0 and b takes the 5 points
        assertEquals(levels("a", 0, "b", 35), GroupVolume.apply(levels("a", 5, "b", 50), -10));
    }

    @Test
    public void anUnevenRemainderGivesTheFirstMembersOnePointMore() {
        // a stops at 100 with 9 points left; b and c get 4 each and the odd point goes to b
        Map<String, Integer> result = GroupVolume.apply(levels("a", 99, "b", 10, "c", 10), 10);

        assertEquals(levels("a", 100, "b", 25, "c", 24), result);
        assertEquals(sum(levels("a", 99, "b", 10, "c", 10)) + 30, sum(result));
    }

    @Test
    public void redistributionRepeatsWhenTheSharePushesAnotherMemberOverTheEdge() {
        // a and b are clamped (20 points left); x has room for 3 only, y takes the rest in a second round
        Map<String, Integer> result = GroupVolume.apply(levels("a", 100, "b", 100, "x", 87, "y", 30), 10);

        assertEquals(levels("a", 100, "b", 100, "x", 100, "y", 57), result);
        assertEquals(357, sum(result));
    }

    @Test
    public void everythingAtTheEdgeStaysThere() {
        assertEquals(levels("a", 100, "b", 100), GroupVolume.apply(levels("a", 100, "b", 100), 10));
        assertEquals(levels("a", 0, "b", 0), GroupVolume.apply(levels("a", 0, "b", 0), -10));
        assertEquals(levels("a", 100, "b", 100, "c", 100), GroupVolume.apply(levels("a", 100, "b", 60, "c", 90), 20));
    }

    @Test
    public void hugeDeltasAreClamped() {
        assertEquals(levels("a", 100, "b", 100), GroupVolume.apply(levels("a", 10, "b", 20), Integer.MAX_VALUE));
        assertEquals(levels("a", 0, "b", 0), GroupVolume.apply(levels("a", 10, "b", 20), Integer.MIN_VALUE));
        assertEquals(levels("a", 100, "b", 100), GroupVolume.apply(levels("a", 10, "b", 20), 1_000_000));
    }

    @Test
    public void movingFromTheStartValuesRestoresEveryMembersOwnLevel() {
        Map<String, Integer> start = levels("a", 40, "b", 95);

        GroupVolume.apply(start, 30); // b is clamped

        assertEquals(start, GroupVolume.apply(start, 0));
    }

    @Test
    public void settingANewAverageAppliesTheDifference() {
        // average 50 -> 70: both move by 20
        assertEquals(levels("a", 60, "b", 80), GroupVolume.target(levels("a", 40, "b", 60), 70));
        // average 50 -> 20
        assertEquals(levels("a", 10, "b", 30), GroupVolume.target(levels("a", 40, "b", 60), 20));
        assertEquals(levels("a", 40, "b", 60), GroupVolume.target(levels("a", 40, "b", 60), 50));
    }

    @Test
    public void theNewAverageIsClampedToTheScale() {
        assertEquals(levels("a", 100, "b", 100), GroupVolume.target(levels("a", 40, "b", 60), 500));
        assertEquals(levels("a", 0, "b", 0), GroupVolume.target(levels("a", 40, "b", 60), -3));
    }

    @Test
    public void settingAnAverageOnNoMembersIsEmpty() {
        assertTrue(GroupVolume.target(new LinkedHashMap<String, Integer>(), 40).isEmpty());
    }

    @Test
    public void randomisedInvariants() {
        Random random = new Random(319);
        for (int round = 0; round < 2000; round++) {
            int members = 1 + random.nextInt(8);
            Map<String, Integer> start = new LinkedHashMap<>();
            for (int i = 0; i < members; i++) {
                start.put("m" + i, random.nextInt(101));
            }
            int delta = random.nextInt(241) - 120;

            Map<String, Integer> result = GroupVolume.apply(start, delta);

            assertEquals(start.keySet(), result.keySet());
            int expectedSum = Math.max(0, Math.min(100 * members, sum(start) + delta * members));
            assertEquals("start=" + start + " delta=" + delta, expectedSum, sum(result));
            for (int level : result.values()) {
                assertTrue(level >= 0 && level <= 100);
            }
            // a member never moves against the direction of the change
            for (Map.Entry<String, Integer> entry : result.entrySet()) {
                int before = start.get(entry.getKey());
                if (delta >= 0) {
                    assertTrue(entry.getValue() >= before);
                } else {
                    assertTrue(entry.getValue() <= before);
                }
            }
        }
    }
}
