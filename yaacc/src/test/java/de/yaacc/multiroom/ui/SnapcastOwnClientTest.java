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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.yaacc.multiroom.client.SnapcastOwnIds;
import de.yaacc.multiroom.control.SnapcastGroup;

/**
 * Task 12.11, 12C (B and C): the app recognises its own client by the id in a server's status (also an offline entry), marks it in
 * the member rows, and decides which Snapcast group card has the power button. Table tests, plain JVM.
 */
public class SnapcastOwnClientTest {

    private static final String OWN = "yaacc-0123456789abcdef";

    // ---- the matcher ---------------------------------------------------------------------------------------------------

    @Test
    public void aClientWithTheOwnIdIsTheOwnClientWhetherItIsConnectedOrNot() {
        assertTrue(SnapcastOwnClient.isOwn(OWN, OWN));
        assertTrue("the id is compared, not the connection: an offline entry is the phone's too", SnapcastOwnClient.isOwn(new String(OWN), OWN));
    }

    @Test
    public void everythingElseIsNotTheOwnClient() {
        String[][] table = {
                {"yaacc-0123456789abcdee", OWN},   // another id of the same shape
                {"YAACC-0123456789ABCDEF", OWN},   // the server's id is compared as it is, not by case
                {OWN + " ", OWN},                  // no trimming
                {"00:11:22:33:44:55", OWN},        // an ordinary client
                {"", OWN},
                {null, OWN},
                {OWN, null},                       // no own id known (no secret yet): nothing is own
                {OWN, ""},
                {null, null},
                {"", ""},
        };
        for (String[] row : table) {
            assertFalse(row[0] + " / " + row[1], SnapcastOwnClient.isOwn(row[0], row[1]));
        }
    }

    // ---- the member rows ------------------------------------------------------------------------------------------------

    @Test
    public void theMemberRowOfTheOwnClientIsMarked() {
        SnapcastGroup group = group("g", "Ground", "Default",
                client("a", "Kitchen", true, 35, false),
                client(OWN, "Yaacc", false, 50, false));

        List<SnapcastMemberRows.Member> rows = SnapcastMemberRows.of(group, () -> "Unnamed", OWN);

        assertEquals(2, rows.size());
        for (SnapcastMemberRows.Member member : rows) {
            assertEquals(member.getName(), member.getClientId().equals(OWN), member.isOwn());
        }
    }

    @Test
    public void withoutAnOwnIdNothingIsMarkedAndTheOldOverloadMarksNothing() {
        SnapcastGroup group = group("g", "", "", client("a", "Kitchen", true, 35, false), client(OWN, "Yaacc", true, 50, false));

        for (SnapcastMemberRows.Member member : SnapcastMemberRows.of(group, () -> "Unnamed", null)) {
            assertFalse(member.isOwn());
        }
        for (SnapcastMemberRows.Member member : SnapcastMemberRows.of(group, () -> "Unnamed")) {
            assertFalse(member.isOwn());
        }
    }

    @Test
    public void theMarkIsPartOfWhatMakesARowLookDifferent() {
        SnapcastGroup group = group("g", "", "", client(OWN, "Yaacc", true, 50, false));

        SnapcastMemberRows.Member marked = SnapcastMemberRows.of(group, () -> "Unnamed", OWN).get(0);
        SnapcastMemberRows.Member plain = SnapcastMemberRows.of(group, () -> "Unnamed", null).get(0);

        assertFalse("the sheet redraws when the mark appears", marked.equals(plain));
    }

    // ---- which card gets the power button -------------------------------------------------------------------------------

    @Test
    public void theCardOfAGroupWhoseFirstMemberIsTheOwnClientHasTheButton() {
        assertTrue(SnapcastOwnClient.cardOffersPower(group("g", "", "", client(OWN, "Yaacc", true, 50, false)), OWN));
        assertTrue("an offline entry too: power on is enabled there",
                SnapcastOwnClient.cardOffersPower(group("g", "", "", client(OWN, "Yaacc", false, 50, false)), OWN));
        assertTrue("first member of several (the one whose name titles the card)", SnapcastOwnClient.cardOffersPower(
                group("g", "", "", client(OWN, "Yaacc", true, 50, false), client("a", "Kitchen", true, 35, false)), OWN));
    }

    @Test
    public void everyOtherCardHasNone() {
        SnapcastGroup notFirst = group("g", "", "", client("a", "Kitchen", true, 35, false), client(OWN, "Yaacc", true, 50, false));
        SnapcastGroup others = group("g", "", "", client("a", "Kitchen", true, 35, false), client("b", "Hall", true, 35, false));

        assertFalse("the own client is a member but not the first one: the card is titled by another client", SnapcastOwnClient.cardOffersPower(notFirst, OWN));
        assertFalse(SnapcastOwnClient.cardOffersPower(others, OWN));
        assertFalse("a group without members", SnapcastOwnClient.cardOffersPower(group("g", "", ""), OWN));
        assertFalse("no group (the server does not answer)", SnapcastOwnClient.cardOffersPower(null, OWN));
        assertFalse("no own id", SnapcastOwnClient.cardOffersPower(group("g", "", "", client(OWN, "Yaacc", true, 50, false)), null));
        assertFalse(SnapcastOwnClient.cardOffersPower(group("g", "", "", client(OWN, "Yaacc", true, 50, false)), ""));
    }

    @Test
    public void aLookAlikeNameOrHostNameDoesNotMakeAClientTheOwnOne() {
        // names and host names are the server's text and never an identity: only the id counts
        SnapcastGroup group = group("g", "Yaacc", "", client("00:11:22:33:44:55", OWN, OWN, true, 50, 0));

        assertFalse(SnapcastOwnClient.cardOffersPower(group, OWN));
        assertFalse(SnapcastMemberRows.of(group, () -> "Unnamed", OWN).get(0).isOwn());
    }

