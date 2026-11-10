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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/** Parsing of the server status and the value objects of the control library. */
public class SnapcastModelTest {

    private static final String GROUP_1 = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String GROUP_2 = "aaaaaaaa-0000-4000-8000-000000000002";
    private static final String GROUP_3 = "aaaaaaaa-0000-4000-8000-000000000003";

    // ---- the fixtures

    @Test
    public void emptyStatusHasNoGroupsButAStream() {
        SnapcastServerStatus status = SnapcastFixtures.status("status_empty.json");

        assertTrue(status.getGroups().isEmpty());
        assertEquals(1, status.getStreams().size());
        assertEquals("Default", status.getStreams().get(0).getId());
        assertEquals("idle", status.getStreams().get(0).getStatus());
        assertEquals("TEST-SERVER", status.getInfo().getHostName());
        assertEquals("0.35.0", status.getInfo().getVersion());
    }

    @Test
    public void oneGroupStatusIsParsedInDetail() {
        SnapcastServerStatus status = SnapcastFixtures.status("status_one_group.json");

        assertEquals(1, status.getGroups().size());
        SnapcastGroup group = status.getGroups().get(0);
        assertEquals(GROUP_1, group.getId());
        assertEquals("", group.getName());
        assertFalse(group.isMuted());
        assertEquals("Default", group.getStreamId());
        assertEquals(2, group.getClients().size());
        SnapcastClient kitchen = group.getClients().get(0);
        assertEquals("02:00:00:00:00:01", kitchen.getId());
        assertEquals("Kitchen", kitchen.getName());
        assertEquals("TEST-HOST-A", kitchen.getHostName());
        assertTrue(kitchen.isConnected());
        assertEquals(70, kitchen.getVolume().getPercent());
        assertFalse(kitchen.getVolume().isMuted());
        assertEquals(0, kitchen.getLatencyMs());
        assertEquals(20, group.getClients().get(1).getLatencyMs());
        SnapcastServerInfo info = status.getInfo();
        assertEquals("TEST-SERVER", info.getHostName());
        assertEquals("Snapserver", info.getServerName());
        assertEquals("0.35.0", info.getVersion());
        assertEquals(1, info.getProtocolVersion());
        assertEquals(1, info.getControlProtocolVersion());
    }

    @Test
    public void threeGroupStatusKeepsDisconnectedMutedAndInstanceClients() {
        SnapcastServerStatus status = SnapcastFixtures.status("status_three_groups.json");

        assertEquals(3, status.getGroups().size());
        assertEquals("Ground floor", status.findGroup(GROUP_1).getName());
        assertEquals("YAACC test phone", status.findGroup(GROUP_1).getStreamId());
        assertTrue(status.findGroup(GROUP_2).isMuted());
        assertEquals("", status.findGroup(GROUP_3).getStreamId());
        SnapcastClient study = status.findClient("02:00:00:00:00:04#2");
        assertNotNull(study);
        assertFalse(study.isConnected());
        assertEquals(90, study.getVolume().getPercent());
        SnapcastClient bedroom = status.findClient("02:00:00:00:00:03");
        assertTrue(bedroom.getVolume().isMuted());
        assertEquals(30, bedroom.getVolume().getPercent());
        assertEquals("", status.findClient("02:00:00:00:00:02").getName());
        assertEquals(GROUP_2, status.findGroupOfClient("02:00:00:00:00:04#2").getId());
        assertNull(status.findClient("nope"));
        assertNull(status.findGroup("nope"));
        assertNull(status.findGroupOfClient("nope"));
        assertEquals(2, status.getStreams().size());
    }

    @Test
    public void tcpStreamUriGivesSchemeHostPortAndQuery() {
        SnapcastStream stream = SnapcastFixtures.status("status_three_groups.json").findStream("YAACC test phone");

        assertNotNull(stream);
        assertEquals("playing", stream.getStatus());
        assertEquals("tcp", stream.getScheme());
        assertEquals("0.0.0.0", stream.getHost());
        assertEquals(4953, stream.getPort());
        assertEquals("server", stream.getQueryParameter("mode"));
        assertEquals("48000:16:2", stream.getQueryParameter("sampleformat"));
        assertEquals("YAACC test phone", stream.getQueryParameter("name"));
        assertNull(stream.getQueryParameter("nope"));
    }

