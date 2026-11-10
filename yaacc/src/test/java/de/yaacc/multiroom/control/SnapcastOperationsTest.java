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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The calls of {@link SnapcastOperations}: the request JSON equals the documented JSON. */
public class SnapcastOperationsTest {

    private static final String GROUP = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String CLIENT = "02:00:00:00:00:01";

    /** Records the calls and answers with canned results (or a canned error). */
    private static final class RecordingCaller implements SnapcastCaller {
        final List<String> methods = new ArrayList<>();
        final List<JSONObject> params = new ArrayList<>();
        final Map<String, JSONObject> results = new HashMap<>();
        SnapcastException failure;

        RecordingCaller answer(String method, String fixture) {
            results.put(method, SnapcastFixtures.payload(fixture));
            return this;
        }

        RecordingCaller answerJson(String method, String json) throws Exception {
            results.put(method, new JSONObject(json));
            return this;
        }

        @Override
        public JSONObject call(String method, JSONObject callParams) throws SnapcastException {
            methods.add(method);
            params.add(callParams == null ? null : SnapcastFixtures.copy(callParams));
            if (failure != null) {
                throw failure;
            }
            JSONObject result = results.get(method);
            return result == null ? new JSONObject() : result;
        }

        void assertSingleCall(String method, String expectedParamsJson) throws Exception {
            assertEquals(Arrays.asList(method), methods);
            assertParams(expectedParamsJson, params.get(0));
        }
    }

    private static void assertParams(String expectedJson, JSONObject actual) throws Exception {
        if (expectedJson == null) {
            assertNull("no params expected, got " + actual, actual);
            return;
        }
        JSONObject expected = new JSONObject(expectedJson);
        assertTrue("expected " + expected + " but was " + actual, actual != null && SnapcastFixtures.similar(expected, actual));
    }

    // ---- the documented calls

    @Test
    public void getStatusSendsNoParamsAndParsesTheServer() throws Exception {
        RecordingCaller caller = new RecordingCaller().answer("Server.GetStatus", "status_three_groups.json");

        SnapcastServerStatus status = new SnapcastOperations(caller).getStatus();

        caller.assertSingleCall("Server.GetStatus", null);
        assertEquals(3, status.getGroups().size());
    }

    @Test
    public void setClientVolume() throws Exception {
        RecordingCaller caller = new RecordingCaller().answer("Client.SetVolume", "response_client_set_volume.json");

        SnapcastVolume result = new SnapcastOperations(caller).setClientVolume(CLIENT, new SnapcastVolume(42, false));

        caller.assertSingleCall("Client.SetVolume",
                "{\"id\":\"" + CLIENT + "\",\"volume\":{\"percent\":42,\"muted\":false}}");
        assertEquals(42, result.getPercent());
    }

    @Test
    public void setClientVolumeKeepsTheMutedFlagAsGiven() throws Exception {
        RecordingCaller caller = new RecordingCaller();

        new SnapcastOperations(caller).setClientVolume("c#2", new SnapcastVolume(10, true));

        caller.assertSingleCall("Client.SetVolume", "{\"id\":\"c#2\",\"volume\":{\"percent\":10,\"muted\":true}}");
    }

    @Test
    public void setClientVolumeFallsBackToTheRequestedVolumeWhenTheAnswerLacksOne() throws Exception {
        RecordingCaller caller = new RecordingCaller();

        SnapcastVolume result = new SnapcastOperations(caller).setClientVolume(CLIENT, new SnapcastVolume(33, true));

        assertEquals(33, result.getPercent());
        assertTrue(result.isMuted());
    }

    @Test
    public void setClientLatencyIsClamped() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        SnapcastOperations operations = new SnapcastOperations(caller);

        operations.setClientLatency(CLIENT, 10);
        operations.setClientLatency(CLIENT, -4);
        operations.setClientLatency(CLIENT, 123_456);

