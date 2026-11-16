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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerInfo;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * Task 12.12: a client id is shown once in the lists, on the server where it is connected, else on the server where it was last seen
 * (the first server of the known order on a tie). A filter over the statuses of all known servers; nothing is removed from a server.
 * Plain JVM.
 */
public class SnapcastMergedClientsTest {

    private static final SnapcastServerKey A = key(2);
    private static final SnapcastServerKey B = key(3);
    private static final SnapcastServerKey C = key(4);

    private static SnapcastServerKey key(int last) {
        return SnapcastEndpoint.parse("10.0.0." + last).toKey();
    }

    private static SnapcastClient client(String id, boolean connected, long lastSeenMicros) {
        return new SnapcastClient(id, "name " + id, "", connected, new SnapcastVolume(50, false), 0, lastSeenMicros);
    }

    private static SnapcastGroup group(String id, SnapcastClient... clients) {
        return new SnapcastGroup(id, "Group " + id, false, "Default", Arrays.asList(clients));
    }

    private static SnapcastServerStatus status(SnapcastGroup... groups) {
        return new SnapcastServerStatus(new SnapcastServerInfo("", "", "", 0, 0), Arrays.asList(groups), Collections.emptyList());
    }

    private static SnapcastMergedClients.Server up(SnapcastServerKey key, SnapcastGroup... groups) {
        return SnapcastMergedClients.Server.of(key, status(groups), true);
    }

    private static SnapcastMergedClients merged(SnapcastMergedClients.Server... servers) {
        return SnapcastMergedClients.of(Arrays.asList(servers));
    }

    private static List<String> ids(SnapcastGroup group) {
        List<String> ids = new ArrayList<>();
        for (SnapcastClient client : group.getClients()) {
            ids.add(client.getId());
        }
        return ids;
    }

    // ---- nothing to merge

    @Test
    public void aSingleServerIsShownAsItIsWithTheVeryObjects() {
        SnapcastGroup group = group("g1", client("x", true, 5), client("y", false, 3));
        SnapcastServerStatus status = status(group);
        SnapcastMergedClients merged = merged(SnapcastMergedClients.Server.of(A, status, true));

        assertFalse(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(A, "y"));
        assertSame(group, merged.visible(A, group));
        assertSame(status, merged.visible(A, status));
    }

