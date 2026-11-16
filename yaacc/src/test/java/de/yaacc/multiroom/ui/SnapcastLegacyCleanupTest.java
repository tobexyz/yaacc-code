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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.client.SnapcastOwnIds;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * Task 12.19: which entries "Remove old entries of this phone" may delete. Only an entry of the FULL status of the server whose id
 * is one of this install's legacy ids and that is offline; never a connected one, never the current id, never an id of another
 * install. The call itself decides again from a fresh status ({@link SnapcastClientRemoval}). Plain JVM.
 */
public class SnapcastLegacyCleanupTest {

    private static final String NEW = "yaacc-aaaaaaaaaaaaaaaa";
    private static final String OLD1 = "yaacc-1111111111111111";
    private static final String OLD2 = "yaacc-2222222222222222";
    private static final String OTHER_INSTALL = "yaacc-bbbbbbbbbbbbbbbb";
    private static final SnapcastOwnIds IDS = SnapcastOwnIds.of(NEW, Arrays.asList(OLD1, OLD2));

    private static SnapcastServerStatus status(SnapcastGroup... groups) {
        return UiFixtures.status(new ArrayList<>(Arrays.asList(groups)));
    }

    @Test
    public void offlineLegacyEntriesAreRemovableInTheOrderOfTheStatus() {
        SnapcastServerStatus status = status(
                group("g1", "", "Default", client(OLD2, "Yaacc", false, 50, false), client("a", "Kitchen", true, 30, false)),
                group("g2", "", "Default", client(OLD1, "Yaacc", false, 50, false)));

        assertEquals(Arrays.asList(OLD2, OLD1), SnapcastLegacyCleanup.removable(status, IDS));
    }

    @Test
    public void aConnectedLegacyEntryIsNeverRemovable() {
        SnapcastServerStatus status = status(group("g1", "", "Default", client(OLD1, "Yaacc", true, 50, false),
                client(OLD2, "Yaacc", false, 50, false)));

        assertEquals(Collections.singletonList(OLD2), SnapcastLegacyCleanup.removable(status, IDS));
    }

    @Test
    public void theCurrentIdIsNeverRemovedByThisAction() {
        SnapcastServerStatus status = status(group("g1", "", "Default", client(NEW, "Yaacc", false, 50, false),
                client(OLD1, "Yaacc", false, 50, false)));

        assertEquals(Collections.singletonList(OLD1), SnapcastLegacyCleanup.removable(status, IDS));
    }

    @Test
    public void otherInstallsAndOrdinaryClientsAreNeverRemovable() {
        SnapcastServerStatus status = status(group("g1", "", "Default", client(OTHER_INSTALL, "Yaacc", false, 50, false),
                client("00:11:22:33:44:55", "Yaacc", false, 50, false), client("a", "Yaacc", false, 50, false)));

        assertTrue(SnapcastLegacyCleanup.removable(status, IDS).isEmpty());
    }

    @Test
    public void nothingWithoutStatusIdsOrLegacyEntries() {
        SnapcastServerStatus status = status(group("g1", "", "Default", client(OLD1, "Yaacc", false, 50, false)));

        assertTrue(SnapcastLegacyCleanup.removable(null, IDS).isEmpty());
        assertTrue(SnapcastLegacyCleanup.removable(status, null).isEmpty());
        assertTrue(SnapcastLegacyCleanup.removable(status, SnapcastOwnIds.none()).isEmpty());
        assertTrue(SnapcastLegacyCleanup.removable(status, SnapcastOwnIds.of(NEW, Collections.<String>emptyList())).isEmpty());
        assertTrue(SnapcastLegacyCleanup.removable(status(group("g1", "", "Default")), IDS).isEmpty());
    }

    @Test
    public void anIdOnTwoGroupsOrRepeatedIsListedOnce() {
        SnapcastServerStatus status = status(group("g1", "", "Default", client(OLD1, "Yaacc", false, 50, false)),
                group("g2", "", "Default", client(OLD1, "Yaacc", false, 50, false)));

        assertEquals(Collections.singletonList(OLD1), SnapcastLegacyCleanup.removable(status, IDS));
    }

    @Test
    public void theListIsBounded() {
        List<String> many = new ArrayList<>();
        List<de.yaacc.multiroom.control.SnapcastClient> clients = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String id = String.format("yaacc-%016x", i);
            many.add(id);
            clients.add(client(id, "Yaacc", false, 50, false));
        }
        SnapcastGroup big = new SnapcastGroup("g", "", false, "Default", clients);

        List<String> removable = SnapcastLegacyCleanup.removable(status(big), SnapcastOwnIds.of(NEW, many));

        assertEquals(SnapcastLegacyCleanup.MAX_REMOVABLE, removable.size());
    }
}