    @Test
    public void pipeStreamHasNoHostAndNoPort() {
        SnapcastStream stream = SnapcastFixtures.status("status_one_group.json").findStream("Default");

        assertEquals("pipe", stream.getScheme());
        assertEquals("", stream.getHost());
        assertEquals(-1, stream.getPort());
        assertEquals("/tmp/snapfifo", stream.getPath());
    }

    @Test
    public void streamHostAndPortComeFromTheCombinedField() throws Exception {
        assertEquals(1234, streamWithHost("192.0.2.5:1234").getPort());
        assertEquals("192.0.2.5", streamWithHost("192.0.2.5:1234").getHost());
        assertEquals(-1, streamWithHost("192.0.2.5").getPort());
        assertEquals("192.0.2.5", streamWithHost("192.0.2.5").getHost());
        assertEquals("[::1]", streamWithHost("[::1]:4953").getHost());
        assertEquals(4953, streamWithHost("[::1]:4953").getPort());
        assertEquals("a loose IPv6 literal has no port", -1, streamWithHost("2001:db8::1").getPort());
        assertEquals(-1, streamWithHost("192.0.2.5:0").getPort());
        assertEquals(-1, streamWithHost("192.0.2.5:70000").getPort());
        assertEquals(-1, streamWithHost("192.0.2.5:abc").getPort());
    }

    private static SnapcastStream streamWithHost(String host) throws Exception {
        JSONObject uri = new JSONObject().put("host", host).put("scheme", "tcp");
        return SnapcastStream.fromJson(new JSONObject().put("id", "s").put("uri", uri));
    }

    @Test
    public void notificationAndResponseFixturesCarryTheSameStatusShape() {
        SnapcastServerStatus fromSetClients = SnapcastFixtures.status("response_set_clients.json");
        SnapcastServerStatus fromUpdate = SnapcastFixtures.status("notification_server_on_update.json");

        assertEquals(1, fromSetClients.getGroups().size());
        assertEquals(1, fromSetClients.getGroups().get(0).getClients().size());
        assertEquals(fromSetClients.getGroups(), fromUpdate.getGroups());
    }

    @Test
    public void everyFixtureStatusParses() {
        for (String name : Arrays.asList("status_empty.json", "status_one_group.json", "status_three_groups.json",
                "response_set_clients.json", "notification_server_on_update.json")) {
            assertNotNull(name, SnapcastFixtures.status(name));
        }
    }

    // ---- tolerance

    @Test
    public void unknownFieldsAreIgnored() throws Exception {
        JSONObject payload = SnapcastFixtures.copy(SnapcastFixtures.payload("status_one_group.json"));
        JSONObject server = payload.getJSONObject("server");
        server.put("futureField", new JSONObject().put("x", 1));
        JSONObject group = server.getJSONArray("groups").getJSONObject(0);
        group.put("futureGroupField", new JSONArray().put(1).put(2));
        group.getJSONArray("clients").getJSONObject(0).put("futureClientField", "x");
        group.getJSONArray("clients").getJSONObject(0).getJSONObject("config").put("future", true);

        SnapcastServerStatus parsed = SnapcastServerStatus.fromResult(payload);

        assertEquals(SnapcastFixtures.status("status_one_group.json"), parsed);
    }

    @Test
    public void missingOptionalFieldsGetDefaults() throws Exception {
        JSONObject client = new JSONObject().put("id", "c1");
        JSONObject group = new JSONObject().put("id", "g1").put("clients", new JSONArray().put(client));
        JSONObject payload = new JSONObject().put("server", new JSONObject().put("groups", new JSONArray().put(group)));

        SnapcastServerStatus status = SnapcastServerStatus.fromResult(payload);

        SnapcastGroup parsedGroup = status.findGroup("g1");
        assertEquals("", parsedGroup.getName());
        assertEquals("", parsedGroup.getStreamId());
        assertFalse(parsedGroup.isMuted());
        SnapcastClient parsed = parsedGroup.getClients().get(0);
        assertEquals("", parsed.getName());
        assertEquals("", parsed.getHostName());
        assertFalse(parsed.isConnected());
        assertEquals(0, parsed.getLatencyMs());
        assertEquals(100, parsed.getVolume().getPercent());
        assertFalse(parsed.getVolume().isMuted());
        assertTrue(status.getStreams().isEmpty());
        assertEquals("", status.getInfo().getHostName());
        assertEquals(0, status.getInfo().getProtocolVersion());
    }

