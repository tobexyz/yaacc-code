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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.UDAServiceId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The synthetic device of a group: its id (parsed from the right), its name (display text of the server, cleaned), and
 * the fact that it has no services, so that the code that looks for services finds none and does not fail.
 */
public class SnapcastGroupDeviceTest {

    private static final SnapcastGroupDevice.Labels LABELS = new SnapcastGroupDevice.Labels("Snapcast group", "offline");
    private static final SnapcastServerKey KEY = SnapcastEndpoint.parse("192.168.1.5:1705").toKey();
    private static final String GROUP_ID = "aaaaaaaa-0000-4000-8000-000000000001";

    private static SnapcastClient client(String id, String name, String host) {
        return new SnapcastClient(id, name, host, true, new SnapcastVolume(50, false), 0);
    }

    private static SnapcastGroup group(String id, String name, SnapcastClient... clients) {
        return new SnapcastGroup(id, name, false, "Default", Arrays.asList(clients));
    }

    private static SnapcastGroupDevice device(SnapcastGroup group, boolean offline) {
        SnapcastGroupDevice device = SnapcastGroupDevice.of(KEY, group, offline, LABELS);
        assertNotNull(device);
        return device;
    }

    // ---- ids

    @Test
    public void theIdHasTheServerKeyAndTheGroupId() {
        assertEquals("snapcast:192.168.1.5:1705:" + GROUP_ID, SnapcastGroupDevice.idOf(KEY, GROUP_ID));
        assertEquals("snapcast:192.168.1.5:1705:" + GROUP_ID,
                device(group(GROUP_ID, "x", client("c", "k", "")), false).getIdentity().getUdn().getIdentifierString());
    }

    @Test
    public void theIdIsParsedFromTheRight() {
        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId("snapcast:192.168.1.5:1705:" + GROUP_ID);

        assertNotNull(id);
        assertEquals(KEY, id.getServerKey());
        assertEquals(GROUP_ID, id.getGroupId());
    }

    @Test
    public void anIpv6KeyWithManyColonsRoundTrips() {
        SnapcastServerKey v6 = SnapcastEndpoint.parse("[fd00::5]:1706").toKey();

        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(SnapcastGroupDevice.idOf(v6, GROUP_ID));

        assertNotNull(id);
        assertEquals(v6, id.getServerKey());
        assertEquals(GROUP_ID, id.getGroupId());
    }

    @Test
    public void aHostNameKeyRoundTrips() {
        SnapcastServerKey named = SnapcastEndpoint.parse("snap.local").toKey();

        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(SnapcastGroupDevice.idOf(named, "g-1"));

        assertEquals(named, id.getServerKey());
        assertEquals("g-1", id.getGroupId());
    }

    @Test
    public void textThatIsNotAGroupIdIsNotParsed() {
        assertNull(SnapcastGroupDevice.parseId(null));
        assertNull(SnapcastGroupDevice.parseId(""));
        assertNull(SnapcastGroupDevice.parseId("LOCAL_UID"));
        assertNull(SnapcastGroupDevice.parseId("uuid:1234"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:192.168.1.5:1705"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:192.168.1.5:1705:"));
        assertNull("not the canonical key form", SnapcastGroupDevice.parseId("snapcast:192.168.001.5:1705:g"));
        assertNull("not the canonical key form (default port)", SnapcastGroupDevice.parseId("snapcast:snap.local:g"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:192.168.1.5:1705:g:h"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:192.168.1.5:1705:g h"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:192.168.1.5:1705:g\n"));
        assertNull(SnapcastGroupDevice.parseId("SNAPCAST:192.168.1.5:1705:g"));
        assertNull(SnapcastGroupDevice.parseId("snapcast:" + repeat("a", 400) + ":1705:g"));
    }

    @Test
    public void isSnapcastIdRecognizesThePrefixOnly() {
        assertTrue(SnapcastGroupDevice.isSnapcastId("snapcast:anything"));
        assertFalse(SnapcastGroupDevice.isSnapcastId("LOCAL_UID"));
        assertFalse(SnapcastGroupDevice.isSnapcastId(null));
    }

    @Test
    public void aGroupIdThatCouldBreakTheIdIsNotMadeIntoADevice() {
        for (String bad : new String[]{"a:b", "a b", "a\nb", "", repeat("x", 65), "grün", "../x", "a/b"}) {
            assertNull("refused: " + bad, SnapcastGroupDevice.of(KEY, group(bad, "n", client("c", "k", "")), false, LABELS));
        }
        assertNotNull(SnapcastGroupDevice.of(KEY, group(repeat("x", 64), "n", client("c", "k", "")), false, LABELS));
        assertNotNull(SnapcastGroupDevice.of(KEY, group("A_b-9", "n", client("c", "k", "")), false, LABELS));
    }

    // ---- names

    @Test
    public void theFriendlyNameIsTheGroupNameWithTheProductName() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "Ground floor", client("c", "Kitchen", "")), false);

