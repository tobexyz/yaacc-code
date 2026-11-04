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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;

/** The calls of {@link YxcClient}: exact paths, parameters and bodies, and the checks the open-source libraries skip. */
public class YxcClientTest {
    private static final String GROUP_ID = "0123456789ABCDEF0123456789ABCDEF";

    private final RecordingTransport transport = new RecordingTransport();
    private final YxcClient client = new YxcClient(transport);

    // ---- paths of the reads

    @Test
    public void readsUseTheseGetPaths() throws Exception {
        client.getDeviceInfo();
        client.getFeatures();
        client.getFuncStatus();
        client.getZoneStatus("zone2");
        client.getDistributionInfo();
        client.getPlayInfo();

        String[] paths = {"system/getDeviceInfo", "system/getFeatures", "system/getFuncStatus", "zone2/getStatus",
                "dist/getDistributionInfo", "netusb/getPlayInfo"};
        assertEquals(paths.length, transport.requests.size());
        for (int i = 0; i < paths.length; i++) {
            assertEquals("GET", transport.requests.get(i).method);
            assertEquals(paths[i], transport.requests.get(i).path);
            assertNull(transport.requests.get(i).body);
        }
    }

    @Test
    public void onlyTheFourZonesAreAccepted() throws Exception {
        for (String zone : new String[]{"main", "zone2", "zone3", "zone4"}) {
            client.getZoneStatus(zone);
        }
        for (String zone : new String[]{null, "", "zone5", "Main", "main/x", "../system", "main?x=1"}) {
            try {
                client.getZoneStatus(zone);
                fail("not a zone: " + zone);
            } catch (IllegalArgumentException expected) {
                // nothing was sent
            }
        }
        assertEquals(4, transport.requests.size());
    }

    // ---- zone setters

    @Test
    public void setPower() throws Exception {
        client.setPower("main", true);
        client.setPower("zone2", false);

        assertEquals("main/setPower?power=on", transport.requests.get(0).path);
        assertEquals("zone2/setPower?power=standby", transport.requests.get(1).path);
    }

    @Test
    public void setInputSendsTheEmptyModeLikeTheLibrariesDo() throws Exception {
        client.setInput("main", "mc_link", "");
        client.setInput("main", "net radio", null);

        assertEquals("main/setInput?input=mc_link&mode=", transport.requests.get(0).path);
        assertEquals("values are URL-encoded", "main/setInput?input=net+radio&mode=", transport.requests.get(1).path);
    }

    // ---- group calls

    @Test
    public void setServerInfoSendsTheZoneAsAStringAndTheClientsAsAList() throws Exception {
        client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, Arrays.asList("192.0.2.11", "192.0.2.12"));

