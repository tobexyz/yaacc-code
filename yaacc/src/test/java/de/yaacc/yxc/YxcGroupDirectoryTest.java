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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Who is in whose group, from the captured distribution answers (server a at .50 lists the client at .1). */
public class YxcGroupDirectoryTest {

    private static YxcDeviceState state(String distFile) throws Exception {
        YxcClient client = new YxcClient(new RecordingTransport()
                .answer("dist/getDistributionInfo", YxcFixtures.read("captured/" + distFile + ".json")));
        return YxcDeviceState.unknown().withDistribution(client.getDistributionInfo());
    }

    private static YxcDeviceSnapshot snap(String udn, String host, YxcDeviceState state) {
        return new YxcDeviceSnapshot(udn, host, state);
    }

    private YxcGroupDirectory directory(YxcDeviceSnapshot... devices) {
        return new YxcGroupDirectory(new ArrayList<>(Arrays.asList(devices)));
    }

    @Test
    public void aClientFindsItsServerByTheGroupId() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.50", state("a_distInfo_server_pair")),
                snap("client", "192.0.2.1", state("c_distInfo_client")),
                snap("other", "192.0.2.77", state("b_distInfo_none")));

        assertEquals("server", directory.serverOf("client"));
        assertNull(directory.serverOf("server"));
        assertNull(directory.serverOf("other"));
        assertNull(directory.serverOf("unknown"));
    }

    @Test
    public void aServerFindsItsClientsByTheirAddresses() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.50", state("a_distInfo_server_pair")),
                snap("client", "192.0.2.1", state("c_distInfo_client")),
                snap("other", "192.0.2.77", state("b_distInfo_none")));

        assertEquals(Collections.singletonList("client"), directory.clientsOf("server"));
        assertEquals(Collections.<String>emptyList(), directory.clientsOf("client"));
        assertEquals(Collections.<String>emptyList(), directory.clientsOf("other"));
    }

    @Test
    public void aClientWhoseAddressTheServerDoesNotListIsStillFoundByTheGroupId() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.50", state("a_distInfo_server_pair")),
                snap("client", "192.0.2.99", state("c_distInfo_client")));

        assertEquals(Collections.singletonList("client"), directory.clientsOf("server"));
    }

    @Test
    public void theMembersAreTheServerFirstThenItsClients() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("client", "192.0.2.1", state("c_distInfo_client")),
                snap("server", "192.0.2.50", state("a_distInfo_server_pair")));

        assertEquals(Arrays.asList("server", "client"), directory.membersOf("client"));
        assertEquals(Arrays.asList("server", "client"), directory.membersOf("server"));
    }

    @Test
    public void aClientWithoutAWatchedServerHasNoMembers() throws Exception {
        YxcGroupDirectory directory = directory(snap("client", "192.0.2.1", state("c_distInfo_client")));

        assertNull(directory.serverOf("client"));
        assertTrue(directory.membersOf("client").isEmpty());
    }

    @Test
    public void aDeviceInNoGroupHasNoMembers() throws Exception {
        YxcGroupDirectory directory = directory(snap("other", "192.0.2.77", state("b_distInfo_none")));

        assertTrue(directory.membersOf("other").isEmpty());
    }

    @Test
    public void theGroupIdIsComparedIgnoringCase() throws Exception {
        String lowerCase = YxcFixtures.read("captured/c_distInfo_client.json")
                .replace("00000000000000000000000000000001", "ABCDEF00000000000000000000000001");
        String upperCase = YxcFixtures.read("captured/a_distInfo_server_pair.json")
                .replace("00000000000000000000000000000001", "abcdef00000000000000000000000001");
        YxcDeviceState client = YxcDeviceState.unknown().withDistribution(
                new YxcClient(new RecordingTransport().answer("dist/getDistributionInfo", lowerCase)).getDistributionInfo());
        YxcDeviceState server = YxcDeviceState.unknown().withDistribution(
                new YxcClient(new RecordingTransport().answer("dist/getDistributionInfo", upperCase)).getDistributionInfo());

        YxcGroupDirectory directory = directory(snap("server", "192.0.2.50", server), snap("client", "192.0.2.1", client));

        assertEquals("server", directory.serverOf("client"));
    }

    @Test
    public void theStateKeepsTheGroupIdAndTheClientAddressesOfAServerOnly() throws Exception {
        YxcDeviceState server = state("a_distInfo_server_pair");
        YxcDeviceState client = state("c_distInfo_client");

        assertEquals("00000000000000000000000000000001", server.getGroupId());
        assertEquals(Collections.singletonList("192.0.2.1"), server.getClientAddresses());
        assertEquals(1, server.getClientCount());
        assertTrue("a client's own list says nothing", client.getClientAddresses().isEmpty());
        assertEquals(0, client.getClientCount());
        assertNull(state("b_distInfo_none").getGroupId());
        assertFalse(client.isStuckClient());
    }

    @Test
    public void aChangeOfTheGroupMembersChangesWhatTheRowShows() throws Exception {
        YxcDeviceState server = state("a_distInfo_server_pair");
        YxcDeviceState none = state("b_distInfo_none");

        assertFalse(server.sameForRow(none));
        assertTrue(server.sameForRow(server));
    }

    @Test
    public void aClientThatLeftButStaysOnTheLinkInputIsStuck() throws Exception {
        String json = YxcFixtures.read("captured/c_distInfo_client.json")
                .replace("00000000000000000000000000000001", "00000000000000000000000000000000");
        YxcClient client = new YxcClient(new RecordingTransport().answer("dist/getDistributionInfo", json));

        YxcDeviceState stuck = YxcDeviceState.unknown().withDistribution(client.getDistributionInfo());

        assertTrue(stuck.isStuckClient());
        assertNull(stuck.getGroupId());
    }

    @Test
    public void twoServersClaimingTheSameGroupIdAreNotBelieved() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.50", state("a_distInfo_server_pair")),
                snap("impostor", "192.0.2.66", state("a_distInfo_server_pair")),
                snap("client", "192.0.2.1", state("c_distInfo_client")));

        assertNull(directory.serverOf("client"));
    }

    @Test
    public void aServerThatDoesNotListTheClientIsNotBelieved() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.50", state("a_distInfo_server_pair")), // lists 192.0.2.1
                snap("client", "192.0.2.99", state("c_distInfo_client")));

        assertNull(directory.serverOf("client"));
    }
}