    @Test
    public void groupsAndClientsWithoutIdAreSkipped() throws Exception {
        JSONObject goodClient = new JSONObject().put("id", "c1");
        JSONObject badClient = new JSONObject().put("config", new JSONObject().put("name", "no id"));
        JSONObject goodGroup = new JSONObject().put("id", "g1").put("clients", new JSONArray().put(badClient).put(goodClient));
        JSONObject badGroup = new JSONObject().put("name", "no id");
        JSONObject payload = new JSONObject().put("server", new JSONObject()
                .put("groups", new JSONArray().put(badGroup).put("junk").put(7).put(goodGroup))
                .put("streams", new JSONArray().put(new JSONObject().put("status", "idle")).put(null)));

        SnapcastServerStatus status = SnapcastServerStatus.fromResult(payload);

        assertEquals(1, status.getGroups().size());
        assertEquals(1, status.getGroups().get(0).getClients().size());
        assertEquals("c1", status.getGroups().get(0).getClients().get(0).getId());
        assertTrue(status.getStreams().isEmpty());
    }

    @Test
    public void duplicateIdsKeepTheFirst() throws Exception {
        JSONObject c1 = new JSONObject().put("id", "c1").put("config", new JSONObject().put("name", "first"));
        JSONObject c1again = new JSONObject().put("id", "c1").put("config", new JSONObject().put("name", "second"));
        JSONObject g1 = new JSONObject().put("id", "g1").put("clients", new JSONArray().put(c1).put(c1again));
        JSONObject g1again = new JSONObject().put("id", "g1").put("name", "again");
        JSONObject g2 = new JSONObject().put("id", "g2").put("clients", new JSONArray().put(c1again));
        JSONObject payload = new JSONObject().put("server", new JSONObject()
                .put("groups", new JSONArray().put(g1).put(g1again).put(g2)));

        SnapcastServerStatus status = SnapcastServerStatus.fromResult(payload);

        assertEquals(2, status.getGroups().size());
        assertEquals(1, status.findGroup("g1").getClients().size());
        assertEquals("first", status.findClient("c1").getName());
        assertTrue("a client lives in one group only", status.findGroup("g2").getClients().isEmpty());
    }

    @Test
    public void valuesOfTheWrongTypeFallBackToDefaults() throws Exception {
        JSONObject client = new JSONObject().put("id", "c1").put("connected", "yes")
                .put("config", new JSONObject().put("name", new JSONObject()).put("latency", "fast")
                        .put("volume", new JSONObject().put("percent", "loud").put("muted", 1)));
        JSONObject group = new JSONObject().put("id", "g1").put("muted", "no").put("name", 5)
                .put("clients", new JSONArray().put(client));
        JSONObject payload = new JSONObject().put("server", new JSONObject().put("groups", new JSONArray().put(group)));

        SnapcastClient parsed = SnapcastServerStatus.fromResult(payload).findClient("c1");

        assertFalse(parsed.isConnected());
        assertEquals("", parsed.getName());
        assertEquals(0, parsed.getLatencyMs());
        assertEquals(100, parsed.getVolume().getPercent());
        assertFalse(parsed.getVolume().isMuted());
    }

    // ---- clamping and caps

