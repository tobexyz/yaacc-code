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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;

/**
 * The full wanted member list that {@code Group.SetClients} needs after the link sheet changed the members (the server
 * removes every client that is not in the list, and each removed client gets a group of its own). Plain JVM.
 */
public class SnapcastClientCandidatesTest {

    private static final SnapcastGroup LIVING = group("g1", "Living", "Default",
            client("a", "Kitchen", true, 50, false), client("b", "Hall", true, 50, false));
    private static final SnapcastGroup OFFICE = group("g3", "", "Default", client("e", "Desk", true, 50, false));

    // ---- 11.3: one change that adds and removes members at once (the link sheet)

    @Test
    public void aChangeKeepsTheOthersInTheirOrderDropsTheRemovedAndAppendsTheAdded() {
        List<String> wanted = SnapcastClientCandidates.wantedAfter(LIVING, Arrays.asList("c", "e"), Collections.singletonList("a"));

        assertEquals(Arrays.asList("b", "c", "e"), wanted);
    }

    @Test
    public void aChangeThatChangesNothingGivesTheCurrentMembers() {
        assertEquals(Arrays.asList("a", "b"),
                SnapcastClientCandidates.wantedAfter(LIVING, Collections.<String>emptyList(), Collections.<String>emptyList()));
        assertEquals(Arrays.asList("a", "b"), SnapcastClientCandidates.wantedAfter(LIVING, null, null));
    }

    @Test
    public void aRemovedClientThatLeftAlreadyAndAnAddedOneThatIsInAlreadyAreSkipped() {
        assertEquals(Arrays.asList("a", "b"),
                SnapcastClientCandidates.wantedAfter(LIVING, Collections.singletonList("a"), Collections.singletonList("zzz")));
    }

    @Test
    public void aChangeThatEmptiesTheGroupIsRefusedBecauseAnEmptyListWouldDissolveIt() {
        assertNull(SnapcastClientCandidates.wantedAfter(LIVING, Collections.<String>emptyList(), Arrays.asList("a", "b")));
        assertNull(SnapcastClientCandidates.wantedAfter(OFFICE, Collections.<String>emptyList(), Collections.singletonList("e")));
    }

    @Test
    public void aClientThatIsRemovedAndAddedAtOnceStays() {
        assertEquals(Arrays.asList("b", "a"),
                SnapcastClientCandidates.wantedAfter(LIVING, Collections.singletonList("a"), Collections.singletonList("a")));
    }

    @Test
    public void aChangeThatMakesTheGroupLargerThanTheLimitIsRefused() {
        List<SnapcastClient> members = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            members.add(client("m" + i, "M" + i, true, 1, false));
        }
        SnapcastGroup full = new SnapcastGroup("big", "Big", false, "Default", members);

        assertNull(SnapcastClientCandidates.wantedAfter(full, Collections.singletonList("c"), Collections.<String>emptyList()));
        assertEquals("one out and one in is fine", 256,
                SnapcastClientCandidates.wantedAfter(full, Collections.singletonList("c"), Collections.singletonList("m0")).size());
    }
}
