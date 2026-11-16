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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** The manual server list: validated on the way in and on the way out, persisted as plain text, no credentials. */
public class SnapcastManualServersTest {

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

    @Test
    public void aNewListIsEmpty() {
        assertTrue(new SnapcastManualServers(new MemoryStorage()).list().isEmpty());
    }

    @Test
    public void serversSurviveARestart() {
        MemoryStorage storage = new MemoryStorage();
        SnapcastManualServers first = new SnapcastManualServers(storage);
        assertEquals(SnapcastManualServers.AddResult.ADDED, first.add("snap.local"));
        assertEquals(SnapcastManualServers.AddResult.ADDED, first.add("192.168.1.5:1999"));

        List<SnapcastEndpoint> afterRestart = new SnapcastManualServers(storage).list();

        assertEquals(2, afterRestart.size());
        assertEquals("snap.local:1705", afterRestart.get(0).toString());
        assertEquals("192.168.1.5:1999", afterRestart.get(1).toString());
    }

    @Test
    public void anInvalidEntryIsRefusedAndNothingIsWritten() {
        MemoryStorage storage = new MemoryStorage();
        SnapcastManualServers servers = new SnapcastManualServers(storage);

        assertEquals(SnapcastManualServers.AddResult.INVALID, servers.add("tcp://snap.local"));
        assertEquals(SnapcastManualServers.AddResult.INVALID, servers.add("user:secret@snap.local"));
        assertEquals(SnapcastManualServers.AddResult.INVALID, servers.add("snap.local:99999"));
        assertEquals(SnapcastManualServers.AddResult.INVALID, servers.add(" snap.local"));
        assertEquals(SnapcastManualServers.AddResult.INVALID, servers.add(null));

        assertEquals(0, storage.writes);
    }

    @Test
    public void theSameServerWrittenTwoWaysIsADuplicate() {
        SnapcastManualServers servers = new SnapcastManualServers(new MemoryStorage());
        assertEquals(SnapcastManualServers.AddResult.ADDED, servers.add("Snap.Local"));
        assertEquals(SnapcastManualServers.AddResult.DUPLICATE, servers.add("snap.local:1705"));
        assertEquals(1, servers.list().size());
    }

    @Test
    public void theListHoldsAtMostSixteenServers() {
        SnapcastManualServers servers = new SnapcastManualServers(new MemoryStorage());
        for (int i = 1; i <= 16; i++) {
            assertEquals(SnapcastManualServers.AddResult.ADDED, servers.add("10.0.0." + i));
        }
        assertEquals(SnapcastManualServers.AddResult.FULL, servers.add("10.0.0.17"));
        assertEquals(16, servers.list().size());
    }

    @Test
    public void removingAServerKeepsTheOthers() {
        SnapcastManualServers servers = new SnapcastManualServers(new MemoryStorage());
        servers.add("10.0.0.1");
        servers.add("10.0.0.2");

        assertTrue(servers.remove(SnapcastEndpoint.parse("10.0.0.1").toKey()));
        assertFalse(servers.remove(SnapcastEndpoint.parse("10.0.0.1").toKey()));

        assertEquals(1, servers.list().size());
        assertEquals("10.0.0.2:1705", servers.list().get(0).toString());
    }

    @Test
    public void aDamagedStoreOnlyYieldsTheValidLines() {
        MemoryStorage storage = new MemoryStorage();
        storage.text = "10.0.0.1\n\nhttp://evil/\nuser:pw@host\n10.0.0.1:1705\n  \n[::1]:1705\nsnap.local\u0000\n";

        List<SnapcastEndpoint> list = new SnapcastManualServers(storage).list();

        assertEquals(2, list.size());
        assertEquals("10.0.0.1:1705", list.get(0).toString());
        assertEquals("[0:0:0:0:0:0:0:1]:1705", list.get(1).toString());
    }

    @Test
    public void aHugeStoreIsCutAtTheCap() {
        MemoryStorage storage = new MemoryStorage();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            text.append("10.0.").append(i / 250).append('.').append(i % 250 + 1).append('\n');
        }
        storage.text = text.toString();

        assertEquals(16, new SnapcastManualServers(storage).list().size());
    }

    @Test
    public void whatIsStoredIsOnlyEndpoints() {
        MemoryStorage storage = new MemoryStorage();
        SnapcastManualServers servers = new SnapcastManualServers(storage);
        servers.add("snap.local:1999");
        servers.add("10.0.0.2");

        assertEquals("snap.local:1999\n10.0.0.2:1705", storage.text);
    }
}
