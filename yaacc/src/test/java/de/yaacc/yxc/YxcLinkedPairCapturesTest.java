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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

/**
 * The group info of two real devices that were linked (scrubbed captures of 2026-11-02): the server and its client report the same group id,
 * and the client's own answer lists the client itself, not the server.
 */
public class YxcLinkedPairCapturesTest {

    private static YxcDistributionInfo info(String fixture) throws Exception {
        return new YxcClient(new RecordingTransport()
                .answer("dist/getDistributionInfo", YxcFixtures.read("captured/" + fixture))).getDistributionInfo();
    }

    @Test
    public void theServerAndItsClientShareTheGroupId() throws Exception {
        YxcDistributionInfo server = info("a_distInfo_server_pair.json");
        YxcDistributionInfo client = info("c_distInfo_client.json");

        assertEquals(YxcDistributionInfo.Role.SERVER, server.getRole());
        assertEquals(YxcDistributionInfo.Role.CLIENT, client.getRole());
        assertFalse(server.isNullGroup());
        assertFalse(client.isNullGroup());
        assertEquals(server.getGroupId(), client.getGroupId());
    }

    @Test
    public void theServerListsTheClientWithItsAddress() throws Exception {
        YxcDistributionInfo server = info("a_distInfo_server_pair.json");

        assertEquals("working", server.getStatus());
        assertEquals(1, server.getClients().size());
        assertEquals("base", server.getClientEntries().get(0).getDataType());
    }

    @Test
    public void theClientListOfAClientHoldsTheClientItself() throws Exception {
        YxcDistributionInfo server = info("a_distInfo_server_pair.json");
        YxcDistributionInfo client = info("c_distInfo_client.json");

        // a client's answer does not name its server: the address in its client list is its own, the same one the server lists
        assertEquals(server.getClients(), client.getClients());
        assertEquals("main", client.getServerZone());
    }

    @Test
    public void aClientIsMatchedToItsServerByTheGroupIdAndTheServersClientList() throws Exception {
        YxcDistributionInfo server = info("a_distInfo_server_pair.json");
        YxcDistributionInfo client = info("c_distInfo_client.json");
        String clientAddress = client.getClients().get(0);

        boolean belongs = server.getRole() == YxcDistributionInfo.Role.SERVER
                && server.getGroupId().equals(client.getGroupId())
                && server.getClients().contains(clientAddress);

        assertTrue(belongs);
        assertFalse(Collections.emptyList().contains(clientAddress));
    }

    @Test
    public void theZoneNameAnswerIsParsableJson() throws Exception {
        org.json.JSONObject name = new org.json.JSONObject(YxcFixtures.read("captured/c_nameText_main.json"));

        assertEquals(0, name.getInt("response_code"));
        assertEquals("main", name.getString("id"));
        assertEquals("Test Room C", name.getString("text"));
    }
}
