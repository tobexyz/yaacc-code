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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * The sheet that links clients to a Snapcast group and unlinks them (11.3), in the pattern of the MusicCast link sheet: every
 * client of the server is listed with a check for the members of this group. What is listed, what is checked, what is disabled
 * and what a selection changes are decided here; the sheet only binds. Plain JVM.
 */
public class SnapcastLinkChoicesTest {

    private static final SnapcastLinkChoices.Words WORDS = new SnapcastLinkChoices.Words() {
        @Override
        public String unnamedClient() {
            return "Unnamed client";
        }

        @Override
        public String unnamedGroup() {
            return "Unnamed group";
        }

        @Override
        public String inGroup(String client, String group) {
            return client + " (in group " + group + ")";
        }

        @Override
        public String inGroupThatEnds(String client, String group) {
            return client + " (in group " + group + ", which then ends)";
        }

        @Override
        public String lastMember(String client) {
            return client + " (last client of this group)";
        }

        @Override
        public String offline(String label) {
            return label + " | offline";
        }
    };

    private static final SnapcastGroup LIVING = group("g1", "Living", "Default",
            client("a", "Kitchen", true, 50, false), client("b", "Hall", false, 50, false));
    private static final SnapcastGroup BEDROOM = group("g2", "Bedroom", "Default",
            client("c", "Bed left", true, 50, false), client("d", "Bed right", true, 50, false));
    private static final SnapcastGroup OFFICE = group("g3", "", "Default", client("e", "Desk", true, 50, false));

    private static SnapcastServerStatus server() {
        return status(Arrays.asList(LIVING, BEDROOM, OFFICE));
    }

    private static List<SnapcastLinkChoices.Choice> choices(String groupId) {
        return SnapcastLinkChoices.of(server(), groupId, WORDS);
    }

    private static SnapcastLinkChoices.Choice find(List<SnapcastLinkChoices.Choice> list, String clientId) {
        for (SnapcastLinkChoices.Choice choice : list) {
            if (choice.getClientId().equals(clientId)) {
                return choice;
            }
        }
        throw new AssertionError("no choice for " + clientId);
    }

    private static List<String> ids(List<SnapcastLinkChoices.Choice> list) {
        List<String> ids = new ArrayList<>();
        for (SnapcastLinkChoices.Choice choice : list) {
            ids.add(choice.getClientId());
        }
        return ids;
    }

    // ---- what is listed

