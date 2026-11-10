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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** Patching the model from notifications: every notification must give what a fresh full status gives. */
public class SnapcastModelUpdaterTest {

    private static final String GROUP_1 = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String CLIENT_1 = "02:00:00:00:00:01";

    private long now;
    private int reReads;
    private SnapcastModelUpdater updater;

    @Before
    public void setUp() {
        now = 0;
        reReads = 0;
        updater = new SnapcastModelUpdater(() -> now, () -> reReads++);
    }

    // ---- helpers

    private static JSONObject baseline() {
        return SnapcastFixtures.copy(SnapcastFixtures.payload("status_three_groups.json"));
    }

    private static SnapcastServerStatus parse(JSONObject payload) {
        try {
            return SnapcastServerStatus.fromResult(payload);
        } catch (SnapcastException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject findById(JSONArray array, String id) throws JSONException {
        for (int i = 0; i < array.length(); i++) {
            if (id.equals(array.getJSONObject(i).getString("id"))) {
                return array.getJSONObject(i);
            }
        }
        throw new AssertionError("no such id in the fixture: " + id);
    }

    private static JSONObject group(JSONObject payload, String id) throws JSONException {
        return findById(payload.getJSONObject("server").getJSONArray("groups"), id);
    }

    private static JSONObject client(JSONObject payload, String id) throws JSONException {
        JSONArray groups = payload.getJSONObject("server").getJSONArray("groups");
        for (int g = 0; g < groups.length(); g++) {
            JSONArray clients = groups.getJSONObject(g).getJSONArray("clients");
            for (int c = 0; c < clients.length(); c++) {
                if (id.equals(clients.getJSONObject(c).getString("id"))) {
                    return clients.getJSONObject(c);
                }
            }
        }
        throw new AssertionError("no such client in the fixture: " + id);
    }

    private static JSONObject stream(JSONObject payload, String id) throws JSONException {
        return findById(payload.getJSONObject("server").getJSONArray("streams"), id);
    }

    /** Applies a notification fixture to {@code before} and expects the state of {@code fresh}. */
    private void assertNotificationGivesFreshState(String fixture, JSONObject before, JSONObject fresh) throws Exception {
        JSONObject notification = SnapcastFixtures.json(fixture);
        updater.setStatus(parse(before));

        SnapcastServerStatus result = updater.apply(notification.getString("method"), notification.getJSONObject("params"));

        assertEquals(fixture, parse(fresh), result);
        assertEquals("the updater holds the result", result, updater.getStatus());
        assertEquals("a consistent patch needs no re-read: " + fixture, 0, reReads);
    }

    // ---- the notifications of the fixtures

    @Test
    public void clientOnConnectReplacesTheClient() throws Exception {
        JSONObject before = baseline();
        client(before, CLIENT_1).put("connected", false).getJSONObject("config").put("latency", 99)
                .getJSONObject("volume").put("percent", 3);

        assertNotificationGivesFreshState("notification_client_on_connect.json", before, baseline());
    }

    @Test
    public void clientOnDisconnectMarksTheClientOffline() throws Exception {
        JSONObject fresh = baseline();
        client(fresh, CLIENT_1).put("connected", false);

        assertNotificationGivesFreshState("notification_client_on_disconnect.json", baseline(), fresh);
    }

    @Test
    public void clientOnVolumeChanged() throws Exception {
        JSONObject fresh = baseline();
        client(fresh, CLIENT_1).getJSONObject("config").getJSONObject("volume").put("percent", 74);

        assertNotificationGivesFreshState("notification_client_on_volume_changed.json", baseline(), fresh);
    }

    @Test
    public void clientOnLatencyChanged() throws Exception {
        JSONObject fresh = baseline();
        client(fresh, CLIENT_1).getJSONObject("config").put("latency", 10);

        assertNotificationGivesFreshState("notification_client_on_latency_changed.json", baseline(), fresh);
    }

    @Test
    public void clientOnNameChanged() throws Exception {
        JSONObject fresh = baseline();
        client(fresh, CLIENT_1).getJSONObject("config").put("name", "Laptop");

        assertNotificationGivesFreshState("notification_client_on_name_changed.json", baseline(), fresh);
    }

    @Test
    public void groupOnMute() throws Exception {
        JSONObject fresh = baseline();
        group(fresh, GROUP_1).put("muted", true);

        assertNotificationGivesFreshState("notification_group_on_mute.json", baseline(), fresh);
    }

    @Test
    public void groupOnStreamChanged() throws Exception {
        JSONObject fresh = baseline();
        group(fresh, GROUP_1).put("stream_id", "Default");

        assertNotificationGivesFreshState("notification_group_on_stream_changed.json", baseline(), fresh);
    }

    @Test
    public void groupOnNameChanged() throws Exception {
        JSONObject before = baseline();
        group(before, GROUP_1).put("name", "something else");

        assertNotificationGivesFreshState("notification_group_on_name_changed.json", before, baseline());
    }

    @Test
    public void streamOnUpdate() throws Exception {
        JSONObject fresh = baseline();
        stream(fresh, "Default").put("status", "playing");

        assertNotificationGivesFreshState("notification_stream_on_update.json", baseline(), fresh);
    }

    @Test
    public void serverOnUpdateReplacesEverything() throws Exception {
        updater.setStatus(parse(baseline()));
        JSONObject notification = SnapcastFixtures.json("notification_server_on_update.json");

        SnapcastServerStatus result = updater.apply("Server.OnUpdate", notification.getJSONObject("params"));

        assertEquals(SnapcastFixtures.status("notification_server_on_update.json"), result);
        assertEquals(1, result.getGroups().size());
        assertEquals(0, reReads);
    }

    @Test
    public void aBatchOfNotificationsIsAppliedOneByOne() throws Exception {
        updater.setStatus(parse(baseline()));
        List<SnapcastMessage> batch = SnapcastMessage.parseLine(SnapcastFixtures.read("batch_notifications.ndjson").trim());

        for (SnapcastMessage message : batch) {
            updater.apply(message.getMethod(), message.getParams());
        }

        assertEquals(10, updater.getStatus().findClient(CLIENT_1).getVolume().getPercent());
        assertEquals(10, updater.getStatus().findClient("02:00:00:00:00:02").getVolume().getPercent());
        assertEquals(0, reReads);
    }

    @Test
    public void everyFixtureNotificationIsCoveredByATestAbove() {
        // the list of fixtures and the methods the updater knows must stay in step
        String[] fixtures = {"client_on_connect", "client_on_disconnect", "client_on_volume_changed",
                "client_on_latency_changed", "client_on_name_changed", "group_on_mute", "group_on_stream_changed",
                "group_on_name_changed", "stream_on_update", "server_on_update"};
        for (String fixture : fixtures) {
            JSONObject notification = SnapcastFixtures.json("notification_" + fixture + ".json");
            assertTrue(fixture, SnapcastModelUpdater.isKnown(notification.optString("method")));
        }
    }

    @Test
    public void theOriginalStatusIsNeverModified() throws Exception {
        SnapcastServerStatus original = parse(baseline());
        updater.setStatus(original);
        JSONObject notification = SnapcastFixtures.json("notification_client_on_volume_changed.json");

        updater.apply("Client.OnVolumeChanged", notification.getJSONObject("params"));

        assertEquals(70, original.findClient(CLIENT_1).getVolume().getPercent());
        assertEquals(74, updater.getStatus().findClient(CLIENT_1).getVolume().getPercent());
    }

    @Test
    public void valuesOfNotificationsAreClampedLikeThoseOfAStatus() throws Exception {
        updater.setStatus(parse(baseline()));

        updater.apply("Client.OnVolumeChanged", new JSONObject()
                .put("id", CLIENT_1).put("volume", new JSONObject().put("percent", 9999).put("muted", true)));
        updater.apply("Client.OnLatencyChanged", new JSONObject().put("id", CLIENT_1).put("latency", -7));

        assertEquals(100, updater.getStatus().findClient(CLIENT_1).getVolume().getPercent());
        assertTrue(updater.getStatus().findClient(CLIENT_1).getVolume().isMuted());
        assertEquals(0, updater.getStatus().findClient(CLIENT_1).getLatencyMs());
    }

    // ---- doubt: re-read

    @Test
    public void anUnknownNotificationRequestsAFullReRead() throws Exception {
        updater.setStatus(parse(baseline()));
        SnapcastServerStatus before = updater.getStatus();

        SnapcastServerStatus result = updater.apply("Something.OnNew", new JSONObject().put("id", "x"));

        assertEquals(1, reReads);
        assertSame("the model stays as it was until the fresh status arrives", before, result);
    }

    @Test
    public void streamOnPropertiesIsKnownAndNeedsNoReRead() throws Exception {
        updater.setStatus(parse(baseline()));

        updater.apply("Stream.OnProperties", new JSONObject().put("id", "Default").put("metadata", new JSONObject()));

        assertEquals(0, reReads);
    }

    @Test
    public void anUnknownClientRequestsAReRead() throws Exception {
        updater.setStatus(parse(baseline()));

        updater.apply("Client.OnVolumeChanged", new JSONObject().put("id", "nope")
                .put("volume", new JSONObject().put("percent", 5).put("muted", false)));

        assertEquals(1, reReads);
    }

    @Test
    public void anUnknownGroupRequestsAReRead() throws Exception {
        updater.setStatus(parse(baseline()));

        updater.apply("Group.OnMute", new JSONObject().put("id", "nope").put("mute", true));

        assertEquals(1, reReads);
    }

    @Test
    public void aNewClientThatIsNotInTheModelRequestsAReRead() throws Exception {
        JSONObject before = baseline();
        JSONObject notification = SnapcastFixtures.json("notification_client_on_connect.json");
        client(before, CLIENT_1).put("id", "02:00:00:00:00:99");
        updater.setStatus(parse(before));

        updater.apply("Client.OnConnect", notification.getJSONObject("params"));

        assertEquals("the new client may have got a group of its own: ask the server", 1, reReads);
    }

    @Test
    public void anUnknownStreamRequestsAReRead() throws Exception {
        updater.setStatus(parse(baseline()));
        JSONObject params = SnapcastFixtures.json("notification_stream_on_update.json").getJSONObject("params");
        params.getJSONObject("stream").put("id", "other");
        params.put("id", "other");

        updater.apply("Stream.OnUpdate", params);

        assertEquals(1, reReads);
    }

    @Test
    public void malformedParamsRequestAReRead() throws Exception {
        updater.setStatus(parse(baseline()));

        updater.apply("Client.OnVolumeChanged", new JSONObject());
        now += 1_000;
        updater.apply("Group.OnMute", null);
        now += 1_000;
        updater.apply("Server.OnUpdate", new JSONObject().put("server", "text"));
        now += 1_000;
        updater.apply(null, new JSONObject());

        assertEquals(4, reReads);
    }

    @Test
    public void withoutAStatusYetTheFirstDoubtAsksForOne() throws Exception {
        assertNull(updater.getStatus());

        updater.apply("Client.OnVolumeChanged", new JSONObject().put("id", CLIENT_1)
                .put("volume", new JSONObject().put("percent", 5).put("muted", false)));

        assertEquals(1, reReads);
        assertNull(updater.getStatus());
    }

    @Test
    public void withoutAStatusAServerUpdateIsTakenAsTheStatus() throws Exception {
        JSONObject notification = SnapcastFixtures.json("notification_server_on_update.json");

        updater.apply("Server.OnUpdate", notification.getJSONObject("params"));

        assertEquals(SnapcastFixtures.status("notification_server_on_update.json"), updater.getStatus());
        assertEquals(0, reReads);
    }

    @Test
    public void lostNotificationsRequestAReRead() {
        updater.onNotificationsLost();

        assertEquals(1, reReads);
    }

    // ---- rate limit

    private void doubt() throws Exception {
        updater.apply("Unknown.Thing", new JSONObject());
    }

    @Test
    public void atMostOneReReadPerSecond() throws Exception {
        updater.setStatus(parse(baseline()));

        doubt();
        now = 500;
        doubt();
        now = 999;
        doubt();
        assertEquals(1, reReads);
        now = 1_000;
        doubt();

        assertEquals(2, reReads);
    }

    @Test
    public void atMostTenReReadsPerMinute() throws Exception {
        updater.setStatus(parse(baseline()));

        for (int i = 0; i < 10; i++) {
            now = i * 1_000L;
            doubt();
        }
        assertEquals(10, reReads);
        now = 10_000;
        doubt();
        now = 30_000;
        doubt();
        now = 59_999;
        doubt();
        assertEquals("the eleventh within a minute is held back", 10, reReads);
        now = 60_000;
        doubt();
        assertEquals("the first of the ten has left the minute", 11, reReads);
    }

    @Test
    public void aHeldBackReReadIsDeliveredByPollWhenAllowed() throws Exception {
        updater.setStatus(parse(baseline()));
        doubt();
        now = 100;
        doubt(); // held back (1 per second)
        assertEquals(1, reReads);

        assertFalse("too early", updater.poll());
        now = 1_000;
        assertTrue(updater.poll());
        assertEquals(2, reReads);
        now = 5_000;
        assertFalse("nothing is waiting any more", updater.poll());
        assertEquals(2, reReads);
    }

    @Test
    public void aFreshStatusClearsAHeldBackReRead() throws Exception {
        updater.setStatus(parse(baseline()));
        doubt();
        now = 100;
        doubt(); // held back

        updater.setStatus(parse(baseline()));
        now = 5_000;

        assertFalse(updater.poll());
        assertEquals(1, reReads);
    }

    @Test
    public void aFloodOfUnknownNotificationsAsksAtMostTenTimesAMinute() throws Exception {
        updater.setStatus(parse(baseline()));

        for (int i = 0; i < 100_000; i++) {
            now = i; // 100 seconds in total, a flood of 1000 per second
            doubt();
        }

        assertTrue("re-reads: " + reReads, reReads <= 20);
    }
}
