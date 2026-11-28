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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The JSON-RPC methods of the control port of the built-in server (issue #330, task 3.2): the subset that the app's own control library
 * and a client need (docs/tech.md, "JSON-RPC control"). One request in, the answer and the notifications of the change out; the
 * notifications go to the other control connections, not to the one that made the change (snapserver does the same, and the client of the
 * app relies on it). Every error carries the code of snapserver and the reason in {@code data}.
 */
public final class RpcDispatcher {

    /** The answer (null for a request without an id) and the notifications to send to the other connections. */
    public static final class Outcome {
        public final JSONObject response;
        public final List<JSONObject> notifications;

        Outcome(JSONObject response, List<JSONObject> notifications) {
            this.response = response;
            this.notifications = notifications;
        }
    }

    private final SnapcastServer server;
    private final ServerState state;

    public RpcDispatcher(SnapcastServer server) {
        this.server = server;
        this.state = server.state();
    }

    public Outcome handle(JSONObject request) {
        Object id = request.opt("id");
        boolean wantsAnswer = request.has("id") && !request.isNull("id");
        Object method = request.opt("method");
        if (!(method instanceof String) || ((String) method).isEmpty()) {
            return new Outcome(error(id, -32600, "Invalid request", "no method"), Collections.<JSONObject>emptyList());
        }
        try {
            JSONObject params = paramsOf(request);
            List<JSONObject> notifications = new ArrayList<>();
            JSONObject result = dispatch((String) method, params, notifications);
            if (!notifications.isEmpty()) {
                server.applyChange();
            }
            return new Outcome(wantsAnswer ? success(id, result) : null, notifications);
        } catch (ServerState.StateException e) {
            return new Outcome(wantsAnswer ? error(id, e.code(), titleOf(e.code()), e.data()) : null, Collections.<JSONObject>emptyList());
        } catch (UnknownMethod e) {
            return new Outcome(wantsAnswer ? error(id, -32601, "Method not found", (String) method) : null, Collections.<JSONObject>emptyList());
        }
    }

    private static final class UnknownMethod extends RuntimeException {
        UnknownMethod() {
            super("unknown method");
        }
    }

    private JSONObject dispatch(String method, JSONObject p, List<JSONObject> out) {
        switch (method) {
            case "Server.GetRPCVersion": {
                JSONObject v = new JSONObject();
                put(v, "major", 2);
                put(v, "minor", 0);
                put(v, "patch", 0);
                return v;
            }
            case "Server.GetStatus":
                return state.status();
            case "Server.DeleteClient":
                out.addAll(state.deleteClient(string(p, "id")));
                return state.status();
            case "Client.GetStatus":
                return state.clientStatus(string(p, "id"));
            case "Group.GetStatus":
                return state.groupStatus(string(p, "id"));
            case "Client.SetVolume": {
                JSONObject volume = object(p, "volume");
                out.addAll(state.setClientVolume(string(p, "id"), integer(volume, "percent"), bool(volume, "muted")));
                JSONObject result = new JSONObject();
                put(result, "volume", volume);
                return result;
            }
            case "Client.SetLatency": {
                int latency = integer(p, "latency");
                out.addAll(state.setClientLatency(string(p, "id"), latency));
                JSONObject result = new JSONObject();
                put(result, "latency", latency);
                return result;
            }
            case "Client.SetName": {
                String name = string(p, "name");
                out.addAll(state.setClientName(string(p, "id"), name));
                JSONObject result = new JSONObject();
                put(result, "name", name);
                return result;
            }
            case "Group.SetMute": {
                boolean mute = bool(p, "mute");
                out.addAll(state.setGroupMute(string(p, "id"), mute));
                JSONObject result = new JSONObject();
                put(result, "mute", mute);
                return result;
            }
            case "Group.SetName": {
                String name = string(p, "name");
                out.addAll(state.setGroupName(string(p, "id"), name));
                JSONObject result = new JSONObject();
                put(result, "name", name);
                return result;
            }
            case "Group.SetStream": {
                String stream = string(p, "stream_id");
                out.addAll(state.setGroupStream(string(p, "id"), stream));
                JSONObject result = new JSONObject();
                put(result, "stream_id", stream);
                return result;
            }
            case "Group.SetClients": {
                JSONArray array = p.optJSONArray("clients");
                if (array == null || array.length() > ServerState.MAX_CLIENTS) {
                    throw invalid("clients is a list of client ids");
                }
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    Object o = array.opt(i);
                    if (!(o instanceof String)) {
                        throw invalid("clients is a list of client ids");
                    }
                    ids.add((String) o);
                }
                out.addAll(state.setGroupClients(string(p, "id"), ids));
                return state.status();
            }
            case "Stream.AddStream": {
                StreamSpec spec = StreamSpec.parse(string(p, "streamUri"));
                out.addAll(server.addTcpStream(spec));
                JSONObject result = new JSONObject();
                put(result, "stream_id", spec.name());
                put(result, "id", spec.name());
                return result;
            }
            case "Stream.RemoveStream": {
                String id = string(p, "id");
                out.addAll(server.removeStreamNotifying(id));
                JSONObject result = new JSONObject();
                put(result, "id", id);
                return result;
            }
            default:
                throw new UnknownMethod();
        }
    }

    // ------------------------------------------------------------------ params

    private static JSONObject paramsOf(JSONObject request) {
        if (!request.has("params") || request.isNull("params")) {
            return new JSONObject();
        }
        JSONObject p = request.optJSONObject("params");
        if (p == null) {
            throw invalid("params is an object");
        }
        return p;
    }

    private static String string(JSONObject p, String key) {
        Object o = p.opt(key);
        if (!(o instanceof String)) {
            throw invalid("'" + key + "' is missing or not a text");
        }
        return (String) o;
    }

    private static int integer(JSONObject p, String key) {
        Object o = p.opt(key);
        if (!(o instanceof Integer) && !(o instanceof Long)) {
            throw invalid("'" + key + "' is missing or not an integer");
        }
        long n = ((Number) o).longValue();
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
            throw invalid("'" + key + "' is out of range");
        }
        return (int) n;
    }

    private static boolean bool(JSONObject p, String key) {
        Object o = p.opt(key);
        if (!(o instanceof Boolean)) {
            throw invalid("'" + key + "' is missing or not a boolean");
        }
        return (Boolean) o;
    }

    private static JSONObject object(JSONObject p, String key) {
        JSONObject o = p.optJSONObject(key);
        if (o == null) {
            throw invalid("'" + key + "' is missing or not an object");
        }
        return o;
    }

    private static ServerState.StateException invalid(String data) {
        return new ServerState.StateException(-32602, data);
    }

    // ------------------------------------------------------------------ answers

    private static String titleOf(int code) {
        switch (code) {
            case -32602:
                return "Invalid params";
            case -32601:
                return "Method not found";
            default:
                return "Internal error";
        }
    }

    private static JSONObject success(Object id, JSONObject result) {
        JSONObject json = new JSONObject();
        put(json, "jsonrpc", "2.0");
        put(json, "id", id);
        put(json, "result", result);
        return json;
    }

    private static JSONObject error(Object id, int code, String message, String data) {
        JSONObject e = new JSONObject();
        put(e, "code", code);
        put(e, "message", message);
        put(e, "data", data);
        JSONObject json = new JSONObject();
        put(json, "jsonrpc", "2.0");
        put(json, "error", e);
        put(json, "id", id == null ? JSONObject.NULL : id);
        return json;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("a plain value is always valid");
        }
    }
}
