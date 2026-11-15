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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * What the renderer's group chooser offers (task 9.1): the servers of the server list, then the groups of the chosen
 * server. Labels are display text from the network, cleaned, and never stand in front of the address. Plain JVM.
 */
public class SnapcastRendererGroupChoicesTest {

    private static SnapcastServerListModel.Row row(String endpoint, String name, SnapcastServerListModel.Kind kind) {
        SnapcastEndpoint parsed = SnapcastEndpoint.parse(endpoint);
        assertNotNull(endpoint, parsed);
        return new SnapcastServerListModel.Row(parsed.toKey(), parsed.toString(), name, kind, false);
    }

    private static SnapcastGroupDevice device(String endpoint, String groupId, String name, int clients) {
        List<SnapcastClient> members = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            members.add(new SnapcastClient("c" + i, "client", "", true, new SnapcastVolume(50, false), 0));
        }
        SnapcastGroup group = new SnapcastGroup(groupId, name, false, "Default", members);
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse(endpoint).toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    @Test
    public void nothingKnownMeansNoChoices() {
        assertTrue(SnapcastRendererGroupChoices.servers(null).isEmpty());
        assertTrue(SnapcastRendererGroupChoices.servers(Collections.<SnapcastServerListModel.Row>emptyList()).isEmpty());
        assertTrue(SnapcastRendererGroupChoices.groups(null).isEmpty());
        assertTrue(SnapcastRendererGroupChoices.groups(Collections.<SnapcastGroupDevice>emptyList()).isEmpty());
    }

    @Test
    public void everyServerIsOfferedOnceWithItsControlEndpointAsTheKey() {
        List<SnapcastRendererGroupChoices.Server> servers = SnapcastRendererGroupChoices.servers(Arrays.asList(
                row("10.0.0.7:1705", "", SnapcastServerListModel.Kind.MANUAL),
                row("192.168.1.5:1705", "Living room", SnapcastServerListModel.Kind.DISCOVERED),
                row("192.168.1.5:1705", "again", SnapcastServerListModel.Kind.REMEMBERED),
                row("10.0.0.9:2000", "", SnapcastServerListModel.Kind.REMEMBERED)));

        assertEquals("the order of the list is kept (typed first, as the model sorts it)", 3, servers.size());
        assertEquals("the control port is part of the key: the groups live there", "10.0.0.7:1705", servers.get(0).key().asString());
        assertEquals(SnapcastServerListModel.Kind.MANUAL, servers.get(0).kind());
        assertEquals("192.168.1.5:1705", servers.get(1).key().asString());
        assertEquals("10.0.0.9:2000", servers.get(2).key().asString());
    }

    @Test
    public void theLabelStartsWithTheAddressSoANameCanNotPoseAsIt() {
        List<SnapcastRendererGroupChoices.Server> servers = SnapcastRendererGroupChoices.servers(Arrays.asList(
                row("192.168.1.5:1705", "10.0.0.1:1705", SnapcastServerListModel.Kind.DISCOVERED),
                row("10.0.0.7:1705", "", SnapcastServerListModel.Kind.MANUAL)));

        assertEquals("192.168.1.5:1705 (10.0.0.1:1705)", servers.get(0).label());
        assertEquals("a typed server has no name: the address alone", "10.0.0.7:1705", servers.get(1).label());
    }

    @Test
    public void aNameOfOnlyInvisibleOrBlankCharactersIsNoName() {
        List<SnapcastRendererGroupChoices.Server> servers = SnapcastRendererGroupChoices.servers(Collections.singletonList(
                row("192.168.1.5:1705", "   ", SnapcastServerListModel.Kind.DISCOVERED)));

        assertEquals("192.168.1.5:1705", servers.get(0).label());
    }

    @Test
    public void everyGroupHasTheCanonicalReceiverIdOfItsServerAndGroup() {
        List<SnapcastRendererGroupChoices.Group> groups = SnapcastRendererGroupChoices.groups(Arrays.asList(
                device("192.168.1.5:1705", "g-1", "Kitchen", 2), device("192.168.1.5:1705", "g-2", "", 0 + 1)));

        assertEquals(2, groups.size());
        assertEquals("snapcast:192.168.1.5:1705:g-1", groups.get(0).receiverId());
        assertEquals("snapcast:192.168.1.5:1705:g-2", groups.get(1).receiverId());
        for (SnapcastRendererGroupChoices.Group group : groups) {
            assertTrue(SnapcastRendererOutput.isCanonicalGroupId(group.receiverId()));
        }
        assertEquals(2, groups.get(0).clients());
        assertEquals("Kitchen (Snapcast)", groups.get(0).name());
    }

    @Test
    public void aGroupNameFromTheNetworkIsShownCleaned() {
        List<SnapcastRendererGroupChoices.Group> groups = SnapcastRendererGroupChoices.groups(Collections.singletonList(
                device("192.168.1.5:1705", "g-1", "Kitchen\nHacked‮", 1)));

        assertFalse(groups.get(0).name().contains("\n"));
        assertFalse(groups.get(0).name().contains("‮"));
        assertFalse("the id is never in the shown text", groups.get(0).name().contains("g-1"));
    }
}
