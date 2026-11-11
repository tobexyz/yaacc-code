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
package de.yaacc.multiroom.ui;

import static de.yaacc.multiroom.ui.UiFixtures.client;
import static de.yaacc.multiroom.ui.UiFixtures.group;
import static de.yaacc.multiroom.ui.UiFixtures.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * "Add client": the clients of the same server that are not in this group, and the full wanted member list that
 * {@code Group.SetClients} needs (the server removes every client that is not in the list, and each removed client gets
 * a group of its own). Plain JVM.
 */
public class SnapcastClientCandidatesTest {

    private static final SnapcastClientCandidates.Words WORDS = new SnapcastClientCandidates.Words() {
        @Override
        public String unnamedClient() {
            return "Unnamed client";
        }

        @Override
        public String unnamedGroup() {
            return "Unnamed group";
        }
    };

    private static final SnapcastGroup LIVING = group("g1", "Living", "Default",
            client("a", "Kitchen", true, 50, false), client("b", "Hall", true, 50, false));
    private static final SnapcastGroup BEDROOM = group("g2", "Bedroom", "Default",
            client("c", "Bed left", true, 50, false), client("d", "Bed right", false, 50, false));
    private static final SnapcastGroup OFFICE = group("g3", "", "Default", client("e", "Desk", true, 50, false));

    private static SnapcastServerStatus server() {
        return status(Arrays.asList(LIVING, BEDROOM, OFFICE));
    }

    @Test
    public void onlyClientsOfOtherGroupsOfTheSameServerAreOffered() {
        List<SnapcastClientCandidates.Candidate> candidates = SnapcastClientCandidates.addable(server(), "g1", WORDS);

        assertEquals("[Bed left, Bed right, Desk]", names(candidates).toString());
        for (SnapcastClientCandidates.Candidate candidate : candidates) {
            assertFalse(LIVING.findClient(candidate.getClientId()) != null);
        }
    }

    @Test
    public void aCandidateSaysWhichGroupItLeavesAndWhetherThatGroupIsLeftEmpty() {
        List<SnapcastClientCandidates.Candidate> candidates = SnapcastClientCandidates.addable(server(), "g1", WORDS);

        SnapcastClientCandidates.Candidate bedLeft = candidates.get(0);
        assertEquals("Bedroom", bedLeft.getFromGroupName());
        assertFalse(bedLeft.leavesGroupEmpty());
        SnapcastClientCandidates.Candidate desk = candidates.get(2);
        assertEquals("the first member names a group without a name", "Desk", desk.getFromGroupName());
        assertTrue("the only member of its group: the group disappears", desk.leavesGroupEmpty());
    }

    @Test
    public void aGroupWithoutNameAndWithoutNamedMembersUsesTheLabel() {
        SnapcastGroup anonymous = group("g4", "", "Default", client("f", "", "", true, 10, 0));
        SnapcastServerStatus status = status(Arrays.asList(LIVING, anonymous));

        assertEquals("Unnamed group", SnapcastClientCandidates.addable(status, "g1", WORDS).get(0).getFromGroupName());
    }

    @Test
    public void connectionStateIsShown() {
        List<SnapcastClientCandidates.Candidate> candidates = SnapcastClientCandidates.addable(server(), "g1", WORDS);

        assertTrue(candidates.get(0).isConnected());
        assertFalse(candidates.get(1).isConnected());
    }

    @Test
    public void theOrderIsByNameAndStable() {
        List<SnapcastClientCandidates.Candidate> first = SnapcastClientCandidates.addable(server(), "g1", WORDS);
        List<SnapcastClientCandidates.Candidate> second = SnapcastClientCandidates.addable(
                status(Arrays.asList(OFFICE, BEDROOM, LIVING)), "g1", WORDS);

        assertEquals(ids(first), ids(second));
    }

    @Test
    public void aGroupThatIsNotThereOffersNothing() {
        assertTrue(SnapcastClientCandidates.addable(server(), "nope", WORDS).isEmpty());
        assertTrue(SnapcastClientCandidates.addable(null, "g1", WORDS).isEmpty());
    }

    @Test
    public void namesAreCleaned() {
        SnapcastGroup odd = group("g5", "Odd\u202E", "Default", client("x", "Pi\u200B", true, 1, false),
                client("y", "Pi\u200B", true, 1, false));
        List<SnapcastClientCandidates.Candidate> candidates = SnapcastClientCandidates.addable(
                status(Arrays.asList(LIVING, odd)), "g1", WORDS);

        assertEquals("Pi?", candidates.get(0).getName());
        assertEquals("Pi? (2)", candidates.get(1).getName());
        assertEquals("Odd?", candidates.get(0).getFromGroupName());
    }

    @Test
    public void addingKeepsTheCurrentMembersAndAppendsTheNewOnes() {
        List<String> wanted = SnapcastClientCandidates.wantedWith(LIVING, Arrays.asList("c", "e"));

        assertEquals(Arrays.asList("a", "b", "c", "e"), wanted);
    }

    @Test
    public void addingNothingOrADuplicateChangesNothing() {
        assertEquals(Arrays.asList("a", "b"), SnapcastClientCandidates.wantedWith(LIVING, Collections.<String>emptyList()));
        assertEquals(Arrays.asList("a", "b", "c"), SnapcastClientCandidates.wantedWith(LIVING, Arrays.asList("a", "c", "c")));
    }

    @Test
    public void emptyIdsAreIgnoredWhenAdding() {
        assertEquals(Arrays.asList("a", "b"), SnapcastClientCandidates.wantedWith(LIVING, Arrays.asList("", null)));
    }

    @Test
    public void removingLeavesTheOthersInTheWantedList() {
        assertEquals(Collections.singletonList("b"), SnapcastClientCandidates.wantedWithout(LIVING, "a"));
        assertEquals(Collections.singletonList("a"), SnapcastClientCandidates.wantedWithout(LIVING, "b"));
    }

    @Test
    public void theLastMemberCannotBeRemovedBecauseAnEmptyListWouldDissolveTheGroup() {
        assertNull(SnapcastClientCandidates.wantedWithout(OFFICE, "e"));
    }

    @Test
    public void anIdThatIsNoMemberCannotBeRemoved() {
        assertNull(SnapcastClientCandidates.wantedWithout(LIVING, "zzz"));
        assertNull(SnapcastClientCandidates.wantedWithout(LIVING, null));
        assertNull(SnapcastClientCandidates.wantedWithout(LIVING, ""));
    }

    @Test
    public void aGroupAtTheClientLimitIsNotGivenMoreThanTheLimit() {
        List<SnapcastClient> members = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            members.add(client("m" + i, "M" + i, true, 1, false));
        }
        SnapcastGroup full = new SnapcastGroup("big", "Big", false, "Default", members);

        assertNull("the server library would refuse a longer list", SnapcastClientCandidates.wantedWith(full, Arrays.asList("c")));
        assertEquals(256, SnapcastClientCandidates.wantedWith(full, Collections.<String>emptyList()).size());
    }

    private static List<String> names(List<SnapcastClientCandidates.Candidate> candidates) {
        List<String> names = new ArrayList<>();
        for (SnapcastClientCandidates.Candidate candidate : candidates) {
            names.add(candidate.getName());
        }
        return names;
    }

    private static List<String> ids(List<SnapcastClientCandidates.Candidate> candidates) {
        List<String> ids = new ArrayList<>();
        for (SnapcastClientCandidates.Candidate candidate : candidates) {
            ids.add(candidate.getClientId());
        }
        return ids;
    }
}