    // ---- removing the own entry: a ghost while no session runs, never while one does -------------------------------------------

    private static de.yaacc.multiroom.control.SnapcastServerStatus statusWithOwnOffline() {
        return UiFixtures.status(group("g", "", "", client(OWN, "Yaacc", false, 50, false), client("a", "Kitchen", false, 30, false)));
    }

    @Test
    public void anOfflineOwnEntryIsAGhostAndMayBeRemovedWhileNoSessionRuns() {
        SnapcastClientRemoval.Own none = SnapcastClientRemoval.ownOf(false, OWN);

        assertEquals(SnapcastClientRemoval.Verdict.REMOVABLE, SnapcastClientRemoval.decide(statusWithOwnOffline(), OWN, none));
        assertTrue(SnapcastClientRemoval.offered(false, OWN, none));
    }

    @Test
    public void theOwnEntryIsNeverRemovedWhileASessionStartsRunsOrReconnectsEvenWhenItLooksOffline() {
        SnapcastClientRemoval.Own session = SnapcastClientRemoval.ownOf(true, OWN);

        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT, SnapcastClientRemoval.decide(statusWithOwnOffline(), OWN, session));
        assertFalse(SnapcastClientRemoval.offered(false, OWN, session));
        // another offline entry is still removable during the session
        assertEquals(SnapcastClientRemoval.Verdict.REMOVABLE, SnapcastClientRemoval.decide(statusWithOwnOffline(), "a", session));
        assertTrue(SnapcastClientRemoval.offered(false, "a", session));
    }

    @Test
    public void aSessionWithoutAKnownOwnIdProtectsEveryEntry() {
        SnapcastClientRemoval.Own unknown = SnapcastClientRemoval.ownOf(true, null);

        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT_UNKNOWN, SnapcastClientRemoval.decide(statusWithOwnOffline(), "a", unknown));
        assertFalse(SnapcastClientRemoval.offered(false, "a", unknown));
    }

    // ---- task 12.19: the legacy ids of this install are the phone too ---------------------------------------------------

    private static final String LEGACY = "yaacc-1111111111111111";
    private static final SnapcastOwnIds IDS = SnapcastOwnIds.of(OWN, java.util.Arrays.asList(LEGACY));

    @Test
    public void aLegacyIdIsOwnAndLegacyAndTheCurrentOneIsOwnButNotLegacy() {
        assertTrue(SnapcastOwnClient.isOwnOrLegacy(OWN, IDS));
        assertTrue(SnapcastOwnClient.isOwnOrLegacy(LEGACY, IDS));
        assertTrue(SnapcastOwnClient.isLegacy(LEGACY, IDS));
        assertFalse(SnapcastOwnClient.isLegacy(OWN, IDS));
    }

    @Test
    public void anotherInstallOrNothingIsNeitherOwnNorLegacy() {
        for (String other : new String[]{"yaacc-bbbbbbbbbbbbbbbb", "", null, "00:11:22:33:44:55"}) {
            assertFalse(String.valueOf(other), SnapcastOwnClient.isOwnOrLegacy(other, IDS));
            assertFalse(String.valueOf(other), SnapcastOwnClient.isLegacy(other, IDS));
        }
        assertFalse(SnapcastOwnClient.isOwnOrLegacy(LEGACY, null));
        assertFalse(SnapcastOwnClient.isOwnOrLegacy(LEGACY, SnapcastOwnIds.none()));
        assertFalse(SnapcastOwnClient.isLegacy(LEGACY, null));
    }

    @Test
    public void theMemberRowOfALegacyEntryIsThePhoneAndFlaggedAsAnOldEntry() {
        SnapcastGroup group = group("g", "Ground", "Default",
                client("a", "Kitchen", true, 35, false),
                client(LEGACY, "Yaacc", false, 50, false),
                client(OWN, "Yaacc", true, 50, false));

        for (SnapcastMemberRows.Member member : SnapcastMemberRows.ofIds(group, () -> "Unnamed", IDS)) {
            boolean phone = member.getClientId().equals(OWN) || member.getClientId().equals(LEGACY);
            assertEquals(member.getClientId(), phone, member.isOwn());
            assertEquals(member.getClientId(), member.getClientId().equals(LEGACY), member.isLegacy());
        }
    }

    @Test
    public void theOldFlagIsPartOfWhatMakesARowLookDifferent() {
        SnapcastGroup group = group("g", "", "", client(LEGACY, "Yaacc", false, 50, false));

        SnapcastMemberRows.Member old = SnapcastMemberRows.ofIds(group, () -> "Unnamed", IDS).get(0);
        SnapcastMemberRows.Member plain = SnapcastMemberRows.ofIds(group, () -> "Unnamed", SnapcastOwnIds.none()).get(0);

        assertFalse(old.equals(plain));
    }

    @Test
    public void theCardOfAGroupWhoseFirstMemberIsALegacyEntryHasTheButtonToo() {
        assertTrue(SnapcastOwnClient.cardOffersPowerForIds(group("g", "", "", client(LEGACY, "Yaacc", false, 50, false)), IDS));
        assertFalse("not the first member", SnapcastOwnClient.cardOffersPowerForIds(
                group("g", "", "", client("a", "Kitchen", true, 35, false), client(LEGACY, "Yaacc", false, 50, false)), IDS));
        assertFalse(SnapcastOwnClient.cardOffersPowerForIds(group("g", "", "", client(LEGACY, "Yaacc", false, 50, false)), SnapcastOwnIds.none()));
        assertFalse(SnapcastOwnClient.cardOffersPowerForIds(null, IDS));
    }
}
