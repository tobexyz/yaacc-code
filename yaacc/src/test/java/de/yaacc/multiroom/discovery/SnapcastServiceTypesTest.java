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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The service types a snapserver announces, as read from {@code server/snapserver.cpp} of the tags (docs/tech.md): up to
 * 0.32 the control port is announced as {@code _snapcast-tcp._tcp} (and {@code _snapcast-jsonrpc._tcp}), from 0.33 as
 * {@code _snapcast-ctrl._tcp}; the stream port is {@code _snapcast._tcp} in every version. The app browsed only the new
 * name, so a server of 0.32 or older was never found (task 11.6).
 */
public class SnapcastServiceTypesTest {

    @Test
    public void bothNamesOfTheControlPortAreBrowsed() {
        List<String> types = SnapcastServiceTypes.browsed();
        assertTrue(types.contains("_snapcast-ctrl._tcp"));
        assertTrue(types.contains("_snapcast-tcp._tcp"));
    }

    @Test
    public void theStreamTypeIsBrowsedToTellAServerThatHidesItsControlPort() {
        assertTrue(SnapcastServiceTypes.browsed().contains("_snapcast._tcp"));
        assertEquals(SnapcastServiceTypes.Role.STREAM_PROBE, SnapcastServiceTypes.roleOf("_snapcast._tcp"));
    }

    @Test
    public void everyBrowsedTypeHasARole() {
        for (String type : SnapcastServiceTypes.browsed()) {
            assertTrue(type, SnapcastServiceTypes.roleOf(type) != null);
        }
    }

    @Test
    public void controlTypesAreTheOnesOfTheControlPort() {
        assertEquals(SnapcastServiceTypes.Role.CONTROL, SnapcastServiceTypes.roleOf("_snapcast-ctrl._tcp"));
        assertEquals(SnapcastServiceTypes.Role.CONTROL, SnapcastServiceTypes.roleOf("_snapcast-tcp._tcp"));
    }

    @Test
    public void theTypeAsTheBrowserReportsItIsRecognised() {
        // NsdManager reports the type with a trailing dot, some releases with the domain
        assertEquals(SnapcastServiceTypes.Role.CONTROL, SnapcastServiceTypes.roleOf("_snapcast-ctrl._tcp."));
        assertEquals(SnapcastServiceTypes.Role.CONTROL, SnapcastServiceTypes.roleOf("_snapcast-tcp._tcp.local."));
        assertEquals(SnapcastServiceTypes.Role.CONTROL, SnapcastServiceTypes.roleOf("_SNAPCAST-CTRL._TCP"));
        assertEquals("_snapcast-ctrl._tcp", SnapcastServiceTypes.canonical("_snapcast-ctrl._tcp."));
    }

    @Test
    public void otherTypesAreNotAccepted() {
        for (String type : Arrays.asList(null, "", ".", "_http._tcp", "_snapcast-ctrl._udp", "_snapcast-ctrl._tcpevil",
                "x_snapcast-ctrl._tcp", "_snapcast-http._tcp", "_snapcast-jsonrpc._tcp", "_snapcast-ctrl._tcp.evil.example.")) {
            assertNull(String.valueOf(type), SnapcastServiceTypes.roleOf(type));
            assertNull(String.valueOf(type), SnapcastServiceTypes.canonical(type));
        }
    }

    @Test
    public void theIdOfAServiceCarriesTypeAndName() {
        String a = SnapcastServiceTypes.serviceId("_snapcast-ctrl._tcp", "Snapcast");
        String b = SnapcastServiceTypes.serviceId("_snapcast-tcp._tcp", "Snapcast");
        assertFalse(a.equals(b)); // one server, two types, the same name: two services
        assertEquals(a, SnapcastServiceTypes.serviceId("_snapcast-ctrl._tcp.", "Snapcast"));
    }

    @Test
    public void controlPortIsNeverDerivedFromTheStreamPort() {
        // the stream service is a hint only: its port is the stream port, not the control port
        assertFalse(SnapcastServiceTypes.isListed(SnapcastServiceTypes.Role.STREAM_PROBE));
        assertTrue(SnapcastServiceTypes.isListed(SnapcastServiceTypes.Role.CONTROL));
    }

    @Test
    public void everyTypeHasAVersionNote() {
        assertEquals("Snapcast 0.33 and newer", SnapcastServiceTypes.versionNote("_snapcast-ctrl._tcp"));
        assertEquals("Snapcast 0.32 and older", SnapcastServiceTypes.versionNote("_snapcast-tcp._tcp"));
        assertEquals("every version, the stream port", SnapcastServiceTypes.versionNote("_snapcast._tcp"));
    }
}
