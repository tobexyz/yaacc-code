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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.multiroom.SnapcastRendererSources.Entry;
import de.yaacc.multiroom.SnapcastRendererSources.GroupRef;
import de.yaacc.multiroom.SnapcastRendererSources.Kind;
import de.yaacc.multiroom.SnapcastRendererSources.Snapshot;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The sources of the OpenHome renderer as the Snapcast groups make them (task 12.5): "this device" first as today, then one source
 * per known group, in a stable order, names cleaned, ids kept in the mapping and never in a visible text, at most 16, the chosen
 * group always listed, and the mapping of a source back to the output setting. Plain JVM.
 */
public class SnapcastRendererSourcesTest {

    private static final String SERVER = "192.168.1.5:1705";
    private static final SnapcastRendererSources.Labels LABELS = new SnapcastRendererSources.Labels("Snapcast group",
            "Snapcast group (not available)");

    private static String id(String group) {
        return SnapcastGroupDevice.idOf(SnapcastEndpoint.parse(SERVER).toKey(), group);
    }

    private static GroupRef group(String groupId, String name) {
        return new GroupRef(id(groupId), name);
    }

    private static SnapcastRendererOutput.Setting setting(String output, String receiverId) {
        Map<String, String> stored = new HashMap<>();
        if (output != null) {
            stored.put(SnapcastRendererOutput.KEY_OUTPUT, output);
        }
        if (receiverId != null) {
            stored.put(SnapcastRendererOutput.KEY_GROUP, receiverId);
        }
        return SnapcastRendererOutput.read(stored::get);
    }

    private static Snapshot build(SnapcastRendererOutput.Setting setting, GroupRef... groups) {
        return SnapcastRendererSources.build(setting, Arrays.asList(groups), LABELS);
    }

    private static List<String> names(Snapshot snapshot) {
        List<String> names = new ArrayList<>();
        for (Entry entry : snapshot.entries()) {
            names.add(entry.name());
        }
        return names;
    }

    // ---- this device alone

    @Test
    public void withNoGroupKnownOnlyThisDeviceIsListedAsToday() {
        Snapshot snapshot = build(setting(null, null));

        assertEquals(1, snapshot.entries().size());
        Entry device = snapshot.entries().get(0);
        assertEquals(0, device.index());
        assertEquals(Kind.THIS_DEVICE, device.kind());
        assertEquals("the names of today's single source", "Playlist", device.name());
        assertEquals("Playlist", device.type());
        assertEquals("Playlist", device.systemName());
        assertNull(device.receiverId());
        assertEquals(0, snapshot.currentIndex());
    }

    @Test
    public void aMissingOrNullListIsThisDeviceAlone() {
        assertEquals(1, SnapcastRendererSources.build(setting(null, null), null, LABELS).entries().size());
        assertEquals(1, SnapcastRendererSources.build(null, null, LABELS).entries().size());
    }

    // ---- order, names, ids

    @Test
    public void groupsFollowThisDeviceOrderedByNameIgnoringCaseThenById() {
        Snapshot snapshot = build(setting(null, null), group("g3", "kitchen"), group("g1", "Attic"), group("g2", "Kitchen"),
                group("g0", "Zeta"));

        assertEquals(Arrays.asList("Playlist", "Attic", "Kitchen", "kitchen", "Zeta"), names(snapshot));
        for (int i = 0; i < snapshot.entries().size(); i++) {
            assertEquals(i, snapshot.entries().get(i).index());
        }
        assertEquals("equal names: the id decides, so the order is stable", id("g2"), snapshot.entries().get(2).receiverId());
        assertEquals(id("g3"), snapshot.entries().get(3).receiverId());
    }

    @Test
    public void groupsWithTheSameNameAreToldApartByANumber() {
        Snapshot snapshot = build(setting(null, null), group("g2", "Kitchen"), group("g1", "Kitchen"), group("g3", "Kitchen"));

        assertEquals(Arrays.asList("Playlist", "Kitchen", "Kitchen (2)", "Kitchen (3)"), names(snapshot));
        assertEquals("by id order", id("g1"), snapshot.entries().get(1).receiverId());
    }

    @Test
    public void theSameGroupsInAnotherOrderGiveTheSameSnapshot() {
        Snapshot one = build(setting(null, null), group("g1", "A"), group("g2", "B"), group("g3", "C"));
        Snapshot other = build(setting(null, null), group("g3", "C"), group("g1", "A"), group("g2", "B"));

        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
    }

