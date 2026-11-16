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
package de.yaacc.upnp.server.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererSources;
import de.yaacc.multiroom.SnapcastRendererSources.Entry;
import de.yaacc.multiroom.SnapcastRendererSources.GroupRef;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The source list of the OpenHome renderer as one way into the output setting of the renderer (task 12.5): the list is built from the
 * setting and the groups known now, choosing a source writes the SAME two preferences as the settings screen, a group that vanished
 * is refused without changing anything, and the user is told once per refusal. Plain JVM: the preferences, the groups and the
 * messages are fakes.
 */
public class RendererOutputSourcesTest {

    private static final SnapcastRendererSources.Labels LABELS = new SnapcastRendererSources.Labels("Snapcast group", "Snapcast group (not available)");

    private static String id(String group) {
        return SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group);
    }

    private final Map<String, String> stored = new HashMap<>();
    private final List<GroupRef> groups = new ArrayList<>();
    private final List<Map<String, String>> writes = new ArrayList<>();
    private final List<RendererOutputSources.Refusal> problems = new ArrayList<>();
    private boolean writeFails;
    private final RendererOutputSources sources = new RendererOutputSources(stored::get, values -> {
        if (writeFails) {
            throw new IllegalStateException("disk full");
        }
        writes.add(new LinkedHashMap<>(values));
        stored.putAll(values);
    }, () -> new ArrayList<>(groups), LABELS, problems::add);

    private void known(String groupId, String name) {
        groups.add(new GroupRef(id(groupId), name));
    }

    // ---- the list follows the setting and the groups

    @Test
    public void theListIsBuiltFromTheSettingAndTheGroupsOfNow() {
        assertEquals("only this device while no group is known", 1, sources.snapshot().entries().size());

        known("g1", "Kitchen");
        known("g2", "Attic");
        stored.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        stored.put(SnapcastRendererOutput.KEY_GROUP, id("g1"));

        SnapcastRendererSources.Snapshot snapshot = sources.snapshot();
        assertEquals(3, snapshot.entries().size());
        assertEquals("the settings screen's choice is the current source", "Kitchen", snapshot.current().name());
    }

    @Test
    public void aSettingThatTheSettingsScreenChangedIsSeenByTheNextSnapshot() {
        known("g1", "Kitchen");
        assertEquals(0, sources.snapshot().currentIndex());

        stored.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        stored.put(SnapcastRendererOutput.KEY_GROUP, id("g1"));
        assertEquals(1, sources.snapshot().currentIndex());

        stored.put(SnapcastRendererOutput.KEY_OUTPUT, "device");
        assertEquals(0, sources.snapshot().currentIndex());
    }

    @Test
    public void aSourceListThatCanNotBeReadIsThisDeviceAlone() {
        RendererOutputSources broken = new RendererOutputSources(key -> {
            throw new IllegalStateException("prefs");
        }, values -> { }, () -> {
            throw new IllegalStateException("hub");
        }, LABELS, problems::add);

        assertEquals(1, broken.snapshot().entries().size());
        assertEquals(0, broken.snapshot().currentIndex());
    }

    // ---- choosing a source writes the setting of the settings screen

    @Test
    public void choosingAGroupWritesBothPreferencesInOneStep() {
        known("g1", "Kitchen");

        assertTrue(sources.select(sources.snapshot().byIndex(1)));

        assertEquals(1, writes.size());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        expected.put(SnapcastRendererOutput.KEY_GROUP, id("g1"));
        assertEquals(expected, writes.get(0));
        assertTrue(problems.isEmpty());
        assertEquals("the snapshot now shows it", 1, sources.snapshot().currentIndex());
        // the same text the settings screen's list and chooser store (SnapcastRendererOutput reads it back as the group)
        SnapcastRendererOutput.Setting setting = SnapcastRendererOutput.read(stored::get);
        assertEquals(SnapcastRendererOutput.Mode.GROUP, setting.mode());
        assertEquals(id("g1"), setting.receiverId());
        assertFalse(setting.isDamaged());
    }

    @Test
    public void choosingThisDeviceWritesOnlyTheOutputAndKeepsTheLastGroup() {
        known("g1", "Kitchen");
        stored.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        stored.put(SnapcastRendererOutput.KEY_GROUP, id("g1"));

        assertTrue(sources.select(sources.snapshot().byIndex(0)));

        assertEquals(Collections.singletonMap(SnapcastRendererOutput.KEY_OUTPUT, "device"), writes.get(0));
        assertEquals("like the settings screen: the chooser keeps its value", id("g1"), stored.get(SnapcastRendererOutput.KEY_GROUP));
        assertEquals(0, sources.snapshot().currentIndex());
    }

    @Test
    public void choosingTheCurrentSourceChangesNothingAndIsNotAnError() {
        known("g1", "Kitchen");
        stored.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        stored.put(SnapcastRendererOutput.KEY_GROUP, id("g1"));

        assertTrue(sources.select(sources.snapshot().current()));

        assertTrue("no write: the player service would stop a player for nothing", writes.isEmpty());
        assertTrue(problems.isEmpty());
    }

    // ---- a group that vanished, and everything else that fails

    @Test
    public void aGroupThatVanishedAfterTheListWasShownIsRefusedAndTheOldOutputStays() {
        known("g1", "Kitchen");
        known("g2", "Attic");
        Entry shownToTheController = sources.snapshot().byIndex(1); // Attic
        groups.remove(1); // Attic is gone (its server dropped it)

        assertFalse(sources.select(shownToTheController));

        assertTrue("nothing was written", writes.isEmpty());
        assertEquals(Collections.singletonList(RendererOutputSources.Refusal.GROUP_GONE), problems);
        assertEquals("the controller still sees the old source", 0, sources.snapshot().currentIndex());
    }

    @Test
    public void aSourceThatCanNotBeChosenIsRefusedWithoutAWrite() {
        stored.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        stored.put(SnapcastRendererOutput.KEY_GROUP, id("gone"));
        Entry placeholder = sources.snapshot().current();

        assertFalse(sources.select(placeholder));

        assertTrue(writes.isEmpty());
        assertEquals(Collections.singletonList(RendererOutputSources.Refusal.GROUP_GONE), problems);
    }

    @Test
    public void aSourceFromAnOlderListIsCheckedAgainstTheGroupsOfNowNotTheOnesOfTheList() {
        known("g1", "Kitchen");
        Entry old = sources.snapshot().byIndex(1);
        groups.clear();
        known("g9", "Other");

        assertFalse("the list changed under the controller's feet", sources.select(old));

        assertTrue(writes.isEmpty());
    }

    @Test
    public void aWriteThatFailsKeepsTheOldOutputAndTellsTheUser() {
        known("g1", "Kitchen");
        writeFails = true;

        assertFalse(sources.select(sources.snapshot().byIndex(1)));

        assertEquals(Collections.singletonList(RendererOutputSources.Refusal.NOT_SAVED), problems);
        assertEquals(0, sources.snapshot().currentIndex());
    }

    @Test
    public void nothingIsSelectedForNull() {
        assertFalse(sources.select(null));
        assertTrue(writes.isEmpty());
        assertTrue("no message for nothing", problems.isEmpty());
    }

    @Test
    public void aFailureOfTheMessageSinkDoesNotBreakTheRefusal() {
        known("g1", "Kitchen");
        Entry shown = sources.snapshot().byIndex(1);
        groups.clear();
        RendererOutputSources failing = new RendererOutputSources(stored::get, values -> { }, () -> new ArrayList<>(groups), LABELS,
                problem -> {
                    throw new IllegalStateException("toast");
                });

        assertFalse(failing.select(shown));
    }

    @Test
    public void theGroupsOfTheListAreTheSameAsTheOnesTheSettingsChooserOffers() {
        known("g1", "Kitchen");
        assertEquals(Arrays.asList("Playlist", "Kitchen"), names(sources.snapshot()));
    }

    private static List<String> names(SnapcastRendererSources.Snapshot snapshot) {
        List<String> names = new ArrayList<>();
        for (Entry entry : snapshot.entries()) {
            names.add(entry.name());
        }
        return names;
    }
}