        assertParams("{\"id\":\"" + CLIENT + "\",\"latency\":10}", caller.params.get(0));
        assertParams("{\"id\":\"" + CLIENT + "\",\"latency\":0}", caller.params.get(1));
        assertParams("{\"id\":\"" + CLIENT + "\",\"latency\":10000}", caller.params.get(2));
        assertEquals("Client.SetLatency", caller.methods.get(0));
    }

    @Test
    public void setClientName() throws Exception {
        RecordingCaller caller = new RecordingCaller();

        new SnapcastOperations(caller).setClientName(CLIENT, "Laptop");

        caller.assertSingleCall("Client.SetName", "{\"id\":\"" + CLIENT + "\",\"name\":\"Laptop\"}");
    }

    @Test
    public void setGroupMute() throws Exception {
        RecordingCaller caller = new RecordingCaller();

        new SnapcastOperations(caller).setGroupMute(GROUP, true);

        caller.assertSingleCall("Group.SetMute", "{\"id\":\"" + GROUP + "\",\"mute\":true}");
    }

    @Test
    public void setGroupStream() throws Exception {
        RecordingCaller caller = new RecordingCaller().answer("Group.SetStream", "response_group_set_stream.json");

        new SnapcastOperations(caller).setGroupStream(GROUP, "stream 1");

        caller.assertSingleCall("Group.SetStream", "{\"id\":\"" + GROUP + "\",\"stream_id\":\"stream 1\"}");
    }

    @Test
    public void setGroupClientsSendsTheFullWantedListInOrder() throws Exception {
        RecordingCaller caller = new RecordingCaller().answer("Group.SetClients", "response_set_clients.json");

        SnapcastServerStatus status = new SnapcastOperations(caller)
                .setGroupClients(GROUP, Arrays.asList("00:21:6a:7d:74:fc#2", "00:21:6a:7d:74:fc"));

        caller.assertSingleCall("Group.SetClients",
                "{\"id\":\"" + GROUP + "\",\"clients\":[\"00:21:6a:7d:74:fc#2\",\"00:21:6a:7d:74:fc\"]}");
        assertEquals("the answer is the new status", 1, status.getGroups().size());
        JSONArray sent = caller.params.get(0).getJSONArray("clients");
        assertEquals("00:21:6a:7d:74:fc#2", sent.getString(0));
    }

    @Test
    public void setGroupClientsRemovesDuplicates() throws Exception {
        RecordingCaller caller = new RecordingCaller().answer("Group.SetClients", "response_set_clients.json");

        new SnapcastOperations(caller).setGroupClients(GROUP, Arrays.asList("a", "b", "a"));

        assertEquals(2, caller.params.get(0).getJSONArray("clients").length());
    }

    @Test
    public void setGroupClientsRefusesMoreThanTheCapInsteadOfCuttingTheList() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= SnapcastLimits.MAX_CLIENTS_PER_GROUP; i++) {
            tooMany.add("c" + i);
        }

        try {
            new SnapcastOperations(caller).setGroupClients(GROUP, tooMany);
            fail("a cut list would remove members");
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.LIMIT, expected.getKind());
        }
        assertTrue(caller.methods.isEmpty());
    }

    @Test
    public void setGroupName() throws Exception {
        RecordingCaller caller = new RecordingCaller();

        new SnapcastOperations(caller).setGroupName(GROUP, "GroundFloor");

        caller.assertSingleCall("Group.SetName", "{\"id\":\"" + GROUP + "\",\"name\":\"GroundFloor\"}");
    }

    @Test
    public void addStreamAcceptsTheAnswerOfTheSourceAndTheOneOfTheDocumentation() throws Exception {
        String uri = "tcp://0.0.0.0:4953?name=YAACC%20test%20phone&mode=server";

        RecordingCaller sourceShape = new RecordingCaller().answer("Stream.AddStream", "response_add_stream.json");
        assertEquals("YAACC test phone", new SnapcastOperations(sourceShape).addStream(uri));
        sourceShape.assertSingleCall("Stream.AddStream", new JSONObject().put("streamUri", uri).toString());

        RecordingCaller docShape = new RecordingCaller().answer("Stream.AddStream", "response_add_stream_doc_shape.json");
        assertEquals("YAACC test phone", new SnapcastOperations(docShape).addStream(uri));
    }

    @Test
    public void addStreamWithoutAnIdInTheAnswerIsAProtocolError() throws Exception {
        RecordingCaller caller = new RecordingCaller().answerJson("Stream.AddStream", "{\"other\":1}");

        try {
            new SnapcastOperations(caller).addStream("tcp://0.0.0.0:4953?name=x");
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
        }
    }

    @Test
    public void removeStream() throws Exception {
        RecordingCaller caller = new RecordingCaller().answer("Stream.RemoveStream", "response_remove_stream.json");

        String id = new SnapcastOperations(caller).removeStream("YAACC test phone");

        caller.assertSingleCall("Stream.RemoveStream", "{\"id\":\"YAACC test phone\"}");
        assertEquals("YAACC test phone", id);
    }

    @Test
    public void removeStreamAcceptsTheDocumentedAnswerShapeToo() throws Exception {
        RecordingCaller caller = new RecordingCaller().answerJson("Stream.RemoveStream", "{\"stream_id\":\"s\"}");

        assertEquals("s", new SnapcastOperations(caller).removeStream("s"));
    }

    // ---- errors and arguments

    @Test
    public void aServerErrorReachesTheCallerWithItsData() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        caller.failure = new SnapcastException(SnapcastException.Kind.SERVER_ERROR, -32603, "Internal error",
                "Stream with name 'x' already exists", null);

        try {
            new SnapcastOperations(caller).addStream("tcp://0.0.0.0:4953?name=x");
            fail();
        } catch (SnapcastException expected) {
            assertEquals(-32603, expected.getCode());
            assertEquals("Stream with name 'x' already exists", expected.getData());
        }
    }

    @Test
    public void emptyOrNullIdsAreProgrammingErrors() throws Exception {
        SnapcastOperations operations = new SnapcastOperations(new RecordingCaller());
        for (Object[] args : new Object[][]{{null}, {""}}) {
            try {
                operations.setGroupMute((String) args[0], true);
                fail();
            } catch (IllegalArgumentException expected) {
                // as wanted
            }
        }
        try {
            operations.setClientVolume(CLIENT, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // as wanted
        }
    }

    @Test
    public void longNamesAreTruncatedBeforeTheyLeave() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            huge.append('n');
        }

        new SnapcastOperations(caller).setClientName(CLIENT, huge.toString());

        assertEquals(SnapcastLimits.MAX_STRING_LENGTH, caller.params.get(0).getString("name").length());
    }

    @Test
    public void aRequestWithQuotesAndNewlinesStaysOneLine() throws Exception {
        RecordingCaller caller = new RecordingCaller();

        new SnapcastOperations(caller).setClientName(CLIENT, "a\"b\nc");

        assertEquals("a\"b\nc", caller.params.get(0).getString("name"));
        assertFalse(caller.params.get(0).toString().contains("\n"));
    }

    // ---- the model follows our own changes (the server does not tell the connection that made them)

    private static final class Model {
        final int[] reReads = {0};
        final SnapcastModelUpdater updater = new SnapcastModelUpdater(() -> 0L, () -> reReads[0]++);
    }

    private static Model modelWithStatus() {
        Model model = new Model();
        model.updater.setStatus(SnapcastFixtures.status("status_one_group.json"));
        return model;
    }

    @Test
    public void ourOwnChangesPatchTheLocalModel() throws Exception {
        Model model = modelWithStatus();
        SnapcastOperations operations = new SnapcastOperations(new RecordingCaller(), model.updater);

        operations.setClientVolume(CLIENT, new SnapcastVolume(33, true));
        operations.setClientLatency(CLIENT, 40);
        operations.setClientName(CLIENT, "Den");
        operations.setGroupMute(GROUP, true);
        operations.setGroupStream(GROUP, "Other");
        operations.setGroupName(GROUP, "Floor");

        SnapcastClient client = model.updater.getStatus().findClient(CLIENT);
        assertEquals(new SnapcastVolume(33, true), client.getVolume());
        assertEquals(40, client.getLatencyMs());
        assertEquals("Den", client.getName());
        SnapcastGroup group = model.updater.getStatus().findGroup(GROUP);
        assertTrue(group.isMuted());
        assertEquals("Other", group.getStreamId());
        assertEquals("Floor", group.getName());
        assertEquals("a patch that fits needs no re-read", 0, model.reReads[0]);
    }

    @Test
    public void theStatusAnswerOfSetGroupClientsBecomesTheModel() throws Exception {
        Model model = modelWithStatus();
        RecordingCaller caller = new RecordingCaller().answer("Group.SetClients", "response_set_clients.json");

        new SnapcastOperations(caller, model.updater).setGroupClients(GROUP, Arrays.asList(CLIENT));

        assertEquals(SnapcastFixtures.status("response_set_clients.json"), model.updater.getStatus());
    }

    @Test
    public void getStatusFeedsTheModel() throws Exception {
        Model model = new Model();
        RecordingCaller caller = new RecordingCaller().answer("Server.GetStatus", "status_three_groups.json");

        SnapcastServerStatus status = new SnapcastOperations(caller, model.updater).getStatus();

        assertEquals(status, model.updater.getStatus());
    }

    @Test
    public void addingOrRemovingAStreamAsksForAReRead() throws Exception {
        Model model = modelWithStatus();
        RecordingCaller caller = new RecordingCaller().answer("Stream.AddStream", "response_add_stream.json");

        new SnapcastOperations(caller, model.updater).addStream("tcp://0.0.0.0:4953?name=YAACC%20test%20phone&mode=server");

        assertEquals("the server tells the other connections only", 1, model.reReads[0]);
    }

    @Test
    public void withoutAModelThereIsNothingToPatchAndNoReRead() throws Exception {
        Model model = new Model();

        new SnapcastOperations(new RecordingCaller(), model.updater).setClientVolume(CLIENT, new SnapcastVolume(10, false));

        assertNull(model.updater.getStatus());
        assertEquals(0, model.reReads[0]);
    }

    @Test
    public void aFailedCallLeavesTheModelAlone() throws Exception {
        Model model = modelWithStatus();
        SnapcastServerStatus before = model.updater.getStatus();
        RecordingCaller caller = new RecordingCaller();
        caller.failure = new SnapcastException(SnapcastException.Kind.TIMEOUT, 0, "slow", null, null);

        try {
            new SnapcastOperations(caller, model.updater).setClientVolume(CLIENT, new SnapcastVolume(10, false));
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.TIMEOUT, expected.getKind());
        }
        assertEquals(before, model.updater.getStatus());
    }

    @Test
    public void setGroupClientsRefusesNullAndAnEmptyList() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        SnapcastOperations operations = new SnapcastOperations(caller);

        try {
            operations.setGroupClients(GROUP, null);
            fail("null");
        } catch (IllegalArgumentException expected) {
            // as documented
        }
        try {
            operations.setGroupClients(GROUP, new ArrayList<String>());
            fail("an empty list would dissolve the group");
        } catch (IllegalArgumentException expected) {
            // as documented
        }
        assertTrue(caller.methods.isEmpty());
    }

    // ---- group volume through the server

    private static SnapcastGroup groupForVolume() {
        List<SnapcastClient> clients = new ArrayList<>();
        clients.add(new SnapcastClient("A", "A", "", true, new SnapcastVolume(70, false), 0));
        clients.add(new SnapcastClient("B", "B", "", true, new SnapcastVolume(55, true), 0));
        clients.add(new SnapcastClient("C", "C", "", false, new SnapcastVolume(90, false), 0));
        return new SnapcastGroup(GROUP, "", false, "s", clients);
    }

    @Test
    public void groupVolumeMovesEveryConnectedMemberAndLeavesMutedMembersMuted() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        SnapcastGroup group = groupForVolume(); // A 70, B 55 (muted), C offline -> average 63

        Map<String, Integer> applied = new SnapcastOperations(caller).setGroupVolume(group, 73);

        assertEquals(Arrays.asList("Client.SetVolume", "Client.SetVolume"), caller.methods);
        assertParams("{\"id\":\"A\",\"volume\":{\"percent\":80,\"muted\":false}}", caller.params.get(0));
        assertParams("{\"id\":\"B\",\"volume\":{\"percent\":65,\"muted\":true}}", caller.params.get(1));
        assertEquals(80, (int) applied.get("A"));
        assertEquals(65, (int) applied.get("B"));
        assertFalse("an offline member is not touched", applied.containsKey("C"));
    }

    @Test
    public void groupVolumeSendsNothingForMembersThatDoNotChange() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        List<SnapcastClient> clients = new ArrayList<>();
        clients.add(new SnapcastClient("A", "A", "", true, new SnapcastVolume(100, false), 0));
        clients.add(new SnapcastClient("B", "B", "", true, new SnapcastVolume(50, false), 0));
        SnapcastGroup group = new SnapcastGroup(GROUP, "", false, "s", clients);

        new SnapcastOperations(caller).setGroupVolume(group, 85); // average 75 -> +10: A is at its edge, B takes it

        assertEquals(Arrays.asList("Client.SetVolume"), caller.methods);
        assertParams("{\"id\":\"B\",\"volume\":{\"percent\":70,\"muted\":false}}", caller.params.get(0));
    }

    @Test
    public void groupVolumeOnAGroupWithoutConnectedMembersDoesNothing() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        SnapcastGroup group = new SnapcastGroup(GROUP, "", false, "s", Arrays.asList(
                new SnapcastClient("A", "A", "", false, new SnapcastVolume(70, false), 0)));

        assertTrue(new SnapcastOperations(caller).setGroupVolume(group, 50).isEmpty());
        assertTrue(caller.methods.isEmpty());
    }

    @Test
    public void groupVolumeTriesEveryMemberAndReportsTheFirstFailure() throws Exception {
        final List<String> ids = new ArrayList<>();
        SnapcastCaller failing = (method, params) -> {
            ids.add(params.optString("id"));
            if (ids.size() == 1) {
                throw new SnapcastException(SnapcastException.Kind.TIMEOUT, 0, "slow", null, null);
            }
            return new JSONObject();
        };

        try {
            new SnapcastOperations(failing).setGroupVolume(groupForVolume(), 90);
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.TIMEOUT, expected.getKind());
        }
        assertEquals("the second member was still set", Arrays.asList("A", "B"), ids);
    }

    @Test
    public void aDragIsCalculatedFromTheStartLevelsAndDoesNotCompound() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        SnapcastOperations operations = new SnapcastOperations(caller);
        SnapcastGroup atStart = new SnapcastGroup(GROUP, "", false, "s", Arrays.asList(
                new SnapcastClient("A", "A", "", true, new SnapcastVolume(100, false), 0),
                new SnapcastClient("B", "B", "", true, new SnapcastVolume(50, false), 0)));
        Map<String, Integer> start = GroupVolume.levels(atStart);

        operations.setGroupVolume(atStart, start, 85); // A is at its edge, B goes to 70
        SnapcastGroup afterStep = new SnapcastGroup(GROUP, "", false, "s", Arrays.asList(
                new SnapcastClient("A", "A", "", true, new SnapcastVolume(100, false), 0),
                new SnapcastClient("B", "B", "", true, new SnapcastVolume(70, false), 0)));
        Map<String, Integer> changed = operations.setGroupVolume(afterStep, start, 75); // back to the start average

        assertEquals(Arrays.asList("Client.SetVolume", "Client.SetVolume"), caller.methods);
        assertParams("{\"id\":\"B\",\"volume\":{\"percent\":70,\"muted\":false}}", caller.params.get(0));
        assertParams("{\"id\":\"B\",\"volume\":{\"percent\":50,\"muted\":false}}", caller.params.get(1));
        assertEquals("B is back at its own start level, A is untouched", 1, changed.size());
        assertEquals(50, (int) changed.get("B"));
    }
}
