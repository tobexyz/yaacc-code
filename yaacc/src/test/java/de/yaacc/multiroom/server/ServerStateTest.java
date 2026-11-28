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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The state of the built-in Snapcast server (issue #330, task 3.1): clients, groups and streams with the rules of snapserver, the status
 * JSON read back by the app's own model parser, and the notifications that a change makes.
 */
public class ServerStateTest {

    private final AtomicInteger groupCounter = new AtomicInteger();
    private final ServerState state = new ServerState("Phone", "0.35.0-yaacc", 1000, () -> "group-" + groupCounter.incrementAndGet(),
            () -> 1_700_000_000_000L);

    private static HelloMessage hello(String id) {
        return HelloMessage.create(id, "host-" + id, "Snapclient", "Linux", "x86_64", "0.35.0");
    }

    private SnapcastServerStatus status() throws Exception {
        return SnapcastServerStatus.fromResult(state.status());
    }

    private static String method(List<JSONObject> notifications, int index) throws Exception {
        return notifications.get(index).getString("method");
    }

    private static void rejects(int code, Runnable action) {
        try {
            action.run();
            fail("expected error " + code);
        } catch (ServerState.StateException e) {
            assertEquals(code, e.code());
        }
    }

    @Test
    public void anEmptyServerHasAStatusThatTheAppParses() throws Exception {
        SnapcastServerStatus s = status();
        assertEquals(0, s.getGroups().size());
        assertEquals(0, s.getStreams().size());
        assertEquals("Phone", s.getInfo().getHostName());
        assertEquals("0.35.0-yaacc", s.getInfo().getVersion());
    }

    @Test
    public void aStreamIsAddedAndAppearsWithItsUriAndStatus() throws Exception {
        List<JSONObject> n = state.addStream("YAACC one", "tcp://0.0.0.0:4953?name=YAACC%20one&sampleformat=48000:16:2");
        assertEquals("Server.OnUpdate", method(n, 0));
        assertEquals(1, status().getStreams().size());
        assertEquals("YAACC one", status().getStreams().get(0).getId());
        assertEquals("idle", status().getStreams().get(0).getStatus());
        assertEquals(4953, status().getStreams().get(0).getPort());
    }

    @Test
    public void aSecondStreamWithTheSameNameIsRefused() {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        rejects(-32603, () -> state.addStream("a", "tcp://0.0.0.0:4954?name=a"));
    }

    @Test
    public void atMostTenStreams() {
        for (int i = 0; i < 10; i++) {
            state.addStream("s" + i, "tcp://0.0.0.0:" + (4953 + i) + "?name=s" + i);
        }
        rejects(-32603, () -> state.addStream("s10", "tcp://0.0.0.0:4963?name=s10"));
    }

    @Test
    public void aStatusChangeOfAStreamIsAnUpdateOfThatStream() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        List<JSONObject> n = state.setStreamStatus("a", "playing");
        assertEquals("Stream.OnUpdate", method(n, 0));
        assertEquals("a", n.get(0).getJSONObject("params").getString("id"));
        assertEquals("playing", n.get(0).getJSONObject("params").getJSONObject("stream").getString("status"));
        assertEquals("nothing changed, nothing told", 0, state.setStreamStatus("a", "playing").size());
    }

    @Test
    public void aNewClientGetsAGroupOfItsOwnOnTheFirstStream() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.addStream("b", "tcp://0.0.0.0:4954?name=b");
        List<JSONObject> n = state.clientConnected(hello("c1"), "192.168.1.5");
        assertEquals("Client.OnConnect", method(n, 0));
        SnapcastServerStatus s = status();
        assertEquals(1, s.getGroups().size());
        SnapcastGroup g = s.getGroups().get(0);
        assertEquals("group-1", g.getId());
        assertEquals("a", g.getStreamId());
        SnapcastClient c = s.findClient("c1");
        assertNotNull(c);
        assertTrue(c.isConnected());
        assertEquals(100, c.getVolume().getPercent());
        assertFalse(c.getVolume().isMuted());
        assertEquals(0, c.getLatencyMs());
    }

    @Test
    public void aSecondClientHasItsOwnGroup() throws Exception {
        state.clientConnected(hello("c1"), "10.0.0.1");
        state.clientConnected(hello("c2"), "10.0.0.2");
        assertEquals(2, status().getGroups().size());
    }

    @Test
    public void aClientThatComesBackKeepsItsGroupAndItsSettings() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.clientConnected(hello("c1"), "10.0.0.1");
        state.setClientVolume("c1", 35, true);
        state.setClientLatency("c1", 120);
        state.setClientName("c1", "Kitchen");
        List<JSONObject> gone = state.clientDisconnected("c1");
        assertEquals("Client.OnDisconnect", method(gone, 0));
        assertFalse(status().findClient("c1").isConnected());
        state.clientConnected(hello("c1"), "10.0.0.9");
        SnapcastClient c = status().findClient("c1");
        assertTrue(c.isConnected());
        assertEquals(35, c.getVolume().getPercent());
        assertTrue(c.getVolume().isMuted());
        assertEquals(120, c.getLatencyMs());
        assertEquals("Kitchen", c.getName());
        assertEquals("one group, not a second", 1, status().getGroups().size());
    }

    @Test
    public void theSettingsOfAClientCombineItsOwnAndTheGroupsMute() throws Exception {
        state.clientConnected(hello("c1"), "10.0.0.1");
        state.setClientVolume("c1", 40, false);
        state.setClientLatency("c1", 25);
        ServerSettingsMessage s = state.settingsOf("c1");
        assertEquals(1000, s.getBufferMs());
        assertEquals(25, s.getLatencyMs());
        assertEquals(40, s.getVolume());
        assertFalse(s.isMuted());
        state.setGroupMute(state.groupIdOf("c1"), true);
        assertTrue("a muted group mutes its clients", state.settingsOf("c1").isMuted());
    }

    @Test
    public void aVolumeOutsideZeroToHundredIsInvalid() {
        state.clientConnected(hello("c1"), "10.0.0.1");
        rejects(-32602, () -> state.setClientVolume("c1", 101, false));
        rejects(-32602, () -> state.setClientVolume("c1", -1, false));
        rejects(-32602, () -> state.setClientVolume("nobody", 50, false));
    }

    @Test
    public void aVolumeChangeIsTold() throws Exception {
        state.clientConnected(hello("c1"), "10.0.0.1");
        List<JSONObject> n = state.setClientVolume("c1", 55, false);
        JSONObject p = n.get(0).getJSONObject("params");
        assertEquals("Client.OnVolumeChanged", method(n, 0));
        assertEquals("c1", p.getString("id"));
        assertEquals(55, p.getJSONObject("volume").getInt("percent"));
        assertEquals(0, state.setClientVolume("c1", 55, false).size());
    }

    @Test
    public void theClientItselfCanChangeItsVolumeAndMute() throws Exception {
        state.clientConnected(hello("c1"), "10.0.0.1");
        List<JSONObject> n = state.clientInfo("c1", 20, true);
        assertEquals("Client.OnVolumeChanged", method(n, 0));
        assertEquals(20, status().findClient("c1").getVolume().getPercent());
        assertTrue(status().findClient("c1").getVolume().isMuted());
    }

    @Test
    public void aGroupIsMutedRenamedAndMovedToAStream() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.addStream("b", "tcp://0.0.0.0:4954?name=b");
        state.clientConnected(hello("c1"), "10.0.0.1");
        String g = state.groupIdOf("c1");
        assertEquals("Group.OnMute", method(state.setGroupMute(g, true), 0));
        assertEquals("nothing changed, nothing told", 0, state.setGroupMute(g, true).size());
        assertEquals("Group.OnNameChanged", method(state.setGroupName(g, "Living"), 0));
        List<JSONObject> n = state.setGroupStream(g, "b");
        assertEquals("Group.OnStreamChanged", method(n, 0));
        assertEquals("b", n.get(0).getJSONObject("params").getString("stream_id"));
        SnapcastGroup group = status().findGroup(g);
        assertTrue(group.isMuted());
        assertEquals("Living", group.getName());
        assertEquals("b", group.getStreamId());
    }

    @Test
    public void aStreamThatDoesNotExistIsNotAChoice() {
        state.clientConnected(hello("c1"), "10.0.0.1");
        rejects(-32602, () -> state.setGroupStream(state.groupIdOf("c1"), "nope"));
        rejects(-32602, () -> state.setGroupStream("no group", "nope"));
    }

    @Test
    public void setClientsSetsTheExactMembershipAndTheOthersGetAGroupOfTheirOwn() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.clientConnected(hello("c1"), "10.0.0.1");
        state.clientConnected(hello("c2"), "10.0.0.2");
        state.clientConnected(hello("c3"), "10.0.0.3");
        String g1 = state.groupIdOf("c1");
        List<JSONObject> n = state.setGroupClients(g1, Arrays.asList("c1", "c2", "c3"));
        assertEquals("Server.OnUpdate", method(n, 0));
        assertEquals("the empty groups are gone", 1, status().getGroups().size());
        assertEquals(3, status().findGroup(g1).getClients().size());
        state.setGroupClients(g1, Arrays.asList("c2"));
        SnapcastServerStatus s = status();
        assertEquals("c2 stays, c1 and c3 each get a group of their own", 3, s.getGroups().size());
        assertEquals(g1, s.findGroupOfClient("c2").getId());
        assertEquals("a", s.findGroupOfClient("c1").getStreamId());
        assertEquals("a", s.findGroupOfClient("c3").getStreamId());
        assertFalse("each left alone in a group of its own", s.findGroupOfClient("c1").getId().equals(s.findGroupOfClient("c3").getId()));
    }

    @Test
    public void setClientsRefusesAClientThatIsUnknown() {
        state.clientConnected(hello("c1"), "10.0.0.1");
        rejects(-32602, () -> state.setGroupClients(state.groupIdOf("c1"), Arrays.asList("c1", "ghost")));
    }

    @Test
    public void aClientCanOnlyBeDeletedWhileItIsGone() throws Exception {
        state.clientConnected(hello("c1"), "10.0.0.1");
        state.clientConnected(hello("c2"), "10.0.0.2");
        rejects(-32602, () -> state.deleteClient("c1"));
        state.clientDisconnected("c1");
        List<JSONObject> n = state.deleteClient("c1");
        assertEquals("Server.OnUpdate", method(n, 0));
        assertNull(status().findClient("c1"));
        assertEquals("its empty group went with it", 1, status().getGroups().size());
        rejects(-32602, () -> state.deleteClient("c1"));
    }

    @Test
    public void aStreamThatIsRemovedLeavesItsGroupsWithoutAStream() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.clientConnected(hello("c1"), "10.0.0.1");
        List<JSONObject> n = state.removeStream("a");
        assertEquals("Server.OnUpdate", method(n, 0));
        assertEquals("", status().findGroup(state.groupIdOf("c1")).getStreamId());
        assertEquals(0, status().getStreams().size());
        rejects(-32602, () -> state.removeStream("a"));
    }

    @Test
    public void theClientsOfAStreamAreTheConnectedOnesOfItsGroups() throws Exception {
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.addStream("b", "tcp://0.0.0.0:4954?name=b");
        state.clientConnected(hello("c1"), "10.0.0.1");
        state.clientConnected(hello("c2"), "10.0.0.2");
        state.setGroupStream(state.groupIdOf("c2"), "b");
        state.clientDisconnected("c1");
        assertEquals(Arrays.asList(), state.connectedClientsOfStream("a"));
        assertEquals(Arrays.asList("c2"), state.connectedClientsOfStream("b"));
        assertEquals("b", state.streamIdOf("c2"));
    }

    @Test
    public void theServerIsBusyOnlyWhileAStreamPlaysForAConnectedClient() throws Exception {
        assertFalse(state.hasPlayingStreamWithClients());
        state.addStream("a", "tcp://0.0.0.0:4953?name=a");
        state.clientConnected(hello("c1"), "10.0.0.1");
        assertFalse("a stream that is idle is no work", state.hasPlayingStreamWithClients());
        state.setStreamStatus("a", "playing");
        assertTrue(state.hasPlayingStreamWithClients());
        assertEquals(1, state.connectedClientCount());
        state.clientDisconnected("c1");
        assertFalse("nobody to play for", state.hasPlayingStreamWithClients());
        assertEquals(0, state.connectedClientCount());
    }

    @Test
    public void aSecondInstanceOfAClientHasItsOwnId() throws Exception {
        HelloMessage first = hello("mac");
        state.clientConnected(first, "10.0.0.1");
        assertNotNull(state.settingsOf("mac"));
        assertEquals("mac", state.clientIdOf(first));
    }

    @Test
    public void atMostSixtyFourClients() {
        for (int i = 0; i < 64; i++) {
            state.clientConnected(hello("c" + i), "10.0.0." + (i % 250));
        }
        rejects(-32603, () -> state.clientConnected(hello("one too many"), "10.0.1.1"));
    }
}
