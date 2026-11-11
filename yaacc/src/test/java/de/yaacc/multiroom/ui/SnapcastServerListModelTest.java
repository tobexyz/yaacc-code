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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.SnapcastSelectedServers;
import de.yaacc.multiroom.SnapcastServerAdmin;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The server list of the settings: strict validation of what the user types (a host name or an address with an optional
 * port, no credentials, no URL), at most 16 servers, a duplicate is merged, the list shows the typed-in and the
 * discovered servers once each with cleaned names, and "remove" removes. Plain JVM with in-memory storage and the real
 * directory.
 */
public class SnapcastServerListModelTest {

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

    private final AtomicLong now = new AtomicLong(1_000L);
    private final MemoryStorage manualStorage = new MemoryStorage();
    private SnapcastDirectory directory;
    private SnapcastManualServers manual;
    private SnapcastServerListModel model;

    @Before
    public void setUp() {
        directory = new SnapcastDirectory((MonotonicClock) now::get, 30_000L);
        manual = new SnapcastManualServers(manualStorage);
        SnapcastSelectedServers selected = new SnapcastSelectedServers(new MemoryStorage(), directory);
        SnapcastServerAdmin admin = new SnapcastServerAdmin(manual, directory, selected, new SnapcastServerAdmin.SelectionStore() {
            @Override
            public Set<String> read() {
                return new HashSet<>();
            }

            @Override
            public void write(Set<String> receiverIds) {
            }
        });
        model = new SnapcastServerListModel(manual, directory, admin);
    }

    private static SnapcastServerKey key(String text) {
        return SnapcastEndpoint.parse(text).toKey();
    }

    // ---- validation

