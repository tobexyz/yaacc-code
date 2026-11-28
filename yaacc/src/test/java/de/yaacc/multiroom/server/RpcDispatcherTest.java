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

import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MicroClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The JSON-RPC methods of the control port (issue #330, task 3.2): answers, errors and the notifications a request makes. */
public class RpcDispatcherTest {

    private final AtomicInteger groups = new AtomicInteger();
    private final ServerState state = new ServerState("Phone", "0.35.0-yaacc", 1000, () -> "g" + groups.incrementAndGet(),
            System::currentTimeMillis);
    private final SnapcastServer server = new SnapcastServer(state, MicroClock.monotonic(), a -> true, 16, n -> { });
    private final List<String> opened = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private boolean failToOpen;
    private final RpcDispatcher dispatcher;

    public RpcDispatcherTest() {
        server.setSourceFactory((pipeline, spec) -> {
            if (failToOpen) {
                throw new IOException("port taken");
            }
            opened.add(spec.name());
            return new Closeable() {
                @Override
                public void close() {
                    closed.add(spec.name());
                }
            };
        });
        dispatcher = new RpcDispatcher(server);
    }

    private RpcDispatcher.Outcome call(String method, String params) throws Exception {
        return dispatcher.handle(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}"));
    }

    private void client(String id) {
        state.clientConnected(HelloMessage.create(id, "h", "YAACC", "Android", "arm", "1"), "10.0.0.1");
    }

    @Test
    public void theVersionOfTheControlProtocol() throws Exception {
        RpcDispatcher.Outcome o = call("Server.GetRPCVersion", "{}");
        assertEquals(7, o.response.getInt("id"));
        assertEquals(2, o.response.getJSONObject("result").getInt("major"));
    }

    @Test
    public void theStatusIsTheStatusOfTheState() throws Exception {
        client("c1");
        JSONObject result = call("Server.GetStatus", "{}").response.getJSONObject("result");
        assertEquals("c1", result.getJSONObject("server").getJSONArray("groups").getJSONObject(0).getJSONArray("clients").getJSONObject(0)
                .getString("id"));
    }

    @Test
    public void aVolumeIsSetAnsweredAndTold() throws Exception {
        client("c1");
        RpcDispatcher.Outcome o = call("Client.SetVolume", "{\"id\":\"c1\",\"volume\":{\"muted\":true,\"percent\":42}}");
        assertEquals(42, o.response.getJSONObject("result").getJSONObject("volume").getInt("percent"));
        assertEquals("Client.OnVolumeChanged", o.notifications.get(0).getString("method"));
        assertEquals(42, state.settingsOf("c1").getVolume());
    }

    @Test
    public void latencyNameAndGroupSettingsAreAnswered() throws Exception {
        client("c1");
        assertEquals(30, call("Client.SetLatency", "{\"id\":\"c1\",\"latency\":30}").response.getJSONObject("result").getInt("latency"));
        assertEquals("Kitchen", call("Client.SetName", "{\"id\":\"c1\",\"name\":\"Kitchen\"}").response.getJSONObject("result").getString("name"));
        String g = state.groupIdOf("c1");
        assertEquals(true, call("Group.SetMute", "{\"id\":\"" + g + "\",\"mute\":true}").response.getJSONObject("result").getBoolean("mute"));
        assertEquals("Living", call("Group.SetName", "{\"id\":\"" + g + "\",\"name\":\"Living\"}").response.getJSONObject("result").getString("name"));
    }

    @Test
    public void aGroupIsMovedToAStreamAndItsClientsAreSet() throws Exception {
        call("Stream.AddStream", "{\"streamUri\":\"tcp://0.0.0.0:4953?name=a\"}");
        call("Stream.AddStream", "{\"streamUri\":\"tcp://0.0.0.0:4954?name=b\"}");
        client("c1");
        client("c2");
        String g = state.groupIdOf("c1");
        assertEquals("b", call("Group.SetStream", "{\"id\":\"" + g + "\",\"stream_id\":\"b\"}").response.getJSONObject("result").getString("stream_id"));
        RpcDispatcher.Outcome o = call("Group.SetClients", "{\"id\":\"" + g + "\",\"clients\":[\"c1\",\"c2\"]}");
        assertEquals(1, o.response.getJSONObject("result").getJSONObject("server").getJSONArray("groups").length());
        assertEquals("Server.OnUpdate", o.notifications.get(0).getString("method"));
    }

    @Test
    public void aTcpStreamIsAddedAndItsSourceOpened() throws Exception {
        RpcDispatcher.Outcome o = call("Stream.AddStream", "{\"streamUri\":\"tcp://0.0.0.0:4953?name=YAACC%20one\"}");
        assertEquals("YAACC one", o.response.getJSONObject("result").getString("stream_id"));
        assertEquals("YAACC one", o.response.getJSONObject("result").getString("id"));
        assertEquals("Server.OnUpdate", o.notifications.get(0).getString("method"));
        assertEquals(1, opened.size());
        assertTrue(state.hasStream("YAACC one"));
    }

    @Test
    public void aStreamThatIsNotTcpIsRefusedAndNothingIsOpened() throws Exception {
        RpcDispatcher.Outcome o = call("Stream.AddStream", "{\"streamUri\":\"process:///bin/sh?name=x\"}");
        assertEquals(-32602, o.response.getJSONObject("error").getInt("code"));
        assertTrue(o.response.getJSONObject("error").getString("data").length() > 0);
        assertEquals(0, opened.size());
        assertEquals(0, state.streamIds().size());
    }

    @Test
    public void aSourceThatCanNotBeOpenedLeavesNoStreamBehind() throws Exception {
        failToOpen = true;
        RpcDispatcher.Outcome o = call("Stream.AddStream", "{\"streamUri\":\"tcp://0.0.0.0:4953?name=a\"}");
        assertEquals(-32603, o.response.getJSONObject("error").getInt("code"));
        assertFalse(state.hasStream("a"));
    }

    @Test
    public void aStreamIsRemovedAndItsSourceClosed() throws Exception {
        call("Stream.AddStream", "{\"streamUri\":\"tcp://0.0.0.0:4953?name=a\"}");
        RpcDispatcher.Outcome o = call("Stream.RemoveStream", "{\"id\":\"a\"}");
        assertEquals("a", o.response.getJSONObject("result").getString("id"));
        assertEquals(1, closed.size());
        assertFalse(state.hasStream("a"));
    }

    @Test
    public void aClientThatIsGoneIsDeleted() throws Exception {
        client("c1");
        state.clientDisconnected("c1");
        RpcDispatcher.Outcome o = call("Server.DeleteClient", "{\"id\":\"c1\"}");
        assertTrue(o.response.getJSONObject("result").has("server"));
        assertFalse(state.knowsClient("c1"));
    }

    @Test
    public void aMethodThatDoesNotExistIsNotFound() throws Exception {
        assertEquals(-32601, call("Server.Explode", "{}").response.getJSONObject("error").getInt("code"));
    }

    @Test
    public void paramsOfTheWrongKindAreInvalid() throws Exception {
        client("c1");
        assertEquals(-32602, call("Client.SetVolume", "{\"id\":\"c1\"}").response.getJSONObject("error").getInt("code"));
        assertEquals(-32602, call("Client.SetVolume", "{\"id\":5,\"volume\":{\"muted\":false,\"percent\":1}}").response.getJSONObject("error").getInt("code"));
        assertEquals(-32602, call("Client.SetVolume", "{\"id\":\"c1\",\"volume\":{\"muted\":false,\"percent\":900}}").response.getJSONObject("error").getInt("code"));
        assertEquals(-32602, call("Group.SetClients", "{\"id\":\"x\",\"clients\":\"c1\"}").response.getJSONObject("error").getInt("code"));
        assertEquals(-32602, call("Client.SetVolume", "[1,2]").response.getJSONObject("error").getInt("code"));
    }

    @Test
    public void aRequestWithoutAnIdGetsNoAnswerButIsCarriedOut() throws Exception {
        client("c1");
        RpcDispatcher.Outcome o = dispatcher.handle(new JSONObject("{\"jsonrpc\":\"2.0\",\"method\":\"Client.SetName\",\"params\":{\"id\":\"c1\",\"name\":\"X\"}}"));
        assertNull(o.response);
        assertEquals(1, o.notifications.size());
    }

    @Test
    public void aRequestWithoutAMethodIsInvalid() throws Exception {
        RpcDispatcher.Outcome o = dispatcher.handle(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1}"));
        assertEquals(-32600, o.response.getJSONObject("error").getInt("code"));
    }

    @Test
    public void aStringIdIsEchoed() throws Exception {
        RpcDispatcher.Outcome o = dispatcher.handle(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"Server.GetRPCVersion\"}"));
        assertEquals("abc", o.response.getString("id"));
        JSONArray none = new JSONArray();
        assertEquals(0, none.length());
    }
}
