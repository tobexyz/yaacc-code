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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.yaacc.multiroom.control.SnapcastGroup;

/**
 * The member rows of a group: sanitized names, connected flag, volume, mute and latency, in an order that does not change
 * when a volume or a connection does. Plain JVM.
 */
public class SnapcastMemberRowsTest {

    private static final SnapcastMemberRows.Words WORDS = () -> "Unnamed client";

    private static List<SnapcastMemberRows.Member> rows(SnapcastGroup group) {
        return SnapcastMemberRows.of(group, WORDS);
    }

    @Test
    public void everyMemberIsOneRowWithItsValues() {
        SnapcastGroup group = group("g", "Ground", "Default",
                client("a", "Kitchen", "kitchen-pi", true, 35, 120),
                client("b", "Hall", "", false, 80, 0));

        List<SnapcastMemberRows.Member> rows = rows(group);

        assertEquals(2, rows.size());
        SnapcastMemberRows.Member hall = rows.get(0);
        assertEquals("Hall", hall.getName());
        assertFalse(hall.isConnected());
        assertEquals(80, hall.getVolume());
        assertFalse(hall.isMuted());
        assertEquals(0, hall.getLatencyMs());
        SnapcastMemberRows.Member kitchen = rows.get(1);
        assertEquals("Kitchen", kitchen.getName());
        assertTrue(kitchen.isConnected());
        assertEquals(35, kitchen.getVolume());
        assertEquals(120, kitchen.getLatencyMs());
        assertEquals("a", kitchen.getClientId());
    }

    @Test
    public void theMuteFlagOfAMemberIsShown() {
        SnapcastGroup group = group("g", "", "", client("a", "Kitchen", true, 35, true));

        assertTrue(rows(group).get(0).isMuted());
    }

    @Test
    public void aClientWithoutANameShowsItsHostName() {
        SnapcastGroup group = group("g", "", "", client("00:11:22:33:44:55", "", "living-pi", true, 50, 0));

        assertEquals("living-pi", rows(group).get(0).getName());
    }

    @Test
    public void aClientWithNeitherNameNorHostShowsTheLabelAndNeverItsId() {
        SnapcastGroup group = group("g", "", "", client("00:11:22:33:44:55", "", "", true, 50, 0));

        String name = rows(group).get(0).getName();

        assertEquals("Unnamed client", name);
        assertFalse(name.contains("00:11"));
    }

    @Test
    public void namesAreCleanedBeforeTheyAreShown() {
        SnapcastGroup group = group("g", "", "", client("a", "Kitchen\u202E\u200B\n", true, 50, false));

        assertEquals("Kitchen???", rows(group).get(0).getName());
    }

    @Test
    public void aVeryLongNameIsCut() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            name.append('n');
        }
        SnapcastGroup group = group("g", "", "", client("a", name.toString(), true, 50, false));

        assertEquals(SnapcastMemberRows.MAX_NAME, rows(group).get(0).getName().length());
    }

    @Test
    public void theOrderIsByNameAndIgnoresCaseAndDoesNotDependOnVolumeOrConnection() {
        SnapcastGroup before = group("g", "", "",
                client("3", "attic", true, 10, false),
                client("1", "Bath", true, 20, false),
                client("2", "Cellar", false, 30, false));
        SnapcastGroup after = group("g", "", "",
                client("2", "Cellar", true, 90, true),
                client("3", "attic", false, 99, false),
                client("1", "Bath", true, 1, false));

        assertEquals(names(rows(before)), names(rows(after)));
        assertEquals("[attic, Bath, Cellar]", names(rows(before)).toString());
    }

    @Test
    public void membersWithTheSameNameKeepAStableOrderAndAreToldApart() {
        SnapcastGroup first = group("g", "", "",
                client("b", "Speaker", true, 10, false),
                client("a", "Speaker", true, 20, false),
                client("c", "Speaker", true, 30, false));
        SnapcastGroup second = group("g", "", "",
                client("c", "Speaker", true, 30, false),
                client("a", "Speaker", true, 20, false),
                client("b", "Speaker", true, 10, false));

        assertEquals("[Speaker, Speaker (2), Speaker (3)]", names(rows(first)).toString());
        assertEquals(ids(rows(first)), ids(rows(second)));
        assertEquals("[a, b, c]", ids(rows(first)).toString());
    }

    @Test
    public void aNameThatLooksLikeASuffixedOneDoesNotCollide() {
        SnapcastGroup group = group("g", "", "",
                client("a", "Speaker", true, 10, false),
                client("b", "Speaker", true, 10, false),
                client("c", "Speaker (2)", true, 10, false));

        List<String> names = names(rows(group));

        assertEquals(3, new java.util.HashSet<>(names).size());
    }

    @Test
    public void anEmptyGroupHasNoRows() {
        assertTrue(rows(group("g", "", "")).isEmpty());
    }

    @Test
    public void membersWithEqualValuesAreEqualAndAChangeShows() {
        SnapcastGroup group = group("g", "", "", client("a", "Kitchen", true, 35, false));

        assertEquals(rows(group), rows(group));
        assertNotEquals(rows(group), rows(group.withClient(client("a", "Kitchen", true, 36, false))));
        assertNotEquals(rows(group), rows(group.withClient(client("a", "Kitchen", false, 35, false))));
        assertNotEquals(rows(group), rows(group.withClient(client("a", "Kitchen", true, 35, true))));
    }

    @Test
    public void onlyTheLastMemberCannotBeRemoved() {
        SnapcastGroup two = group("g", "", "", client("a", "A", true, 1, false), client("b", "B", true, 1, false));
        SnapcastGroup one = group("g", "", "", client("a", "A", true, 1, false));

        assertTrue(SnapcastMemberRows.canRemove(two));
        assertFalse(SnapcastMemberRows.canRemove(one));
        assertFalse(SnapcastMemberRows.canRemove(group("g", "", "")));
    }

    private static List<String> names(List<SnapcastMemberRows.Member> rows) {
        List<String> names = new java.util.ArrayList<>();
        for (SnapcastMemberRows.Member row : rows) {
            names.add(row.getName());
        }
        return names;
    }

    private static List<String> ids(List<SnapcastMemberRows.Member> rows) {
        List<String> ids = new java.util.ArrayList<>();
        for (SnapcastMemberRows.Member row : rows) {
            ids.add(row.getClientId());
        }
        return ids;
    }
}
