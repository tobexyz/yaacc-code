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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.protocol.HelloMessage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** What survives a restart (issue #330, requirement R8): the clients with their settings and the groups, not the streams. */
public class ServerStoreTest {

    private final AtomicInteger groups = new AtomicInteger();

    private ServerState fresh() {
        return new ServerState("Phone", "0.35.0-yaacc", 1000, () -> "g" + groups.incrementAndGet(), () -> 1_700_000_000_000L);
    }

    private static HelloMessage hello(String id) {
        return HelloMessage.create(id, "host-" + id, "YAACC", "Android", "arm", "1");
    }

    @Test
    public void clientsAndGroupsComeBackAfterARestartAsDisconnected() throws Exception {
        ServerState before = fresh();
        before.addStream("a", "tcp://0.0.0.0:4953?name=a");
        before.clientConnected(hello("c1"), "10.0.0.1");
        before.clientConnected(hello("c2"), "10.0.0.2");
        before.setClientVolume("c1", 35, true);
        before.setClientLatency("c1", 120);
        before.setClientName("c1", "Kitchen");
        String g = before.groupIdOf("c1");
        before.setGroupClients(g, java.util.Arrays.asList("c1", "c2"));
        before.setGroupMute(g, true);
        before.setGroupName(g, "Downstairs");

        ServerState after = fresh();
        after.addStream("a", "tcp://0.0.0.0:4953?name=a");
        after.restore(before.snapshot());

        SnapcastServerStatus s = SnapcastServerStatus.fromResult(after.status());
        assertEquals(1, s.getGroups().size());
        assertEquals("Downstairs", s.getGroups().get(0).getName());
        assertTrue(s.getGroups().get(0).isMuted());
        assertEquals("a", s.getGroups().get(0).getStreamId());
        SnapcastClient c1 = s.findClient("c1");
        assertFalse("nobody is connected after a restart", c1.isConnected());
        assertEquals(35, c1.getVolume().getPercent());
        assertTrue(c1.getVolume().isMuted());
        assertEquals(120, c1.getLatencyMs());
        assertEquals("Kitchen", c1.getName());
        assertEquals(2, s.getGroups().get(0).getClients().size());
    }

    @Test
    public void aClientThatComesBackAfterARestartIsInItsOldGroup() throws Exception {
        ServerState before = fresh();
        before.clientConnected(hello("c1"), "10.0.0.1");
        String g = before.groupIdOf("c1");
        ServerState after = fresh();
        after.restore(before.snapshot());
        after.clientConnected(hello("c1"), "10.0.0.7");
        assertEquals(g, after.groupIdOf("c1"));
        assertEquals(1, SnapcastServerStatus.fromResult(after.status()).getGroups().size());
    }

    @Test
    public void theStreamsAreNotPartOfTheSnapshot() throws Exception {
        ServerState before = fresh();
        before.addStream("a", "tcp://0.0.0.0:4953?name=a");
        assertFalse(before.snapshot().toString().contains("4953"));
        ServerState after = fresh();
        after.restore(before.snapshot());
        assertEquals(0, after.streamIds().size());
    }

    @Test
    public void aGroupThatPointedToAStreamFindsItAgainWhenTheStreamIsAdded() throws Exception {
        ServerState before = fresh();
        before.addStream("a", "tcp://0.0.0.0:4953?name=a");
        before.clientConnected(hello("c1"), "10.0.0.1");
        ServerState after = fresh();
        after.restore(before.snapshot());
        assertEquals("a", after.streamIdOf("c1"));
        after.addStream("a", "tcp://0.0.0.0:4953?name=a");
        assertEquals(java.util.Arrays.asList(), after.connectedClientsOfStream("a"));
        after.clientConnected(hello("c1"), "10.0.0.1");
        assertEquals(java.util.Arrays.asList("c1"), after.connectedClientsOfStream("a"));
    }

    @Test
    public void aDamagedSnapshotIsIgnoredAndTheServerStartsEmpty() throws Exception {
        ServerState s = fresh();
        s.restore(new JSONObject("{\"clients\":5,\"groups\":\"x\"}"));
        s.restore(new JSONObject("{}"));
        s.restore(null);
        assertEquals(0, SnapcastServerStatus.fromResult(s.status()).getGroups().size());
    }

    @Test
    public void entriesBeyondTheLimitsAndBadOnesAreDropped() throws Exception {
        JSONArray clients = new JSONArray();
        JSONArray members = new JSONArray();
        for (int i = 0; i < 100; i++) {
            JSONObject c = new JSONObject();
            c.put("id", "c" + i);
            c.put("name", "n".repeat(500));
            c.put("volume", 900);
            c.put("muted", false);
            c.put("latency", -5);
            clients.put(c);
            members.put("c" + i);
        }
        clients.put("garbage");
        clients.put(new JSONObject("{\"volume\":3}")); // no id
        JSONArray groupsJson = new JSONArray();
        groupsJson.put(new JSONObject().put("id", "gx").put("name", "G").put("muted", false).put("stream_id", "").put("clients", members));
        JSONObject snapshot = new JSONObject().put("clients", clients).put("groups", groupsJson);
        ServerState s = fresh();
        s.restore(snapshot);
        SnapcastServerStatus status = SnapcastServerStatus.fromResult(s.status());
        assertEquals("at most 64 clients", 64, status.getGroups().get(0).getClients().size());
        SnapcastClient c = status.findClient("c0");
        assertEquals("a name is cut to 64 characters", 64, c.getName().length());
        assertEquals("a volume is held to 0 to 100", 100, c.getVolume().getPercent());
        assertEquals("a latency below 0 is 0", 0, c.getLatencyMs());
        assertNull(status.findClient("c99"));
    }

    @Test
    public void theStoreWritesAfterAChangeAtMostOncePerSecondAndReadsWhatItWrote() throws Exception {
        Map<String, String> map = new HashMap<>();
        AtomicLong now = new AtomicLong(1_000L);
        ServerStore store = new ServerStore(new ServerStore.Backing() {
            @Override
            public String read() {
                return map.get("k");
            }

            @Override
            public void write(String text) {
                map.put("k", text);
                map.put("writes", String.valueOf(Integer.parseInt(map.getOrDefault("writes", "0")) + 1));
            }
        }, now::get);
        ServerState s = fresh();
        s.clientConnected(hello("c1"), "10.0.0.1");
        store.changed(s);
        assertEquals("1", map.get("writes"));
        s.setClientName("c1", "A");
        store.changed(s);
        now.addAndGet(300);
        s.setClientName("c1", "B");
        store.changed(s);
        assertEquals("a change within a second waits", "1", map.get("writes"));
        now.addAndGet(1_000);
        store.changed(s);
        assertEquals("2", map.get("writes"));
        ServerState again = fresh();
        store.restoreInto(again);
        assertEquals("B", SnapcastServerStatus.fromResult(again.status()).findClient("c1").getName());
    }

    @Test
    public void aStoreThatHoldsGarbageRestoresNothing() throws Exception {
        ServerStore store = new ServerStore(new ServerStore.Backing() {
            @Override
            public String read() {
                return "not json at all";
            }

            @Override
            public void write(String text) {
            }
        }, () -> 0L);
        ServerState s = fresh();
        store.restoreInto(s);
        assertEquals(0, SnapcastServerStatus.fromResult(s.status()).getGroups().size());
    }
}
