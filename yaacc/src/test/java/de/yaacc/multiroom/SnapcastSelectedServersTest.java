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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The servers that hold a selected Snapcast group are remembered across a restart (plain {@code host:port} lines, no
 * credentials, strict on load, at most 16), and forgotten again when no selected group refers to them. A manual
 * server is not stored here (its own list does that). Plain JVM, in-memory storage, a clock the test moves.
 */
public class SnapcastSelectedServersTest {

    private static final long GRACE = 30_000L;

    private static final class MemoryStorage implements SnapcastManualServers.Storage {
        String text;
        int writes;

        @Override
        public String read() {
            return text;
        }

        @Override
        public void write(String value) {
            text = value;
            writes++;
        }
    }

    private final AtomicLong now = new AtomicLong(1_000L);
    private final MonotonicClock clock = now::get;
    private final MemoryStorage storage = new MemoryStorage();
    private SnapcastDirectory directory;
    private SnapcastSelectedServers selected;

    @Before
    public void setUp() {
        directory = new SnapcastDirectory(clock, GRACE);
        selected = new SnapcastSelectedServers(storage, directory);
    }

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static SnapcastServerKey key(String text) {
        return SnapcastEndpoint.parse(text).toKey();
    }

    private static String id(String server, String group) {
        return SnapcastGroupDevice.idOf(key(server), group);
    }

    @Test
    public void theServerOfASelectedGroupIsStoredAsAPlainEndpoint() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);

        selected.selectionChanged(Arrays.asList(id("192.168.1.5:1705", "g-a"), "uuid:123"));

        assertEquals("192.168.1.5:1705", storage.text);
    }

    @Test
    public void aServerWithoutASelectedGroupIsNotStored() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        directory.serviceFound("Other", ip(192, 168, 1, 6), 1705);

        selected.selectionChanged(Collections.singletonList(id("192.168.1.6:1705", "g-a")));

        assertEquals("192.168.1.6:1705", storage.text);
    }

    @Test
    public void aGroupOfAServerThatIsNotListedStoresNothing() {
        selected.selectionChanged(Collections.singletonList(id("192.168.1.9:1705", "g-a")));

        assertTrue(selected.list().isEmpty());
    }

    @Test
    public void aManualServerIsNotStoredHere() {
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("192.168.1.5")));

        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));

        assertTrue(selected.list().isEmpty());
    }

    @Test
    public void theServerIsForgottenWhenNoSelectedGroupRefersToIt() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));
        assertEquals("192.168.1.5:1705", storage.text);

        selected.selectionChanged(Collections.singletonList("LOCAL_UID"));

        assertEquals("", storage.text);
        assertTrue(selected.list().isEmpty());
    }

    @Test
    public void nothingIsWrittenWhenNothingChanged() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));
        int writes = storage.writes;

        selected.selectionChanged(Arrays.asList(id("192.168.1.5:1705", "g-a"), id("192.168.1.5:1705", "g-b")));
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));

        assertEquals(writes, storage.writes);
    }

    @Test
    public void aNullSelectionForgetsEverything() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));

        selected.selectionChanged(null);

        assertTrue(selected.list().isEmpty());
    }

    @Test
    public void theServerIsListedAgainAfterARestart() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));

        SnapcastDirectory afterRestart = new SnapcastDirectory(clock, GRACE);
        new SnapcastSelectedServers(storage, afterRestart).restore();

        List<SnapcastDirectory.Entry> servers = afterRestart.servers();
        assertEquals(1, servers.size());
        assertEquals(key("192.168.1.5:1705"), servers.get(0).getKey());
        assertTrue(servers.get(0).isRemembered());
        assertNotNull("an address to connect to, no look-up", servers.get(0).getEndpoint().getAddress());
    }

    @Test
    public void aRememberedServerThatIsNotSeenAgainIsForgottenWithItsSelection() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));
        SnapcastDirectory afterRestart = new SnapcastDirectory(clock, GRACE);
        SnapcastSelectedServers restarted = new SnapcastSelectedServers(storage, afterRestart);
        restarted.restore();
        afterRestart.discoveryRestarted();

        now.addAndGet(GRACE);
        assertTrue(afterRestart.servers().isEmpty());
        restarted.selectionChanged(Collections.singletonList("LOCAL_UID")); // the selection loses the id, as getReceiverDevices does

        assertTrue(restarted.list().isEmpty());
        assertEquals("", storage.text);
    }

    @Test
    public void whatIsStoredIsReadStrictly() {
        storage.text = String.join("\n",
                "192.168.1.5:1705",
                "user:secret@192.168.1.6:1705",   // credentials
                "tcp://192.168.1.7:1705",         // a scheme
                "snap.local:1705",                 // a name: only addresses that were seen are remembered
                "8.8.8.8:1705",                    // outside the local ranges
                "192.168.1.8:99999",               // a port out of range
                " 192.168.1.9:1705",               // white space
                "192.168.1.5:1705",                // twice
                "",
                "192.168.1.10");
        selected.restore();

        List<SnapcastEndpoint> list = selected.list();

        assertEquals(Arrays.asList("192.168.1.5:1705", "192.168.1.10:1705"), toText(list));
        assertEquals(2, directory.servers().size());
    }

    @Test
    public void atMostSixteenAreReadFromTheStore() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            lines.add("192.168.1." + i + ":1705");
        }
        storage.text = String.join("\n", lines);

        selected.restore();

        assertEquals(SnapcastDirectory.MAX_SERVERS, selected.list().size());
        assertEquals(SnapcastDirectory.MAX_SERVERS, directory.servers().size());
    }

    @Test
    public void aMissingOrEmptyStoreIsEmpty() {
        selected.restore();
        storage.text = "";
        selected.restore();

        assertTrue(selected.list().isEmpty());
        assertTrue(directory.servers().isEmpty());
    }

    @Test
    public void storedTextHoldsNoCredentialsAndNoNames() throws Exception {
        directory.serviceFound("My Secret Snapcast Name", ip(192, 168, 1, 5), 1705);
        selected.selectionChanged(Collections.singletonList(id("192.168.1.5:1705", "g-a")));

        assertFalse(storage.text.contains("Secret"));
        assertFalse(storage.text.contains("@"));
        assertFalse(storage.text.contains("g-a"));
    }

    private static List<String> toText(List<SnapcastEndpoint> list) {
        List<String> text = new ArrayList<>();
        for (SnapcastEndpoint endpoint : list) {
            text.add(endpoint.toString());
        }
        return text;
    }
}