    @Test
    public void anAddressWithOrWithoutAPortIsAccepted() {
        assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("192.168.1.5"));
        assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("192.168.1.6:1800"));
        assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("snap.local"));
        assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("[fe80::1]:1705"));

        assertEquals(4, manual.list().size());
    }

    @Test
    public void whiteSpaceAroundTheInputIsIgnored() {
        assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("  192.168.1.5\n"));
        assertEquals("192.168.1.5:1705", manual.list().get(0).toString());
    }

    @Test
    public void nothingTypedIsRefused() {
        assertEquals(SnapcastServerListModel.Outcome.EMPTY, model.add(""));
        assertEquals(SnapcastServerListModel.Outcome.EMPTY, model.add("   "));
        assertEquals(SnapcastServerListModel.Outcome.EMPTY, model.add(null));
    }

    @Test
    public void credentialsAreRefusedWithTheirOwnMessageAndNothingIsStored() {
        assertEquals(SnapcastServerListModel.Outcome.HAS_CREDENTIALS, model.add("admin:secret@192.168.1.5"));
        assertEquals(SnapcastServerListModel.Outcome.HAS_CREDENTIALS, model.add("user@snap.local:1705"));
        assertEquals(SnapcastServerListModel.Outcome.HAS_CREDENTIALS, model.add("http://user:pw@host/"));

        assertTrue(manual.list().isEmpty());
        assertNull(manualStorage.text);
    }

    @Test
    public void aUrlAndOtherMalformedTextIsInvalid() {
        for (String bad : new String[]{"http://192.168.1.5", "192.168.1.5/path", "192.168.1.5:0", "192.168.1.5:70000",
                "192.168.1.5:abc", "not a host", "-bad.example", "010.0.0.1", "0.0.0.0", "224.0.0.1", "a..b", "host:",
                ":1705", "192.168.1.5:1705:1", "snap\u202E.local"}) {
            assertEquals(bad, SnapcastServerListModel.Outcome.INVALID, model.add(bad));
        }
        assertTrue(manual.list().isEmpty());
    }

    @Test
    public void aDuplicateIsMergedNotAddedTwice() {
        assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("192.168.1.5"));

        assertEquals(SnapcastServerListModel.Outcome.ALREADY_LISTED, model.add("192.168.1.5:1705"));
        assertEquals(SnapcastServerListModel.Outcome.ALREADY_LISTED, model.add(" 192.168.1.5 "));

        assertEquals(1, manual.list().size());
    }

    @Test
    public void atMostSixteenServersCanBeAdded() {
        for (int i = 1; i <= 16; i++) {
            assertEquals(SnapcastServerListModel.Outcome.ADDED, model.add("10.0.0." + i));
        }
        assertFalse(model.canAdd());

        assertEquals(SnapcastServerListModel.Outcome.FULL, model.add("10.0.0.17"));
        assertEquals(16, manual.list().size());
    }

    @Test
    public void aDuplicateIsReportedAsSuchEvenWhenTheListIsFull() {
        for (int i = 1; i <= 16; i++) {
            model.add("10.0.0." + i);
        }

        assertEquals(SnapcastServerListModel.Outcome.ALREADY_LISTED, model.add("10.0.0.3"));
    }

    @Test
    public void everyOutcomeButAddedAndAlreadyListedIsAProblem() {
        assertFalse(SnapcastServerListModel.Outcome.ADDED.isProblem());
        assertFalse(SnapcastServerListModel.Outcome.ALREADY_LISTED.isProblem());
        assertTrue(SnapcastServerListModel.Outcome.EMPTY.isProblem());
        assertTrue(SnapcastServerListModel.Outcome.INVALID.isProblem());
        assertTrue(SnapcastServerListModel.Outcome.HAS_CREDENTIALS.isProblem());
        assertTrue(SnapcastServerListModel.Outcome.FULL.isProblem());
    }

    @Test
    public void anAddedServerIsInTheDirectoryAtOnce() {
        model.add("192.168.1.5");

        assertTrue(directory.find(key("192.168.1.5")).isManual());
    }

    // ---- the rows

    @Test
    public void aTypedInServerIsARemovableRow() {
        model.add("192.168.1.5");

        List<SnapcastServerListModel.Row> rows = model.rows();

        assertEquals(1, rows.size());
        assertEquals("192.168.1.5:1705", rows.get(0).getEndpointText());
        assertEquals(SnapcastServerListModel.Kind.MANUAL, rows.get(0).getKind());
        assertTrue(rows.get(0).isRemovable());
        assertEquals(key("192.168.1.5"), rows.get(0).getKey());
    }

    @Test
    public void aDiscoveredServerIsOneRowWithItsCleanedNameAndCanNotBeRemoved() throws Exception {
        directory.serviceFound("Living\u202E room\u200B", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 7}), 1705);

        List<SnapcastServerListModel.Row> rows = model.rows();

        assertEquals(1, rows.size());
        assertEquals(SnapcastServerListModel.Kind.DISCOVERED, rows.get(0).getKind());
        assertEquals("Living? room?", rows.get(0).getName());
        assertFalse(rows.get(0).isRemovable());
    }

    @Test
    public void aServerFoundAndTypedInIsOneManualRowThatSaysItIsAnnounced() throws Exception {
        directory.serviceFound("Snapcast", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 7}), 1705);
        model.add("192.168.1.7");

        List<SnapcastServerListModel.Row> rows = model.rows();

        assertEquals(1, rows.size());
        assertEquals(SnapcastServerListModel.Kind.MANUAL, rows.get(0).getKind());
        assertTrue(rows.get(0).isAnnounced());
        assertTrue(rows.get(0).isRemovable());
    }

    @Test
    public void aRememberedServerIsARemovableRow() {
        directory.setRememberedServers(Collections.singletonList(SnapcastEndpoint.parse("192.168.1.9:1705")));

        List<SnapcastServerListModel.Row> rows = model.rows();

        assertEquals(1, rows.size());
        assertEquals(SnapcastServerListModel.Kind.REMEMBERED, rows.get(0).getKind());
        assertTrue(rows.get(0).isRemovable());
    }

    @Test
    public void theTypedInServersComeFirstThenTheOthersEachByEndpoint() throws Exception {
        directory.serviceFound("B", InetAddress.getByAddress(new byte[]{10, 0, 0, 9}), 1705);
        directory.serviceFound("A", InetAddress.getByAddress(new byte[]{10, 0, 0, 3}), 1705);
        model.add("10.0.0.50");
        model.add("10.0.0.20");

        List<SnapcastServerListModel.Row> rows = model.rows();

        assertEquals("10.0.0.20:1705", rows.get(0).getEndpointText());
        assertEquals("10.0.0.50:1705", rows.get(1).getEndpointText());
        assertEquals("10.0.0.3:1705", rows.get(2).getEndpointText());
        assertEquals("10.0.0.9:1705", rows.get(3).getEndpointText());
    }

    @Test
    public void aDiscoveredNameIsNotShownEvenWhenItWasStoredUncleaned() {
        assertEquals("Kitchen?", SnapcastServerListModel.cleanName("Kitchen\u200B"));
        assertEquals("", SnapcastServerListModel.cleanName(null));
    }

    // ---- remove

    @Test
    public void removingRemovesTheServerFromTheListAndTheRows() {
        model.add("192.168.1.5");
        model.add("192.168.1.6");

        model.remove(key("192.168.1.5"));

        assertEquals(1, model.rows().size());
        assertEquals(1, manual.list().size());
        assertNull(directory.find(key("192.168.1.5")));
    }
}