    @Test
    public void aClientThatOnlyOneServerKnowsIsNeverHidden() {
        SnapcastMergedClients merged = merged(up(A, group("g1", client("x", false, 1))), up(B, group("g2", client("y", false, 1))));

        assertFalse(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "y"));
    }

    @Test
    public void noServersNoStatusAndUnknownThingsAreHarmless() {
        SnapcastGroup group = group("g1", client("x", true, 1));
        SnapcastMergedClients none = SnapcastMergedClients.none();
        SnapcastMergedClients silent = merged(SnapcastMergedClients.Server.of(A, null, false), up(B, group));

        assertFalse(none.isHidden(A, "x"));
        assertSame(group, none.visible(A, group));
        assertFalse(silent.isHidden(A, "x"));
        assertFalse("a server that was never listed hides nothing", silent.isHidden(C, "x"));
        assertSame(group, silent.visible(C, group));
        assertNull(silent.visible(A, (SnapcastServerStatus) null));
        assertNull(silent.visible(A, (SnapcastGroup) null));
        assertFalse(silent.isHidden(null, "x"));
        assertFalse(silent.isHidden(B, null));
        assertSame(group, SnapcastMergedClients.of(null).visible(A, group));
    }

    // ---- connected somewhere

    @Test
    public void aClientConnectedOnOneServerIsShownThereAndNotAsAnOfflineLeftoverOnTheOther() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", false, 900), client("z", true, 1))),
                up(B, group("g2", client("x", true, 100))));

        assertTrue("the offline copy on A is hidden although it was seen more recently", merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
        assertFalse("other clients are not touched", merged.isHidden(A, "z"));
    }

    @Test
    public void theOrderOfTheServersDoesNotMatterWhenAClientIsConnected() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", true, 100))),
                up(B, group("g2", client("x", false, 900))));

        assertFalse(merged.isHidden(A, "x"));
        assertTrue(merged.isHidden(B, "x"));
    }

    @Test
    public void connectedOnOneAndOfflineOnTwoOthersIsShownOnTheConnectedOneOnly() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", false, 500))),
                up(B, group("g2", client("x", true, 1))),
                up(C, group("g3", client("x", false, 700))));

        assertTrue(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
        assertTrue(merged.isHidden(C, "x"));
    }

    @Test
    public void theSameIdConnectedOnTwoServersIsShownOnBothAndThatIsOnlyDocumentedNotHandled() {
        // should not happen (a client is connected to one server at a time); then nothing is hidden: hiding one of two live entries
        // would take a working speaker out of a list
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", true, 100))),
                up(B, group("g2", client("x", true, 200))),
                up(C, group("g3", client("x", false, 900))));

        assertFalse(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
        assertTrue("an offline copy next to live ones is still a leftover", merged.isHidden(C, "x"));
    }

    @Test
    public void aServerWhoseConnectionIsDownDoesNotCountAsHavingTheClientConnected() {
        // the last status of a server that is not reachable still says "connected"; it can not be true any more
        SnapcastMergedClients merged = merged(
                SnapcastMergedClients.Server.of(A, status(group("g1", client("x", true, 100))), false),
                up(B, group("g2", client("x", true, 50))));

        assertTrue(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
    }

    @Test
    public void whenTheReachableServerHasItOfflineTheLastSeenDecidesEvenAgainstAStaleConnectedFlag() {
        SnapcastMergedClients merged = merged(
                SnapcastMergedClients.Server.of(A, status(group("g1", client("x", true, 100))), false),
                up(B, group("g2", client("x", false, 200))));

        assertTrue(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
    }

    // ---- connected nowhere

    @Test
    public void offlineEverywhereTheServerThatSawTheClientLastShowsIt() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", false, 1_700_000_001_000_000L))),
                up(B, group("g2", client("x", false, 1_700_000_002_000_000L))),
                up(C, group("g3", client("x", false, 1_600_000_000_000_000L))));

        assertTrue(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
        assertTrue(merged.isHidden(C, "x"));
    }

    @Test
    public void theMicrosecondsDecideWhenTheSecondsAreEqual() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", false, 1_700_000_001_000_123L))),
                up(B, group("g2", client("x", false, 1_700_000_001_000_124L))));

        assertTrue(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
    }

    @Test
    public void aTieOfTheLastSeenGoesToTheFirstServerOfTheKnownOrder() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", false, 500))),
                up(B, group("g2", client("x", false, 500))),
                up(C, group("g3", client("x", false, 500))));

        assertFalse(merged.isHidden(A, "x"));
        assertTrue(merged.isHidden(B, "x"));
        assertTrue(merged.isHidden(C, "x"));

        SnapcastMergedClients reversed = merged(
                up(C, group("g3", client("x", false, 500))),
                up(B, group("g2", client("x", false, 500))),
                up(A, group("g1", client("x", false, 500))));

        assertFalse("the order given is the known order", reversed.isHidden(C, "x"));
        assertTrue(reversed.isHidden(B, "x"));
        assertTrue(reversed.isHidden(A, "x"));
    }

    @Test
    public void aMissingLastSeenCountsAsTheOldestAndTwoMissingOnesAreATie() {
        SnapcastMergedClients oneMissing = merged(
                up(A, group("g1", client("x", false, 0))),
                up(B, group("g2", client("x", false, 7))));
        SnapcastMergedClients bothMissing = merged(
                up(A, group("g1", client("x", false, 0))),
                up(B, group("g2", client("x", false, 0))));

        assertTrue(oneMissing.isHidden(A, "x"));
        assertFalse(oneMissing.isHidden(B, "x"));
        assertFalse(bothMissing.isHidden(A, "x"));
        assertTrue(bothMissing.isHidden(B, "x"));
    }

    @Test
    public void everyClientIsDecidedOnItsOwn() {
        SnapcastMergedClients merged = merged(
                up(A, group("g1", client("x", false, 100), client("y", true, 1))),
                up(B, group("g2", client("x", true, 1), client("y", false, 900))));

        assertTrue(merged.isHidden(A, "x"));
        assertFalse(merged.isHidden(B, "x"));
        assertFalse(merged.isHidden(A, "y"));
        assertTrue(merged.isHidden(B, "y"));
    }

    // ---- what the lists get

    @Test
    public void aGroupWithSomeHiddenMembersKeepsTheOthersInOrderAndEverythingElse() {
        SnapcastGroup original = new SnapcastGroup("g1", "Living", true, "Radio",
                Arrays.asList(client("a", true, 1), client("x", false, 100), client("c", false, 2)));
        SnapcastMergedClients merged = merged(up(A, original), up(B, group("g2", client("x", true, 1))));

        SnapcastGroup visible = merged.visible(A, original);

        assertNotNull(visible);
        assertEquals(Arrays.asList("a", "c"), ids(visible));
        assertEquals("g1", visible.getId());
        assertEquals("Living", visible.getName());
        assertTrue(visible.isMuted());
        assertEquals("Radio", visible.getStreamId());
        assertEquals("the original is not changed", Arrays.asList("a", "x", "c"), ids(original));
    }

    @Test
    public void aGroupWhoseMembersAreAllShownElsewhereIsHidden() {
        SnapcastGroup ghosts = group("g1", client("x", false, 100), client("y", false, 100));
        SnapcastGroup other = group("g2", client("z", false, 1));
        SnapcastMergedClients merged = merged(up(A, ghosts, other), up(B, group("g3", client("x", true, 1), client("y", true, 1))));

        assertNull(merged.visible(A, ghosts));
        assertNotNull(merged.visible(A, other));
        SnapcastServerStatus visible = merged.visible(A, status(ghosts, other));
        assertEquals(1, visible.getGroups().size());
        assertEquals("g2", visible.getGroups().get(0).getId());
    }

    @Test
    public void aGroupThatNeverHadMembersIsNotHiddenByTheFilter() {
        SnapcastGroup empty = group("g1");
        SnapcastMergedClients merged = merged(up(A, empty, group("g2", client("x", false, 1))), up(B, group("g3", client("x", true, 1))));

        assertSame(empty, merged.visible(A, empty));
    }

    @Test
    public void theVisibleStatusKeepsTheStreamsAndTheInfo() {
        SnapcastGroup group = group("g1", client("x", false, 100), client("a", true, 1));
        SnapcastServerStatus status = status(group);
        SnapcastMergedClients merged = merged(SnapcastMergedClients.Server.of(A, status, true), up(B, group("g2", client("x", true, 1))));

        SnapcastServerStatus visible = merged.visible(A, status);

        assertEquals(status.getInfo(), visible.getInfo());
        assertEquals(status.getStreams(), visible.getStreams());
        assertEquals(Arrays.asList("a"), ids(visible.getGroups().get(0)));
    }

    @Test
    public void aTitleThatComesFromTheFirstMemberComesFromTheFirstVisibleOne() {
        SnapcastGroup original = new SnapcastGroup("g1", "", false, "Default",
                Arrays.asList(client("x", false, 100), client("a", true, 1)));
        SnapcastMergedClients merged = merged(up(A, original), up(B, group("g2", client("x", true, 1))));

        assertEquals("a", merged.visible(A, original).getClients().get(0).getId());
    }
}
