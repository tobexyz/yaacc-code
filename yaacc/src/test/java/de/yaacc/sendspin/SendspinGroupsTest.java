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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SendspinGroupsTest {

    private final SendspinGroups groups = new SendspinGroups();

    @Test
    public void aLinkedPlayerHasItsLeaderAndTheLeaderItsMembersInTheOrderTheyWereLinked() {
        assertEquals(SendspinGroups.Result.LINKED, groups.link("L", "a"));
        assertEquals(SendspinGroups.Result.LINKED, groups.link("L", "b"));
        assertEquals("L", groups.leaderOf("a"));
        assertEquals(Arrays.asList("a", "b"), groups.membersOf("L"));
        assertNull(groups.leaderOf("L"));
        assertTrue(groups.isLeader("L"));
        assertFalse(groups.isLeader("a"));
        assertTrue(groups.isInGroup("a"));
        assertTrue(groups.isInGroup("L"));
        assertFalse(groups.isInGroup("x"));
    }

    @Test
    public void linkingAgainChangesNothing() {
        groups.link("L", "a");
        assertEquals(SendspinGroups.Result.ALREADY, groups.link("L", "a"));
        assertEquals(Collections.singletonList("a"), groups.membersOf("L"));
    }

    @Test
    public void aPlayerCanNotBeItsOwnLeader() {
        assertEquals(SendspinGroups.Result.SAME, groups.link("a", "a"));
    }

    @Test
    public void aMemberCanNotLeadAndALeaderCanNotBeAMember() {
        groups.link("L", "a");
        assertEquals(SendspinGroups.Result.LEADER_IS_A_MEMBER, groups.link("a", "b"));
        assertEquals(SendspinGroups.Result.MEMBER_IS_A_LEADER, groups.link("M", "L"));
    }

    @Test
    public void aMemberOfAnotherGroupIsMoved() {
        groups.link("L", "a");
        assertEquals(SendspinGroups.Result.LINKED, groups.link("M", "a"));
        assertEquals("M", groups.leaderOf("a"));
        assertTrue(groups.membersOf("L").isEmpty());
        assertFalse("a group without members is no group", groups.isLeader("L"));
    }

    @Test
    public void aGroupHasAtMostEightPlayersTheLeaderIncluded() {
        for (int i = 1; i <= SendspinGroups.MAX_MEMBERS; i++) {
            assertEquals(SendspinGroups.Result.LINKED, groups.link("L", "m" + i));
        }
        assertEquals(SendspinGroups.MAX_MEMBERS + 1 - 1, groups.membersOf("L").size());
        assertEquals(SendspinGroups.Result.FULL, groups.link("L", "one-too-many"));
        assertEquals(7, SendspinGroups.MAX_MEMBERS);
    }

    @Test
    public void unlinkingAMemberAndDissolvingAGroup() {
        groups.link("L", "a");
        groups.link("L", "b");
        groups.unlink("a");
        assertNull(groups.leaderOf("a"));
        assertEquals(Collections.singletonList("b"), groups.membersOf("L"));
        groups.dissolve("L");
        assertNull(groups.leaderOf("b"));
        assertFalse(groups.isLeader("L"));
        groups.unlink("nobody"); // harmless
        groups.dissolve("nobody");
    }

    @Test
    public void aPlayerThatIsRemovedLeavesItsGroupAndTheGroupOfALeaderThatIsRemovedEnds() {
        groups.link("L", "a");
        groups.link("L", "b");
        groups.remove("a");
        assertEquals(Collections.singletonList("b"), groups.membersOf("L"));
        groups.remove("L");
        assertNull(groups.leaderOf("b"));
    }

    @Test
    public void emptyOrNullIdsAreRefused() {
        assertEquals(SendspinGroups.Result.INVALID, groups.link(null, "a"));
        assertEquals(SendspinGroups.Result.INVALID, groups.link("L", ""));
        assertTrue(groups.membersOf(null).isEmpty());
        assertNull(groups.leaderOf(null));
    }

    @Test
    public void membersAreListedDirectlyUnderTheirLeaderAndTheRestKeepsItsOrder() {
        java.util.Map<String, String> leaderOf = new java.util.HashMap<>();
        leaderOf.put("c", "a");
        leaderOf.put("e", "a");
        leaderOf.put("d", "zz"); // its leader is not in the list: it stays
        java.util.List<String> sorted = java.util.Arrays.asList("a", "b", "c", "d", "e", "(not a player)");
        java.util.List<String> grouped = SendspinGroups.groupedOrder(sorted, s -> s.startsWith("(") ? null : s, leaderOf::get);
        assertEquals(java.util.Arrays.asList("a", "c", "e", "b", "d", "(not a player)"), grouped);
        assertEquals("no groups, no change", sorted, SendspinGroups.groupedOrder(sorted, s -> s.startsWith("(") ? null : s, id -> null));
    }

    @Test
    public void aMemberThatLeavesIsAloneAgainAndTheGroupGoesOn() {
        groups.link("L", "a");
        groups.link("L", "b");
        assertEquals("the group it left", "L", groups.leave("a"));
        assertNull(groups.leaderOf("a"));
        assertEquals(Arrays.asList("b"), groups.membersOf("L"));
    }

    @Test
    public void theLastMemberThatLeavesEndsTheGroup() {
        groups.link("L", "a");
        assertEquals("L", groups.leave("a"));
        assertFalse(groups.isInGroup("L"));
    }

    @Test
    public void aLeaderThatLeavesEndsItsGroupBecauseTheGroupIsPlayedToIt() {
        groups.link("L", "a");
        groups.link("L", "b");
        assertEquals("L", groups.leave("L"));
        assertFalse(groups.isInGroup("a"));
        assertFalse(groups.isInGroup("b"));
        assertFalse(groups.isInGroup("L"));
    }

    @Test
    public void aPlayerInNoGroupHasNothingToLeave() {
        assertNull(groups.leave("x"));
        assertNull(groups.leave(null));
    }
}