    @Test
    public void volumeIsClampedTo0To100AndLatencyTo0To10000() throws Exception {
        assertEquals(100, clientWith(1_000_000, 0).getVolume().getPercent());
        assertEquals(0, clientWith(-5, 0).getVolume().getPercent());
        assertEquals(0, clientWith(0, -50).getLatencyMs());
        assertEquals(10_000, clientWith(0, 1_000_000).getLatencyMs());
        assertEquals(10_000, clientWith(0, 4_000_000_000_000L).getLatencyMs());
        assertEquals(100, clientWith(1e300, 0).getVolume().getPercent());
        assertEquals(0, clientWith(-1e300, 0).getVolume().getPercent());
        assertEquals(10_000, clientWith(0, new java.math.BigInteger("99999999999999999999999999")).getLatencyMs());
        assertEquals(0, clientWith(0, new java.math.BigInteger("-99999999999999999999999999")).getLatencyMs());
        assertEquals(74, clientWith(74.4, 0).getVolume().getPercent());
    }

    private static SnapcastClient clientWith(Object percent, Object latency) throws Exception {
        JSONObject config = new JSONObject().put("latency", latency)
                .put("volume", new JSONObject().put("percent", percent).put("muted", false));
        return SnapcastClient.fromJson(new JSONObject().put("id", "c").put("config", config));
    }

    @Test
    public void volumeValueObjectClamps() {
        assertEquals(100, new SnapcastVolume(500, false).getPercent());
        assertEquals(0, new SnapcastVolume(-1, true).getPercent());
        assertEquals(30, new SnapcastVolume(20, false).withPercent(30).getPercent());
        assertTrue(new SnapcastVolume(20, false).withMuted(true).isMuted());
        assertEquals(100, new SnapcastVolume(20, false).withPercent(900).getPercent());
    }

    @Test
    public void stringsAreTruncatedAtOneKiB() throws Exception {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            huge.append('x');
        }
        JSONObject config = new JSONObject().put("name", huge.toString());
        SnapcastClient client = SnapcastClient.fromJson(new JSONObject().put("id", huge.toString()).put("config", config));