        RecordingTransport.Request request = transport.only();
        assertEquals("POST", request.method);
        assertEquals("dist/setServerInfo", request.path);
        JSONObject body = new JSONObject(request.body);
        assertEquals(GROUP_ID, body.getString("group_id"));
        assertEquals("main", body.getString("zone"));
        assertEquals("add", body.getString("type"));
        JSONArray clients = body.getJSONArray("client_list");
        assertEquals(2, clients.length());
        assertEquals("192.0.2.11", clients.getString(0));
        assertEquals("192.0.2.12", clients.getString(1));
        assertEquals(4, body.length());
    }

    @Test
    public void setServerInfoRemove() throws Exception {
        client.setServerInfo(GROUP_ID, "zone2", YxcClientListAction.REMOVE, Collections.singletonList("192.0.2.11"));

        JSONObject body = new JSONObject(transport.only().body);
        assertEquals("remove", body.getString("type"));
        assertEquals("zone2", body.getString("zone"));
    }

    @Test
    public void clearServerInfoSendsOnlyAnEmptyGroupId() throws Exception {
        client.clearServerInfo();

        assertEquals("dist/setServerInfo", transport.only().path);
        assertEquals("{\"group_id\":\"\"}", transport.only().body);
    }

    @Test
    public void setClientInfoSendsTheZoneAsAListAndTheServerAsAString() throws Exception {
        client.setClientInfo(GROUP_ID, "main", "192.0.2.10");

        RecordingTransport.Request request = transport.only();
        assertEquals("POST", request.method);
        assertEquals("dist/setClientInfo", request.path);
        JSONObject body = new JSONObject(request.body);
        assertEquals(GROUP_ID, body.getString("group_id"));
        JSONArray zone = body.getJSONArray("zone");
        assertEquals(1, zone.length());
        assertEquals("main", zone.getString(0));
        assertEquals("192.0.2.10", body.getString("server_ip_address"));
        assertEquals(3, body.length());
    }

    @Test
    public void clearClientInfoSendsOnlyAnEmptyGroupId() throws Exception {
        client.clearClientInfo();

        assertEquals("dist/setClientInfo", transport.only().path);
        assertEquals("{\"group_id\":\"\"}", transport.only().body);
    }

    @Test
    public void startAndStopDistribution() throws Exception {
        client.startDistribution(0);
        client.startDistribution(3);
        client.stopDistribution();

        assertEquals("dist/startDistribution?num=0", transport.requests.get(0).path);
        assertEquals("dist/startDistribution?num=3", transport.requests.get(1).path);
        assertEquals("GET", transport.requests.get(2).method);
        assertEquals("dist/stopDistribution", transport.requests.get(2).path);
    }

    @Test
    public void setGroupName() throws Exception {
        client.setGroupName("Küche");
        client.setGroupName("");

        assertEquals("dist/setGroupName", transport.requests.get(0).path);
        assertEquals("POST", transport.requests.get(0).method);
        assertEquals("Küche", new JSONObject(transport.requests.get(0).body).getString("name"));
        assertEquals("", new JSONObject(transport.requests.get(1).body).getString("name"));
    }

    @Test
    public void groupNameLimitIs128Bytes() throws Exception {
        StringBuilder fits = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            fits.append('ü');
        }
        client.setGroupName(fits.toString());
        try {
            client.setGroupName(fits + "a");
            fail("129 bytes");
        } catch (IllegalArgumentException expected) {
            // refused before anything was sent
        }
        try {
            client.setGroupName(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void invalidGroupArgumentsAreRefusedBeforeAnythingIsSent() throws Exception {
        java.util.List<String> one = Collections.singletonList("192.0.2.11");
        String[] badIds = {null, "", "abc", GROUP_ID + "0", "0123456789ABCDEF0123456789ABCDEG"};
        for (String id : badIds) {
            expectRefused(() -> client.setServerInfo(id, "main", YxcClientListAction.ADD, one));
            expectRefused(() -> client.setClientInfo(id, "main", "192.0.2.10"));
        }
        expectRefused(() -> client.setServerInfo(GROUP_ID, "zone9", YxcClientListAction.ADD, one));
        expectRefused(() -> client.setServerInfo(GROUP_ID, "main", null, one));
        expectRefused(() -> client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, null));
        expectRefused(() -> client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, Collections.<String>emptyList()));
        expectRefused(() -> client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, Collections.singletonList("a\"b")));
        expectRefused(() -> client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, Collections.singletonList(null)));
        expectRefused(() -> client.setClientInfo(GROUP_ID, "zone9", "192.0.2.10"));
        expectRefused(() -> client.setClientInfo(GROUP_ID, "main", null));
        expectRefused(() -> client.setClientInfo(GROUP_ID, "main", "192.0.2.10/x"));
        expectRefused(() -> client.startDistribution(-1));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void aRequestWithAbsurdlyManyClientsIsRefusedButNineteenPass() throws Exception {
        // the limit of a device is its client_max (checked by the group service); the client only bounds the request size
        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            many.add("192.0.2." + i);
        }
        client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, many);
        for (int i = 20; i <= YxcClient.MAX_CLIENT_ADDRESSES + 1; i++) {
            many.add("192.0.2." + i);
        }

        expectRefused(() -> client.setServerInfo(GROUP_ID, "main", YxcClientListAction.ADD, many));
        assertEquals(1, transport.requests.size());
    }

    // ---- what the libraries do not check

    @Test
    public void everyResponseCodeButZeroIsAnException() throws Exception {
        for (String fixture : new String[]{"error_initializing.json", "error_invalid_parameter.json", "error_guarded.json", "error_timeout.json"}) {
            RecordingTransport failing = new RecordingTransport().answerEverythingWith(YxcFixtures.read(fixture));
            int expected = new JSONObject(YxcFixtures.read(fixture)).getInt("response_code");
            try {
                new YxcClient(failing).clearServerInfo();
                fail("response_code " + expected + " was taken for success");
            } catch (YxcException e) {
                assertEquals(expected, e.getResponseCode());
                assertEquals("dist/setServerInfo", e.getCall());
                assertEquals(YxcException.NONE, e.getHttpStatus());
                assertTrue(e.getMessage(), e.getMessage().contains("dist/setServerInfo"));
                assertTrue(e.getMessage(), e.getMessage().contains("response_code " + expected));
            }
        }
    }

    @Test
    public void readsCheckTheCodeToo() throws Exception {
        YxcClient guarded = new YxcClient(new RecordingTransport().answerEverythingWith("{\"response_code\":1}"));
        try {
            guarded.getFeatures();
            fail();
        } catch (YxcException e) {
            assertEquals(1, e.getResponseCode());
            assertEquals("system/getFeatures", e.getCall());
            assertTrue(e.getMessage().contains("initializing"));
        }
    }

    @Test
    public void theCallNameLeavesTheQueryOut() throws Exception {
        YxcClient failing = new YxcClient(new RecordingTransport().answerEverythingWith("{\"response_code\":5}"));
        try {
            failing.setInput("main", "mc_link", "");
            fail();
        } catch (YxcException e) {
            assertEquals("main/setInput", e.getCall());
        }
    }

    @Test
    public void anHttpStatusIsAnExceptionWithTheStatus() throws Exception {
        transport.failWith(new YxcHttpStatusException(503));
        try {
            client.getDistributionInfo();
            fail();
        } catch (YxcException e) {
            assertEquals(503, e.getHttpStatus());
            assertEquals(YxcException.NONE, e.getResponseCode());
            assertEquals("dist/getDistributionInfo", e.getCall());
        }
    }

    @Test
    public void aTransportFailureIsAnExceptionWithTheCause() throws Exception {
        IOException cause = new SocketTimeoutException("slow");
        transport.failWith(cause);
        try {
            client.stopDistribution();
            fail();
        } catch (YxcException e) {
            assertEquals(cause, e.getCause());
            assertEquals("dist/stopDistribution", e.getCall());
            assertEquals(YxcException.NONE, e.getResponseCode());
            assertEquals(YxcException.NONE, e.getHttpStatus());
        }
    }

    @Test
    public void anAnswerThatIsNotAJsonObjectIsAnException() throws Exception {
        for (String text : new String[]{"", "<html>no</html>", "[1]", "null", "{\"a\":", "42"}) {
            try {
                new YxcClient(new RecordingTransport().answerEverythingWith(text)).getDeviceInfo();
                fail("accepted: " + text);
            } catch (YxcException e) {
                assertEquals(YxcException.NONE, e.getResponseCode());
                assertTrue("the body stays out of the message", !e.getMessage().contains("html"));
            }
        }
    }

    @Test
    public void aNullAnswerIsAnException() throws Exception {
        try {
            new YxcClient(new RecordingTransport().answerEverythingWith(null)).getDeviceInfo();
            fail();
        } catch (YxcException expected) {
            assertEquals("system/getDeviceInfo", expected.getCall());
        }
    }

    @Test
    public void anAnswerWithoutResponseCodeIsAnException() throws Exception {
        for (String text : new String[]{"{}", "{\"model_name\":\"x\"}", "{\"response_code\":\"0\"}", "{\"response_code\":null}"}) {
            try {
                new YxcClient(new RecordingTransport().answerEverythingWith(text)).getDeviceInfo();
                fail("accepted: " + text);
            } catch (YxcException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("response_code"));
            }
        }
    }

    @Test
    public void aClientNeedsATransport() {
        try {
            new YxcClient(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void expectRefused(Call call) throws Exception {
        try {
            call.run();
            fail("should have been refused");
        } catch (IllegalArgumentException expected) {
            // refused before anything was sent
        }
    }

    @Test
    public void anAnswerNestedTooDeeplyIsAMalformedAnswerNotACrash() {
        String deep = new String(new char[100_000]).replace('\0', '[');
        String quoteTrick = "{'a':'\"','b':" + new String(new char[2000]).replace('\0', '[') + "}";
        for (String body : new String[]{deep, quoteTrick}) {
            YxcClient client = new YxcClient(new RecordingTransport().answerEverythingWith(body));
            try {
                client.getDistributionInfo();
                fail("accepted: " + body.length() + " characters");
            } catch (YxcException expected) {
                assertEquals(YxcException.NONE, expected.getResponseCode());
            }
        }
    }
}