    @Test
    public void theVisibleTextsNeverCarryAnIdOrAnAddress() {
        Snapshot snapshot = build(setting(null, null), group("g-secret-1", "Kitchen"), group("g-secret-2", "Hall"));

        for (Entry entry : snapshot.entries()) {
            for (String text : new String[]{entry.name(), entry.type(), entry.systemName()}) {
                assertFalse(text, text.contains("g-secret") || text.contains("192.168") || text.contains("snapcast:")
                        || text.contains("1705"));
            }
        }
        assertEquals("the id is in the mapping", id("g-secret-1"), snapshot.entries().get(2).receiverId());
    }

    @Test
    public void theSystemNameIsAStableOpaqueTokenOfTheId() {
        Snapshot first = build(setting(null, null), group("g1", "Kitchen"));
        Snapshot later = build(setting(null, null), group("g0", "Attic"), group("g1", "Kitchen")); // the list changed around it

        String token = first.entries().get(1).systemName();
        assertTrue(token, token.matches("snapcast-[0-9a-f]{16}"));
        assertEquals("the same group has the same token when the list changes (a controller may hold it)", token,
                later.entries().get(2).systemName());
        assertNotEquals(token, later.entries().get(1).systemName());
        assertEquals(token, SnapcastRendererSources.tokenOf(id("g1")));
    }

    @Test
    public void namesAreCleanedLikeEveryNameFromAServerAndEmptyOnesGetALabel() {
        Snapshot snapshot = build(setting(null, null), group("g1", "  Liv​ing\nroom  "), group("g2", "   "), group("g3", null));

        assertEquals("Playlist", snapshot.entries().get(0).name());
        for (Entry entry : snapshot.entries().subList(1, snapshot.entries().size())) {
            assertFalse(entry.name(), entry.name().contains("\n") || entry.name().contains("​"));
            assertEquals(entry.name(), entry.name().trim());
        }
        boolean found = false;
        for (String name : names(snapshot)) {
            found = found || (name.startsWith("Liv") && name.endsWith("room") && name.length() <= "Liv?ing?room".length());
        }
        assertTrue(names(snapshot).toString(), found);
        assertTrue("an empty name is the label", names(snapshot).contains("Snapcast group"));
        assertTrue("the second empty one is told apart", names(snapshot).contains("Snapcast group (2)"));
    }