        assertEquals(SnapcastLimits.MAX_STRING_LENGTH, client.getName().length());
        assertEquals(SnapcastLimits.MAX_STRING_LENGTH, client.getId().length());
        assertEquals(1024, SnapcastLimits.MAX_STRING_LENGTH);
    }

    @Test
    public void truncationDoesNotSplitASurrogatePair() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < SnapcastLimits.MAX_STRING_LENGTH - 1; i++) {
            text.append('x');
        }
        text.append("😀"); // an emoji, two chars: the cut falls between them
        SnapcastClient client = SnapcastClient.fromJson(new JSONObject().put("id", "c")
                .put("config", new JSONObject().put("name", text.toString())));

        String name = client.getName();
        assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)));
    }

    @Test
    public void groupsClientsAndStreamsAreCapped() throws Exception {
        JSONArray groups = new JSONArray();
        for (int g = 0; g < 100; g++) {
            JSONArray clients = new JSONArray();
            for (int c = 0; c < 300; c++) {
                clients.put(new JSONObject().put("id", "c" + g + "-" + c));
            }
            groups.put(new JSONObject().put("id", "g" + g).put("clients", clients));
        }
        JSONArray streams = new JSONArray();
        for (int s = 0; s < 200; s++) {
            streams.put(new JSONObject().put("id", "s" + s));
        }
        JSONObject payload = new JSONObject().put("server", new JSONObject().put("groups", groups).put("streams", streams));

        SnapcastServerStatus status = SnapcastServerStatus.fromResult(payload);

        assertEquals(SnapcastLimits.MAX_GROUPS, status.getGroups().size());
        assertEquals(SnapcastLimits.MAX_CLIENTS_PER_GROUP, status.getGroups().get(0).getClients().size());
        assertEquals(SnapcastLimits.MAX_STREAMS, status.getStreams().size());
        assertEquals(64, SnapcastLimits.MAX_GROUPS);
        assertEquals(256, SnapcastLimits.MAX_CLIENTS_PER_GROUP);
        assertEquals(64, SnapcastLimits.MAX_STREAMS);
    }

    @Test
    public void streamQueryIsCapped() throws Exception {
        JSONObject query = new JSONObject();
        for (int i = 0; i < 500; i++) {
            query.put("k" + i, "v" + i);
        }
        SnapcastStream stream = SnapcastStream.fromJson(new JSONObject().put("id", "s")
                .put("uri", new JSONObject().put("query", query)));

        assertTrue(stream.getQuery().size() <= SnapcastLimits.MAX_QUERY_ENTRIES);
    }

    // ---- malformed input

    @Test
    public void malformedTextGivesATypedExceptionNeverAnUncheckedOne() {
        String[] samples = {"", " ", "not json", "{", "[1,2", "{\"server\":", "null", "42", "\"text\"", "{\"server\":[]}",
                "{\"server\":\"x\"}", "{}", "[]", "{'server':{}}", "{\"server\":{}} trailing"};
        for (String sample : samples) {
            try {
                SnapcastServerStatus.parseResult(sample);
                fail("accepted: " + sample);
            } catch (SnapcastException expected) {
                assertEquals(sample, SnapcastException.Kind.MALFORMED, expected.getKind());
            }
        }
    }

    @Test
    public void nullInputGivesATypedException() {
        try {
            SnapcastServerStatus.parseResult(null);
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
        }
        try {
            SnapcastServerStatus.fromResult(null);
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
        }
    }

    // ---- errors

    @Test
    public void errorFixturesBecomeTypedExceptionsWithTheReasonInData() throws Exception {
        SnapcastException internal = errorOf("error_internal.json");
        assertEquals(-32603, internal.getCode());
        assertEquals("Internal error", internal.getMessage());
        assertEquals("Stream with name 'YAACC test phone' already exists", internal.getData());
        assertEquals(SnapcastException.Kind.SERVER_ERROR, internal.getKind());
        assertFalse(internal.isAuthorizationError());

        SnapcastException params = errorOf("error_invalid_params.json");
        assertEquals(-32602, params.getCode());
        assertEquals("Stream with id 'nope' not found", params.getData());

        SnapcastException missing = errorOf("error_method_not_found.json");
        assertEquals(-32601, missing.getCode());
        assertNull(missing.getData());
    }

    @Test
    public void unauthorizedAndForbiddenAreRecognizable() throws Exception {
        SnapcastException unauthorized = errorOf("error_unauthorized.json");
        assertEquals(401, unauthorized.getCode());
        assertTrue(unauthorized.isUnauthorized());
        assertFalse(unauthorized.isForbidden());
        assertTrue(unauthorized.isAuthorizationError());

        SnapcastException forbidden = errorOf("error_forbidden.json");
        assertEquals(403, forbidden.getCode());
        assertTrue(forbidden.isForbidden());
        assertFalse(forbidden.isUnauthorized());
        assertTrue(forbidden.isAuthorizationError());
    }

    private static SnapcastException errorOf(String fixture) throws Exception {
        SnapcastMessage message = SnapcastMessage.parseLine(SnapcastFixtures.read(fixture).replace("\n", " ")).get(0);
        assertEquals(SnapcastMessage.Type.RESPONSE, message.getType());
        assertNotNull(message.getError());
        return message.getError();
    }

    @Test
    public void errorWithHugeMessageAndDataIsTruncated() throws Exception {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            huge.append('m');
        }
        JSONObject error = new JSONObject().put("code", 99999999999L).put("message", huge.toString())
                .put("data", new JSONObject().put("text", huge.toString()));
        String line = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("error", error).toString();

        SnapcastException parsed = SnapcastMessage.parseLine(line).get(0).getError();

        assertTrue(parsed.getMessage().length() <= SnapcastLimits.MAX_STRING_LENGTH);
        assertTrue(parsed.getData().length() <= SnapcastLimits.MAX_STRING_LENGTH);
    }

    @Test
    public void serverErrorTextCarriesNoControlCharactersAndTheMessageStaysShort() throws Exception {
        String evil = "x\n\u001b[31m192.0.2.55\u2028\u202e\u0085\u007f\r\0 end";
        JSONObject error = new JSONObject().put("code", 5).put("message", evil).put("data", evil);
        String line = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("error", error).toString();

        SnapcastException parsed = SnapcastMessage.parseLine(line).get(0).getError();

        for (String text : new String[]{parsed.getMessage(), parsed.getData(), parsed.describe(), parsed.toString()}) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                assertTrue("control or format character 0x" + Integer.toHexString(c) + " in: " + text,
                        c >= 0x20 && c != 0x7f && !(c >= 0x80 && c <= 0x9f) && c != 0x2028 && c != 0x2029
                                && !(c >= 0x202a && c <= 0x202e) && !(c >= 0x2066 && c <= 0x2069) && c != 0x200e
                                && c != 0x200f && c != 0x061c);
            }
        }
        assertTrue(parsed.getMessage().length() <= 64);
        assertTrue(parsed.getData().contains("end"));
    }

    @Test
    public void exceptionCarriesKindCodeMessageAndData() {
        SnapcastException exception = new SnapcastException(SnapcastException.Kind.SERVER_ERROR, 7, "msg", "why", null);

        assertEquals(7, exception.getCode());
        assertEquals("msg", exception.getMessage());
        assertEquals("why", exception.getData());
        assertTrue(exception.describe().contains("msg"));
        assertTrue(exception.describe().contains("why"));
    }

    // ---- value semantics

    @Test
    public void modelsAreValueObjects() {
        SnapcastServerStatus a = SnapcastFixtures.status("status_three_groups.json");
        SnapcastServerStatus b = SnapcastFixtures.status("status_three_groups.json");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, SnapcastFixtures.status("status_one_group.json"));
        assertNotEquals(a.getGroups().get(0), a.getGroups().get(1));
    }

    @Test
    public void listsAndMapsAreUnmodifiable() {
        SnapcastServerStatus status = SnapcastFixtures.status("status_three_groups.json");

        assertUnsupported(() -> status.getGroups().clear());
        assertUnsupported(() -> status.getStreams().clear());
        assertUnsupported(() -> status.getGroups().get(0).getClients().clear());
        assertUnsupported(() -> status.findStream("YAACC test phone").getQuery().clear());
    }

    private static void assertUnsupported(Runnable action) {
        try {
            action.run();
            fail("modifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable as wanted
        }
    }

    @Test
    public void withersGiveNewObjectsAndLeaveTheOriginalAlone() {
        SnapcastServerStatus status = SnapcastFixtures.status("status_three_groups.json");
        SnapcastGroup group = status.findGroup(GROUP_1);

        SnapcastGroup muted = group.withMuted(true);
        SnapcastGroup renamed = group.withName("Basement");
        SnapcastGroup moved = group.withStreamId("Default");
        SnapcastClient kitchen = group.getClients().get(0);
        SnapcastGroup louder = group.withClient(kitchen.withVolume(new SnapcastVolume(99, false)));

        assertFalse(group.isMuted());
        assertTrue(muted.isMuted());
        assertEquals("Basement", renamed.getName());
        assertEquals("Default", moved.getStreamId());
        assertEquals(99, louder.getClients().get(0).getVolume().getPercent());
        assertEquals(70, group.getClients().get(0).getVolume().getPercent());
        assertEquals(12, kitchen.withLatencyMs(12).getLatencyMs());
        assertEquals(10_000, kitchen.withLatencyMs(99_999).getLatencyMs());
        assertEquals("Den", kitchen.withName("Den").getName());
        assertFalse(kitchen.withConnected(false).isConnected());
        assertEquals("replacing an unknown client changes nothing", group, group.withClient(
                new SnapcastClient("unknown", "x", "", true, new SnapcastVolume(1, false), 0)));
        assertEquals(muted, status.withGroup(muted).findGroup(GROUP_1));
        assertEquals("the group keeps its place", GROUP_1, status.withGroup(muted).getGroups().get(0).getId());
        assertEquals(status, status.withGroup(new SnapcastGroup("unknown", "", false, "", java.util.Collections.emptyList())));
    }

    @Test
    public void displayNameFallsBackToHostNameThenId() {
        SnapcastVolume volume = new SnapcastVolume(50, false);

        assertEquals("Kitchen", new SnapcastClient("id", "Kitchen", "host", true, volume, 0).getDisplayName());
        assertEquals("host", new SnapcastClient("id", "", "host", true, volume, 0).getDisplayName());
        assertEquals("id", new SnapcastClient("id", "", "", true, volume, 0).getDisplayName());
    }
}