    @Test
    public void everyClientOfTheServerIsListedInOneListOrderedByName() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        assertEquals(Arrays.asList("c", "d", "e", "b", "a"), ids(list)); // Bed left, Bed right, Desk, Hall, Kitchen
    }

    @Test
    public void theMembersOfThisGroupAreCheckedAndTheOthersAreNot() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        assertTrue(find(list, "a").isMember());
        assertTrue(find(list, "b").isMember());
        assertFalse(find(list, "c").isMember());
        assertFalse(find(list, "e").isMember());
    }

    @Test
    public void everyoneIsEnabledWhenTheGroupHasMoreThanOneMember() {
        for (SnapcastLinkChoices.Choice choice : choices("g1")) {
            assertTrue(choice.getClientId(), choice.isEnabled());
        }
    }

    @Test
    public void theOnlyMemberOfAGroupStaysCheckedAndDisabledBecauseAnEmptyListWouldEndTheGroup() {
        List<SnapcastLinkChoices.Choice> list = choices("g3");

        SnapcastLinkChoices.Choice desk = find(list, "e");
        assertTrue(desk.isMember());
        assertFalse(desk.isEnabled());
        assertTrue("the others can still be added", find(list, "a").isEnabled());
    }

    @Test
    public void aGroupThatIsNotThereOrNoStatusListsNothing() {
        assertTrue(SnapcastLinkChoices.of(server(), "nope", WORDS).isEmpty());
        assertTrue(SnapcastLinkChoices.of(null, "g1", WORDS).isEmpty());
    }

    // ---- the texts

    @Test
    public void aMemberShowsItsNameAndAnotherGroupsClientTheGroupItLeaves() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        assertEquals("Kitchen", find(list, "a").getLabel());
        assertEquals("Bed left (in group Bedroom)", find(list, "c").getLabel());
    }

    @Test
    public void aClientThatIsAloneInItsGroupSaysThatItsGroupThenEnds() {
        assertEquals("Desk (in group Desk, which then ends)", find(choices("g1"), "e").getLabel());
    }

    @Test
    public void theLastMemberIsSaidToBeTheLastOne() {
        assertEquals("Desk (last client of this group)", find(choices("g3"), "e").getLabel());
    }

    @Test
    public void aClientThatIsNotConnectedIsMarkedOffline() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        assertEquals("Hall | offline", find(list, "b").getLabel());
        assertFalse(find(list, "b").isConnected());
    }

    @Test
    public void namesFromTheServerAreCleanedAndMadeUnique() {
        SnapcastGroup odd = group("g4", "Odd\u202E", "Default", client("x", "Pi\u200B", true, 1, false),
                client("y", "Pi\u200B", true, 1, false));
        List<SnapcastLinkChoices.Choice> list = SnapcastLinkChoices.of(status(Arrays.asList(LIVING, odd)), "g1", WORDS);

        assertEquals("Pi? (in group Odd?)", find(list, "x").getLabel());
        assertEquals("Pi? (2) (in group Odd?)", find(list, "y").getLabel());
    }

    @Test
    public void aNameThatIsTwiceOnTheServerIsToldApartAcrossMembersAndOthers() {
        SnapcastGroup first = group("g1", "One", "Default", client("a", "Speaker", true, 1, false));
        SnapcastGroup second = group("g2", "Two", "Default", client("b", "Speaker", true, 1, false));
        List<SnapcastLinkChoices.Choice> list = SnapcastLinkChoices.of(status(Arrays.asList(first, second)), "g1", WORDS);

        assertEquals("Speaker (last client of this group)", find(list, "a").getLabel());
        assertEquals("Speaker (2) (in group Two, which then ends)", find(list, "b").getLabel());
    }

    @Test
    public void aNamelessClientUsesTheLabelAndANamelessGroupTheName() {
        SnapcastGroup nameless = group("g5", "", "Default", client("z", "", "", true, 1, 0), client("y", "Zed", true, 1, false));
        List<SnapcastLinkChoices.Choice> list = SnapcastLinkChoices.of(status(Arrays.asList(LIVING, nameless)), "g1", WORDS);

        assertEquals("Unnamed client (in group Unnamed group)", find(list, "z").getLabel());
    }

    @Test
    public void theTextOfAChoiceInTheLogsCarriesNeitherNameNorId() {
        for (SnapcastLinkChoices.Choice choice : choices("g1")) {
            String text = choice.toString();
            assertFalse(text, text.contains("Kitchen") || text.contains("Hall") || text.contains("Bed"));
            assertFalse(text, text.contains("clientId"));
        }
    }

    // ---- what a selection changes

    @Test
    public void nothingChangedIsAnEmptyChange() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        SnapcastLinkChoices.Change change = SnapcastLinkChoices.changeOf(list, Arrays.asList("a", "b"));

        assertTrue(change.isEmpty());
        assertTrue(change.getAdded().isEmpty());
        assertTrue(change.getRemoved().isEmpty());
    }

    @Test
    public void aCheckedStrangerIsAddedAndAnUncheckedMemberIsRemoved() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        SnapcastLinkChoices.Change change = SnapcastLinkChoices.changeOf(list, Arrays.asList("a", "c", "e"));

        assertEquals(Arrays.asList("c", "e"), change.getAdded());
        assertEquals(Collections.singletonList("b"), change.getRemoved());
        assertFalse(change.isEmpty());
    }

    @Test
    public void aDisabledMemberIsNeverRemovedEvenWhenTheSelectionLacksIt() {
        List<SnapcastLinkChoices.Choice> list = choices("g3");

        SnapcastLinkChoices.Change change = SnapcastLinkChoices.changeOf(list, Collections.<String>emptyList());

        assertTrue(change.getRemoved().isEmpty());
        assertTrue(change.isEmpty());
    }

    @Test
    public void anIdThatWasNotListedIsIgnored() {
        List<SnapcastLinkChoices.Choice> list = choices("g1");

        SnapcastLinkChoices.Change change = SnapcastLinkChoices.changeOf(list, Arrays.asList("a", "b", "zzz", "", null));

        assertTrue(change.isEmpty());
    }

    @Test
    public void aNullSelectionRemovesEveryEnabledMember() {
        SnapcastLinkChoices.Change change = SnapcastLinkChoices.changeOf(choices("g1"), null);

        assertEquals(Arrays.asList("b", "a"), change.getRemoved());
        assertTrue(change.getAdded().isEmpty());
    }
}
