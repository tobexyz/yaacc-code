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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * "Remove server" in the settings means removed: the server leaves the manual list, the remembered flag and the stored
 * endpoint go (security review S23), and the stored selection loses the groups of that server. Plain JVM with in-memory
 * storage and the real directory.
 */
public class SnapcastServerAdminTest {

    private static final long GRACE = 30_000L;

    private static final class MemoryStorage implements SnapcastManualServers.Storage {
        String text;

        @Override
        public String read() {
            return text;
        }

        @Override
        public void write(String value) {
            text = value;
        }
    }

    private static final class MemorySelection implements SnapcastServerAdmin.SelectionStore {
        Set<String> ids = new HashSet<>();
        int writes;

        @Override
        public Set<String> read() {
            return new HashSet<>(ids);
        }

        @Override
        public void write(Set<String> value) {
            ids = new HashSet<>(value);
            writes++;
        }
    }

    private final AtomicLong now = new AtomicLong(1_000L);
    private final MemoryStorage manualStorage = new MemoryStorage();
    private final MemoryStorage selectedStorage = new MemoryStorage();
    private final MemorySelection selection = new MemorySelection();
    private SnapcastDirectory directory;
    private SnapcastManualServers manual;
    private SnapcastSelectedServers selected;
    private SnapcastServerAdmin admin;

    @Before
    public void setUp() {
        directory = new SnapcastDirectory((MonotonicClock) now::get, GRACE);
        manual = new SnapcastManualServers(manualStorage);
        selected = new SnapcastSelectedServers(selectedStorage, directory);
        admin = new SnapcastServerAdmin(manual, directory, selected, selection);
    }

    private static SnapcastServerKey key(String text) {
        return SnapcastEndpoint.parse(text).toKey();
    }

    private static String id(String server, String group) {
        return SnapcastGroupDevice.idOf(key(server), group);
    }

    private void addManual(String text) {
        assertEquals(SnapcastManualServers.AddResult.ADDED, manual.add(text));
        directory.setManualServers(manual.list());
    }

    @Test
    public void aManualServerLeavesTheListAndTheDirectory() {
        addManual("10.0.0.2");
        addManual("10.0.0.3");

        SnapcastServerAdmin.Result result = admin.remove(key("10.0.0.2"));

        assertTrue(result.wasManual());
        assertEquals(1, manual.list().size());
        assertEquals(1, directory.servers().size());
        assertNull(directory.find(key("10.0.0.2")));
    }

    @Test
    public void theStoredSelectionLosesTheGroupsOfThatServerOnly() {
        addManual("10.0.0.2");
        addManual("10.0.0.3");
        selection.ids.addAll(Arrays.asList(id("10.0.0.2", "g-a"), id("10.0.0.2", "g-b"), id("10.0.0.3", "g-c"),
                "uuid:local", "LOCAL"));

        SnapcastServerAdmin.Result result = admin.remove(key("10.0.0.2"));

        assertEquals(2, result.getSelectionRemoved());
        assertEquals(new HashSet<>(Arrays.asList(id("10.0.0.3", "g-c"), "uuid:local", "LOCAL")), selection.ids);
    }

    @Test
    public void theSelectionIsNotWrittenWhenNothingRefersToTheServer() {
        addManual("10.0.0.2");
        selection.ids.add(id("10.0.0.3", "g-c"));

        SnapcastServerAdmin.Result result = admin.remove(key("10.0.0.2"));

        assertEquals(0, result.getSelectionRemoved());
        assertEquals(0, selection.writes);
    }

    @Test
    public void theRememberedFlagAndTheStoredEndpointGoToo() throws Exception {
        // the server was discovered and a group of it selected: it is remembered; then it was typed in and now removed
        directory.serviceFound("Snapcast", InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 1705);
        selection.ids.add(id("10.0.0.2:1705", "g-a"));
        selected.selectionChanged(selection.ids);
        assertEquals("10.0.0.2:1705", selectedStorage.text);
        directory.serviceLost("Snapcast");
        addManual("10.0.0.2");
        assertTrue(directory.find(key("10.0.0.2")).isRemembered());

        admin.remove(key("10.0.0.2"));

        assertEquals("the stored endpoint is gone", "", selectedStorage.text);
        assertNull("the server is gone from the directory", directory.find(key("10.0.0.2")));
        assertTrue(selection.ids.isEmpty());
    }