    @Test
    public void aLongNameIsCut() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('x');
        }

        Snapshot snapshot = build(setting(null, null), group("g1", longName.toString()));

        assertTrue(snapshot.entries().get(1).name().length() <= SnapcastRendererSources.MAX_NAME);
    }

    @Test
    public void aGroupWithAnIdThatIsNotCanonicalOrIsListedTwiceIsLeftOut() {
        Snapshot snapshot = build(setting(null, null), new GroupRef("not an id", "Bad"), new GroupRef(null, "Null"),
                group("g1", "Kitchen"), group("g1", "Kitchen again"));

        assertEquals(Arrays.asList("Playlist", "Kitchen"), names(snapshot));
    }

    // ---- the limit

    @Test
    public void atMostSixteenGroupsAreListed() {
        List<GroupRef> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add(group("g" + i, String.format("Group %04d", i)));
        }

        Snapshot snapshot = SnapcastRendererSources.build(setting(null, null), many, LABELS);

        assertEquals(1 + SnapcastRendererSources.MAX_GROUPS, snapshot.entries().size());
        assertEquals(16, SnapcastRendererSources.MAX_GROUPS);
        assertEquals("Group 0000", snapshot.entries().get(1).name());
        assertEquals("Group 0015", snapshot.entries().get(16).name());
    }

    @Test
    public void theChosenGroupIsListedEvenBeyondTheLimit() {
        List<GroupRef> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(group("g" + i, String.format("Group %04d", i)));
        }
        String chosen = id("g39");

        Snapshot snapshot = SnapcastRendererSources.build(setting("group", chosen), many, LABELS);

        assertEquals(1 + 16, snapshot.entries().size());
        assertEquals("Group 0039", snapshot.entries().get(snapshot.currentIndex()).name());
        assertEquals(chosen, snapshot.entries().get(snapshot.currentIndex()).receiverId());
        assertEquals("the others are the first ones, in order", "Group 0014", snapshot.entries().get(15).name());
    }

    // ---- which source is the current one: the same state as the setting

    @Test
    public void thisDeviceIsCurrentForTheDeviceSettingAndForADamagedOne() {
        assertEquals(0, build(setting("device", null), group("g1", "Kitchen")).currentIndex());
        assertEquals("damaged: this device is used, the setting says so", 0,
                build(setting("group", "garbage"), group("g1", "Kitchen")).currentIndex());
        assertEquals("an output value that is not known is damaged too", 0, build(setting("tape", null), group("g1", "A")).currentIndex());
    }

    @Test
    public void theChosenGroupIsTheCurrentSource() {
        Snapshot snapshot = build(setting("group", id("g2")), group("g1", "Attic"), group("g2", "Kitchen"), group("g3", "Zeta"));

        assertEquals(2, snapshot.currentIndex());
        assertEquals(id("g2"), snapshot.current().receiverId());
        assertEquals(Kind.GROUP, snapshot.current().kind());
    }

    @Test
    public void aChosenGroupThatIsNotKnownIsShownAsNotAvailableAndStaysCurrent() {
        Snapshot snapshot = build(setting("group", id("gone")), group("g1", "Kitchen"));

        assertEquals(Arrays.asList("Playlist", "Kitchen", "Snapcast group (not available)"), names(snapshot));
        assertEquals(2, snapshot.currentIndex());
        assertEquals(Kind.UNAVAILABLE_GROUP, snapshot.current().kind());
        assertFalse("it can not be chosen again", snapshot.current().selectable());
        assertTrue(snapshot.current().systemName().matches("snapcast-[0-9a-f]{16}"));
    }

    @Test
    public void groupModeWithoutAGroupShowsTheSamePlaceholder() {
        Snapshot snapshot = build(setting("group", null), group("g1", "Kitchen"));

        assertEquals(2, snapshot.currentIndex());
        assertEquals(Kind.UNAVAILABLE_GROUP, snapshot.current().kind());
        assertNull(snapshot.current().receiverId());
        assertFalse(snapshot.current().selectable());
    }

    @Test
    public void thePlaceholderDoesNotPushTheListOverTheLimit() {
        List<GroupRef> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(group("g" + i, String.format("Group %04d", i)));
        }

        Snapshot snapshot = SnapcastRendererSources.build(setting("group", id("gone")), many, LABELS);

        assertEquals(1 + 16, snapshot.entries().size());
        assertEquals(Kind.UNAVAILABLE_GROUP, snapshot.entries().get(16).kind());
        assertEquals(16, snapshot.currentIndex());
    }

    // ---- finding a source again, and what it means for the setting

    @Test
    public void aSourceIsFoundByIndexByNameAndBySystemName() {
        Snapshot snapshot = build(setting(null, null), group("g1", "Attic"), group("g2", "Kitchen"));

        assertEquals(id("g2"), snapshot.byIndex(2).receiverId());
        assertNull(snapshot.byIndex(3));
        assertNull(snapshot.byIndex(-1));
        assertEquals("Kitchen", snapshot.byName("Kitchen").name());
        assertEquals(0, snapshot.byName("Playlist").index());
        assertNull(snapshot.byName("kitchen"));
        assertNull(snapshot.byName(null));
        assertEquals("Attic", snapshot.bySystemName(SnapcastRendererSources.tokenOf(id("g1"))).name());
        assertEquals(0, snapshot.bySystemName("Playlist").index());
        assertNull(snapshot.bySystemName("Radio"));
        assertNull("the id itself is no system name", snapshot.bySystemName(id("g1")));
    }

    @Test
    public void whatASourceMeansForTheSetting() {
        Snapshot snapshot = build(setting("group", id("gone")), group("g1", "Kitchen"));

        assertEquals(SnapcastRendererSources.Target.deviceTarget(), snapshot.byIndex(0).target());
        SnapcastRendererSources.Target group = snapshot.byIndex(1).target();
        assertEquals(SnapcastRendererOutput.Mode.GROUP, group.mode());
        assertEquals(id("g1"), group.receiverId());
        assertNull("not selectable", snapshot.byIndex(2).target());
    }

    @Test
    public void textsOfTheModelNeverCarryTheId() {
        Snapshot snapshot = build(setting("group", id("g1")), group("g1", "Kitchen"));

        assertFalse(snapshot.toString().contains("192.168"));
        assertFalse(snapshot.current().toString().contains("192.168"));
        assertFalse(snapshot.current().target().toString().contains("192.168"));
    }
}
