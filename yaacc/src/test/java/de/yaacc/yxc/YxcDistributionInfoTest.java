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

import java.util.Arrays;

public class YxcDistributionInfoTest {

    private static YxcDistributionInfo read(String json) throws Exception {
        return new YxcClient(new RecordingTransport().answerEverythingWith(json)).getDistributionInfo();
    }

    @Test
    public void notLinkedIsTheWordNone() throws Exception {
        YxcDistributionInfo info = read(YxcFixtures.read("dist_info_none.json"));

        assertEquals("none", info.getRoleWord());
        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
        assertTrue(info.isNullGroup());
        assertTrue(info.getClients().isEmpty());
        assertNull(info.getStatus());
    }

    @Test
    public void theWordNoneWinsEvenWhenTheGroupIdIsNotZeros() throws Exception {
        // the scrubbed captures of the real devices carry a made-up non-zero id for a device in no group
        YxcDistributionInfo info = read("{\"response_code\":0,\"group_id\":\"00000000000000000000000000000002\",\"role\":\"None\"}");

        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
    }

    @Test
    public void anUnknownWordWithoutAGroupIsStillNotLinked() throws Exception {
        YxcDistributionInfo info = read("{\"response_code\":0,\"group_id\":\"00000000000000000000000000000000\",\"role\":\"something_new\"}");

        assertEquals("something_new", info.getRoleWord());
        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
    }

    @Test
    public void theGeneratedGroupNameOfADeviceInNoGroupIsOnlyCarried() throws Exception {
        YxcDistributionInfo info = read("{\"response_code\":0,\"group_id\":\"00000000000000000000000000000000\","
                + "\"group_name\":\"(Linked) Kitchen\",\"role\":\"none\"}");

        assertEquals("(Linked) Kitchen", info.getGroupName());
        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
        assertTrue(info.isNullGroup());
    }

    @Test
    public void aServerKeepsItsStatusAndTheDataTypeOfItsClients() throws Exception {
        YxcDistributionInfo info = read(YxcFixtures.read("captured/a_distInfo_server.json"));

        assertEquals(YxcDistributionInfo.Role.SERVER, info.getRole());
        assertEquals("working", info.getStatus());
        assertEquals(1, info.getClientEntries().size());
        assertEquals("192.0.2.1", info.getClientEntries().get(0).getIpAddress());
        assertEquals("base", info.getClientEntries().get(0).getDataType());
        assertEquals(Arrays.asList("192.0.2.1"), info.getClients());
        assertFalse(info.hasMcSurround());
        assertNull(info.getMcSurroundRole());
    }

    @Test
    public void aSurroundBlockIsOnlyNoted() throws Exception {
        YxcDistributionInfo info = read(YxcFixtures.read("captured/b_distInfo_none.json"));

        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
        assertTrue(info.hasMcSurround());
        assertEquals("none", info.getMcSurroundRole());
        assertTrue(info.getClients().isEmpty());
    }

    @Test
    public void notLinkedWithoutAnyRoleKey() throws Exception {
        YxcDistributionInfo info = read("{\"response_code\":0,\"group_id\":\"00000000000000000000000000000000\"}");

        assertNull(info.getRoleWord());
        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
    }

    @Test
    public void nothingAtAllIsNotLinkedToo() throws Exception {
        assertEquals(YxcDistributionInfo.Role.NONE, read("{\"response_code\":0}").getRole());
    }

    @Test
    public void server() throws Exception {
        YxcDistributionInfo info = read(YxcFixtures.read("dist_info_server.json"));

        assertEquals(YxcDistributionInfo.Role.SERVER, info.getRole());
        assertEquals("server", info.getRoleWord());
        assertEquals("0123456789ABCDEF0123456789ABCDEF", info.getGroupId());
        assertFalse(info.isNullGroup());
        assertEquals("Test group", info.getGroupName());
        assertEquals("main", info.getServerZone());
        assertEquals(Arrays.asList("192.0.2.12", "192.0.2.13"), info.getClients());
    }

    @Test
    public void client() throws Exception {
        YxcDistributionInfo info = read(YxcFixtures.read("dist_info_client.json"));

        assertEquals(YxcDistributionInfo.Role.CLIENT, info.getRole());
        assertEquals("0123456789ABCDEF0123456789ABCDEF", info.getGroupId());
        assertTrue(info.getClients().isEmpty());
    }

    @Test
    public void theRoleWordIsMatchedIgnoringCase() throws Exception {
        assertEquals(YxcDistributionInfo.Role.SERVER, read("{\"response_code\":0,\"role\":\"Server\"}").getRole());
        assertEquals(YxcDistributionInfo.Role.CLIENT, read("{\"response_code\":0,\"role\":\"CLIENT\"}").getRole());
    }

    @Test
    public void anUnknownWordWithAGroupIsUnknown() throws Exception {
        YxcDistributionInfo info = read("{\"response_code\":0,\"group_id\":\"0123456789ABCDEF0123456789ABCDEF\",\"role\":\"something_new\"}");

        assertEquals(YxcDistributionInfo.Role.UNKNOWN, info.getRole());
        assertEquals("something_new", info.getRoleWord());
    }

    @Test
    public void clientsWithoutAnAddressAreSkipped() throws Exception {
        YxcDistributionInfo info = read("{\"response_code\":0,\"role\":\"server\",\"client_list\":[{\"ip_address\":\"192.0.2.5\"},{},{\"ip_address\":\"\"},\"x\"]}");

        assertEquals(Arrays.asList("192.0.2.5"), info.getClients());
        assertNull("a client entry without a data type is still a client", info.getClientEntries().get(0).getDataType());
    }

    @Test
    public void nullGroupIdHelper() {
        assertTrue(YxcDistributionInfo.isNullGroupId(null));
        assertTrue(YxcDistributionInfo.isNullGroupId(""));
        assertTrue(YxcDistributionInfo.isNullGroupId(YxcDistributionInfo.NULL_GROUP_ID));
        assertFalse(YxcDistributionInfo.isNullGroupId("00000000000000000000000000000001"));
        assertEquals(32, YxcDistributionInfo.NULL_GROUP_ID.length());
    }

    @Test
    public void aHugeClientListIsCutAndLongAddressesAreSkipped() throws Exception {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            list.append(i == 0 ? "" : ",").append("{\"ip_address\":\"192.0.2.").append(i).append("\"}");
        }
        list.append(",{\"ip_address\":\"").append(new String(new char[500]).replace('\0', 'x')).append("\"}");
        String json = "{\"response_code\":0,\"group_id\":\"00000000000000000000000000000001\",\"role\":\"server\",\"client_list\":["
                + list + "]}";

        YxcDistributionInfo info = new YxcClient(new RecordingTransport().answer("dist/getDistributionInfo", json)).getDistributionInfo();

        assertEquals(YxcDistributionInfo.MAX_CLIENTS, info.getClients().size());
    }
}