    @Test
    public void aServerThatIsStillAnnouncedStaysAsADiscoveredOneButNotAsRemembered() throws Exception {
        addManual("10.0.0.2");
        directory.serviceFound("Snapcast", InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 1705);

        admin.remove(key("10.0.0.2"));

        SnapcastDirectory.Entry entry = directory.find(key("10.0.0.2"));
        assertFalse(entry.isManual());
        assertFalse(entry.isRemembered());
        assertTrue(entry.isDiscovered());
    }

    @Test
    public void aRememberedServerThatIsNotManualCanBeRemovedToo() throws Exception {
        directory.setRememberedServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2:1705")));
        selection.ids.add(id("10.0.0.2:1705", "g-a"));
        selectedStorage.text = "10.0.0.2:1705";

        SnapcastServerAdmin.Result result = admin.remove(key("10.0.0.2:1705"));

        assertFalse(result.wasManual());
        assertNull(directory.find(key("10.0.0.2:1705")));
        assertEquals("", selectedStorage.text);
        assertTrue(selection.ids.isEmpty());
    }

    @Test
    public void removingAnUnknownServerChangesNothing() {
        addManual("10.0.0.3");
        selection.ids.add("LOCAL");

        SnapcastServerAdmin.Result result = admin.remove(key("10.0.0.9"));

        assertFalse(result.wasManual());
        assertEquals(1, manual.list().size());
        assertEquals(Collections.singleton("LOCAL"), selection.ids);
    }

    @Test
    public void aNullKeyChangesNothing() {
        addManual("10.0.0.3");

        admin.remove(null);

        assertEquals(1, manual.list().size());
    }

    @Test
    public void aSelectionStoreThatFailsDoesNotStopTheRemovalAndDoesNotForgetOtherServers() {
        addManual("10.0.0.2");
        directory.setRememberedServers(Arrays.asList(SnapcastEndpoint.parse("10.0.0.2:1705"),
                SnapcastEndpoint.parse("10.0.0.7:1705")));
        selectedStorage.text = "10.0.0.2:1705\n10.0.0.7:1705";
        SnapcastServerAdmin broken = new SnapcastServerAdmin(manual, directory, selected, new SnapcastServerAdmin.SelectionStore() {
            @Override
            public Set<String> read() {
                throw new IllegalStateException("no preferences");
            }

            @Override
            public void write(Set<String> ids) {
                throw new IllegalStateException("no preferences");
            }
        });

        broken.remove(key("10.0.0.2"));

        assertTrue(manual.list().isEmpty());
        assertNull(directory.find(key("10.0.0.2")));
        assertEquals("10.0.0.7:1705", selectedStorage.text);
        assertTrue("a server that a selected group still refers to stays remembered",
                directory.find(key("10.0.0.7:1705")).isRemembered());
    }

    @Test
    public void withoutServerKeepsEverythingThatIsNotAGroupOfThatServer() {
        Set<String> ids = new HashSet<>(Arrays.asList(id("10.0.0.2", "g-a"), id("10.0.0.20", "g-a"), "snapcast:garbage",
                "uuid:1234", "x"));

        Set<String> kept = SnapcastServerAdmin.withoutServer(ids, key("10.0.0.2"));

        assertFalse(kept.contains(id("10.0.0.2", "g-a")));
        assertTrue("another server with the same prefix", kept.contains(id("10.0.0.20", "g-a")));
        assertTrue(kept.contains("snapcast:garbage"));
        assertTrue(kept.contains("uuid:1234"));
        assertEquals(4, kept.size());
    }
}