        assertEquals("Ground floor (Snapcast)", device.getDetails().getFriendlyName());
        assertEquals("Ground floor (Snapcast)", device.getDisplayString());
    }

    @Test
    public void withoutAGroupNameTheFirstMembersNameIsUsed() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "", client("c1", "Bedroom", "h1"), client("c2", "Study", "h2")), false);

        assertEquals("Bedroom (Snapcast)", device.getDetails().getFriendlyName());
    }

    @Test
    public void aMemberWithoutANameUsesItsHostNameAndNeverItsId() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "", client("02:00:00:00:00:09", "", "TEST-HOST"), client("c2", "Study", "")), false);

        assertEquals("TEST-HOST (Snapcast)", device.getDetails().getFriendlyName());
    }

    @Test
    public void withNoNameAtAllTheGenericLabelIsUsed() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "", client("02:00:00:00:00:09", "", "")), false);

        assertEquals("Snapcast group", device.getDetails().getFriendlyName());
        assertFalse(device.getDetails().getFriendlyName().contains("02:00"));
    }

    @Test
    public void anOfflineServerIsMarkedInTheName() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "Ground floor", client("c", "Kitchen", "")), true);

        assertEquals("Ground floor (Snapcast, offline)", device.getDetails().getFriendlyName());
        assertTrue(device.isOffline());
    }

    @Test
    public void namesFromTheServerAreCleanedAndCut() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "Living\nroom‮\u001b[31m" + repeat("y", 200), client("c", "k", "")), false);

        String name = device.getDetails().getFriendlyName();
        assertFalse(name.contains("\n"));
        assertFalse(name.contains("‮"));
        assertFalse(name.contains("\u001b"));
        assertTrue(name.endsWith(" (Snapcast)"));
        assertTrue("cut at 64 characters before the suffix", name.length() <= 64 + " (Snapcast)".length());
    }

    @Test
    public void theNameNeverChangesTheIdentity() {
        SnapcastGroupDevice a = device(group(GROUP_ID, "One", client("c", "k", "")), false);
        SnapcastGroupDevice b = device(group(GROUP_ID, "Two", client("c", "k", "")), true);

        assertEquals("the same group is the same receiver, whatever it is called", a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, device(group("other-group", "One", client("c", "k", "")), false));
    }

    // ---- no services

    @Test
    public void theDeviceHasNoServicesAndIsNotInTheRegistry() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "G", client("c", "k", "")), false);

        assertNull(device.getServices());
        assertFalse(device.hasServices());
        assertNull(device.getRoot());
        assertNull(device.getEmbeddedDevices());
        assertNull(device.findService(new UDAServiceId("AVTransport")));
        assertNull(device.findService(new UDAServiceId("RenderingControl")));
        assertEquals(0, device.findServiceTypes().length);
    }

    @Test
    public void itKnowsItsServerAndGroup() {
        SnapcastGroupDevice device = device(group(GROUP_ID, "G", client("c", "k", "")), false);

        assertEquals(KEY, device.getServerKey());
        assertEquals(GROUP_ID, device.getGroupId());
        assertEquals("Default", device.getStreamId());
        assertEquals(1, device.getClientCount());
        assertFalse(device.isOffline());
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void itIsADeviceForTheCodeThatListsReceivers() {
        Device<?, ?, ?> device = device(group(GROUP_ID, "G", client("c", "k", "")), false);
        List<Device<?, ?, ?>> list = Collections.<Device<?, ?, ?>>singletonList(device);

        assertEquals(1, list.size());
        assertNotNull(device.getIdentity().getUdn());
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }
}
