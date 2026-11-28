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

import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The state of the built-in Snapcast server (issue #330): clients, groups and streams with the rules of snapserver
 * ({@code docs/tech.md}, "JSON-RPC control"), the status as {@code Server.GetStatus} shows it, and the notifications that a change makes.
 * <p>
 * A mutating method returns the notifications (JSON-RPC notification objects) for the control connections, and never writes to a socket
 * itself; the caller sends them after it has left the monitor. All methods are thread safe (one monitor). A change that changes nothing
 * returns no notification. Errors carry the code of snapserver: -32602 for invalid params, -32603 for an internal error.
 */
public final class ServerState {

    /** At most this many streams, as the design says (S4). */
    public static final int MAX_STREAMS = 10;
    /** At most this many known clients, connected or not (memory bound). */
    public static final int MAX_CLIENTS = 64;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_LATENCY_MS = 10_000;

    /** An error of a request. */
    public static final class StateException extends RuntimeException {
        private final int code;

        public StateException(int code, String data) {
            super(data);
            this.code = code;
        }

        /** The JSON-RPC error code. */
        public int code() {
            return code;
        }

        /** The reason (the {@code data} of the error). */
        public String data() {
            return getMessage();
        }
    }

    private static final class StreamEntry {
        final String id;
        final String rawUri;
        final String scheme;
        final String authority;
        final String path;
        final Map<String, String> query;
        String status = "idle";

        StreamEntry(String id, String rawUri) {
            this.id = id;
            this.rawUri = rawUri;
            String rest = rawUri;
            int schemeEnd = rest.indexOf("://");
            this.scheme = schemeEnd > 0 ? rest.substring(0, schemeEnd) : "";
            rest = schemeEnd > 0 ? rest.substring(schemeEnd + 3) : rest;
            String queryText = "";
            int q = rest.indexOf('?');
            if (q >= 0) {
                queryText = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }
            int slash = rest.indexOf('/');
            this.authority = slash >= 0 ? rest.substring(0, slash) : rest;
            this.path = slash >= 0 ? rest.substring(slash) : "";
            Map<String, String> parsed = new TreeMap<>();
            for (String pair : queryText.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                parsed.put(percentDecode(key), percentDecode(value));
            }
            this.query = parsed;
        }

        JSONObject toJson() {
            JSONObject uri = new JSONObject();
            put(uri, "raw", rawUri);
            put(uri, "scheme", scheme);
            put(uri, "host", authority);
            put(uri, "path", path);
            JSONObject queryJson = new JSONObject();
            for (Map.Entry<String, String> e : query.entrySet()) {
                put(queryJson, e.getKey(), e.getValue());
            }
            put(uri, "query", queryJson);
            put(uri, "fragment", "");
            JSONObject json = new JSONObject();
            put(json, "id", id);
            put(json, "status", status);
            put(json, "uri", uri);
            return json;
        }
    }

    private static final class GroupEntry {
        final String id;
        String name = "";
        boolean muted;
        String streamId;
        final List<String> clients = new ArrayList<>();

        GroupEntry(String id, String streamId) {
            this.id = id;
            this.streamId = streamId;
        }
    }

    private static final class ClientEntry {
        final String id;
        String hostName = "";
        String os = "";
        String arch = "";
        String mac = "";
        String ip = "";
        String clientName = "";
        String version = "";
        int protocolVersion = 2;
        int instance = 1;
        String name = "";
        int volume = 100;
        boolean muted;
        int latencyMs;
        boolean connected;
        long lastSeenMillis;
        String groupId;

        ClientEntry(String id) {
            this.id = id;
        }
    }

    private final String serverName;
    private final String version;
    private final int bufferMs;
    private final Supplier<String> groupIds;
    private final LongSupplier nowMillis;
    private final Map<String, StreamEntry> streams = new LinkedHashMap<>();
    private final Map<String, GroupEntry> groups = new LinkedHashMap<>();
    private final Map<String, ClientEntry> clients = new LinkedHashMap<>();

    /**
     * @param serverName the name of the machine, shown as the host of the server
     * @param bufferMs   the buffer that clients are told (default 1000)
     * @param groupIds   makes the ids of new groups (a UUID in the app)
     */
    public ServerState(String serverName, String version, int bufferMs, Supplier<String> groupIds, LongSupplier nowMillis) {
        this.serverName = serverName == null ? "" : serverName;
        this.version = version == null ? "" : version;
        this.bufferMs = bufferMs;
        this.groupIds = groupIds;
        this.nowMillis = nowMillis;
    }

    // ------------------------------------------------------------------ streams

    /** Adds a stream (the caller has checked the kind of source: only tcp, see the design). */
    public synchronized List<JSONObject> addStream(String id, String rawUri) {
        if (id == null || id.isEmpty() || rawUri == null) {
            throw new StateException(-32602, "a stream needs a name and a uri");
        }
        if (streams.containsKey(id)) {
            throw new StateException(-32603, "Stream with name '" + id + "' already exists");
        }
        if (streams.size() >= MAX_STREAMS) {
            throw new StateException(-32603, "too many streams");
        }
        streams.put(id, new StreamEntry(id, rawUri));
        // a client that came while there was no stream is on the stream that appears (a group without one)
        for (GroupEntry g : groups.values()) {
            if (g.streamId == null || g.streamId.isEmpty()) {
                g.streamId = id;
            }
        }
        return Collections.singletonList(update());
    }

    public synchronized List<JSONObject> removeStream(String id) {
        if (streams.remove(id) == null) {
            throw new StateException(-32602, "Stream with id '" + id + "' not found");
        }
        for (GroupEntry g : groups.values()) {
            if (id.equals(g.streamId)) {
                g.streamId = "";
            }
        }
        return Collections.singletonList(update());
    }

    /** @param status {@code "idle"} or {@code "playing"} */
    public synchronized List<JSONObject> setStreamStatus(String id, String status) {
        StreamEntry s = streams.get(id);
        if (s == null) {
            throw new StateException(-32602, "Stream with id '" + id + "' not found");
        }
        if (status.equals(s.status)) {
            return Collections.emptyList();
        }
        s.status = status;
        JSONObject params = new JSONObject();
        put(params, "id", id);
        put(params, "stream", s.toJson());
        return Collections.singletonList(notification("Stream.OnUpdate", params));
    }

    public synchronized boolean hasStream(String id) {
        return streams.containsKey(id);
    }

    public synchronized List<String> streamIds() {
        return new ArrayList<>(streams.keySet());
    }

    // ------------------------------------------------------------------ clients

    /** The id the server uses for the client of this Hello: its ID, with {@code #n} appended for an instance above 1. */
    public String clientIdOf(HelloMessage hello) {
        return hello.getInstance() > 1 ? hello.getId() + "#" + hello.getInstance() : hello.getId();
    }

    /** A client said Hello. A client that is known keeps its group and settings. */
    public synchronized List<JSONObject> clientConnected(HelloMessage hello, String ip) {
        String id = clientIdOf(hello);
        ClientEntry c = clients.get(id);
        boolean created = false;
        if (c == null) {
            if (clients.size() >= MAX_CLIENTS) {
                throw new StateException(-32603, "too many clients");
            }
            c = new ClientEntry(id);
            clients.put(id, c);
            created = true;
        }
        c.hostName = hello.getHostName();
        c.os = hello.getOs();
        c.arch = hello.getArch();
        c.mac = hello.getMac();
        c.ip = ip == null ? "" : ip;
        c.clientName = hello.getClientName();
        c.version = hello.getVersion();
        c.protocolVersion = hello.getProtocolVersion();
        c.instance = hello.getInstance();
        c.connected = true;
        c.lastSeenMillis = nowMillis.getAsLong();
        boolean grouped = c.groupId != null && groups.containsKey(c.groupId);
        if (!grouped) {
            GroupEntry g = new GroupEntry(groupIds.get(), firstStream());
            g.clients.add(c.id);
            groups.put(g.id, g);
            c.groupId = g.id;
        }
        List<JSONObject> out = new ArrayList<>(2);
        JSONObject params = new JSONObject();
        put(params, "id", id);
        put(params, "client", clientJson(c));
        out.add(notification("Client.OnConnect", params));
        if (created || !grouped) {
            out.add(update());
        }
        return out;
    }

    public synchronized List<JSONObject> clientDisconnected(String id) {
        ClientEntry c = clients.get(id);
        if (c == null || !c.connected) {
            return Collections.emptyList();
        }
        c.connected = false;
        c.lastSeenMillis = nowMillis.getAsLong();
        JSONObject params = new JSONObject();
        put(params, "id", id);
        put(params, "client", clientJson(c));
        return Collections.singletonList(notification("Client.OnDisconnect", params));
    }

    public synchronized List<JSONObject> setClientVolume(String id, int percent, boolean muted) {
        ClientEntry c = client(id);
        if (percent < 0 || percent > 100) {
            throw new StateException(-32602, "volume out of range");
        }
        return volumeChanged(c, percent, muted);
    }

    /** The client itself reports its volume and mute (ClientInfo): out of range values are clamped, not refused. */
    public synchronized List<JSONObject> clientInfo(String id, int percent, boolean muted) {
        ClientEntry c = clients.get(id);
        if (c == null) {
            return Collections.emptyList();
        }
        return volumeChanged(c, Math.max(0, Math.min(100, percent)), muted);
    }

    private List<JSONObject> volumeChanged(ClientEntry c, int percent, boolean muted) {
        if (c.volume == percent && c.muted == muted) {
            return Collections.emptyList();
        }
        c.volume = percent;
        c.muted = muted;
        JSONObject params = new JSONObject();
        put(params, "id", c.id);
        put(params, "volume", volumeJson(c));
        return Collections.singletonList(notification("Client.OnVolumeChanged", params));
    }

    public synchronized List<JSONObject> setClientLatency(String id, int latencyMs) {
        ClientEntry c = client(id);
        if (latencyMs < 0 || latencyMs > MAX_LATENCY_MS) {
            throw new StateException(-32602, "latency out of range");
        }
        if (c.latencyMs == latencyMs) {
            return Collections.emptyList();
        }
        c.latencyMs = latencyMs;
        JSONObject params = new JSONObject();
        put(params, "id", id);
        put(params, "latency", latencyMs);
        return Collections.singletonList(notification("Client.OnLatencyChanged", params));
    }

    public synchronized List<JSONObject> setClientName(String id, String name) {
        ClientEntry c = client(id);
        String clean = clean(name);
        if (clean.equals(c.name)) {
            return Collections.emptyList();
        }
        c.name = clean;
        JSONObject params = new JSONObject();
        put(params, "id", id);
        put(params, "name", clean);
        return Collections.singletonList(notification("Client.OnNameChanged", params));
    }

    /** Forgets a client that is not connected. */
    public synchronized List<JSONObject> deleteClient(String id) {
        ClientEntry c = clients.get(id);
        if (c == null) {
            throw new StateException(-32602, "Client with id '" + id + "' not found");
        }
        if (c.connected) {
            throw new StateException(-32602, "Client '" + id + "' is connected");
        }
        removeFromGroup(c);
        clients.remove(id);
        return Collections.singletonList(update());
    }

    /** What the client is told when it joins and when its settings change. */
    public synchronized ServerSettingsMessage settingsOf(String id) {
        ClientEntry c = client(id);
        GroupEntry g = groups.get(c.groupId);
        return new ServerSettingsMessage(bufferMs, c.latencyMs, c.muted || (g != null && g.muted), c.volume);
    }

    public synchronized boolean knowsClient(String id) {
        return clients.containsKey(id);
    }

    public synchronized String groupIdOf(String clientId) {
        return client(clientId).groupId;
    }

    /** The stream of the group of a client, "" if none. */
    public synchronized String streamIdOf(String clientId) {
        ClientEntry c = client(clientId);
        GroupEntry g = groups.get(c.groupId);
        return g == null || g.streamId == null ? "" : g.streamId;
    }

    /** The connected clients that are in groups that play this stream. */
    public synchronized List<String> connectedClientsOfStream(String streamId) {
        List<String> out = new ArrayList<>();
        for (GroupEntry g : groups.values()) {
            if (streamId.equals(g.streamId)) {
                for (String id : g.clients) {
                    ClientEntry c = clients.get(id);
                    if (c != null && c.connected) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ groups

    public synchronized List<JSONObject> setGroupMute(String groupId, boolean muted) {
        GroupEntry g = group(groupId);
        if (g.muted == muted) {
            return Collections.emptyList();
        }
        g.muted = muted;
        JSONObject params = new JSONObject();
        put(params, "id", groupId);
        put(params, "mute", muted);
        return Collections.singletonList(notification("Group.OnMute", params));
    }

    public synchronized List<JSONObject> setGroupName(String groupId, String name) {
        GroupEntry g = group(groupId);
        String clean = clean(name);
        if (clean.equals(g.name)) {
            return Collections.emptyList();
        }
        g.name = clean;
        JSONObject params = new JSONObject();
        put(params, "id", groupId);
        put(params, "name", clean);
        return Collections.singletonList(notification("Group.OnNameChanged", params));
    }

    public synchronized List<JSONObject> setGroupStream(String groupId, String streamId) {
        GroupEntry g = group(groupId);
        if (!streams.containsKey(streamId)) {
            throw new StateException(-32602, "Stream with id '" + streamId + "' not found");
        }
        if (streamId.equals(g.streamId)) {
            return Collections.emptyList();
        }
        g.streamId = streamId;
        JSONObject params = new JSONObject();
        put(params, "id", groupId);
        put(params, "stream_id", streamId);
        return Collections.singletonList(notification("Group.OnStreamChanged", params));
    }

    /**
     * Sets the exact membership of a group: the clients of the list move into it, the clients that were in it and are not in the list
     * each get a group of their own on the same stream; a group that ends up without clients is gone.
     */
    public synchronized List<JSONObject> setGroupClients(String groupId, List<String> wanted) {
        GroupEntry target = group(groupId);
        List<String> ids = new ArrayList<>();
        for (String id : wanted) {
            if (!clients.containsKey(id)) {
                throw new StateException(-32602, "Client with id '" + id + "' not found");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        boolean changed = !ids.equals(target.clients);
        for (String id : new ArrayList<>(target.clients)) {
            if (!ids.contains(id)) {
                ClientEntry c = clients.get(id);
                target.clients.remove(id);
                GroupEntry alone = new GroupEntry(groupIds.get(), target.streamId);
                alone.clients.add(id);
                groups.put(alone.id, alone);
                c.groupId = alone.id;
            }
        }
        for (String id : ids) {
            ClientEntry c = clients.get(id);
            if (!groupId.equals(c.groupId)) {
                removeFromGroup(c);
                target.clients.add(id);
                c.groupId = groupId;
            }
        }
        target.clients.retainAll(ids);
        target.clients.sort((a, b) -> Integer.compare(ids.indexOf(a), ids.indexOf(b)));
        dropEmptyGroups();
        return changed ? Collections.singletonList(update()) : Collections.<JSONObject>emptyList();
    }

    // ------------------------------------------------------------------ status

    /** The result of {@code Server.GetStatus}: {@code {"server": {...}}}. */
    public synchronized JSONObject status() {
        JSONObject server = new JSONObject();
        List<JSONObject> groupList = new ArrayList<>();
        for (GroupEntry g : groups.values()) {
            groupList.add(groupJson(g));
        }
        put(server, "groups", new org.json.JSONArray(groupList));
        JSONObject host = new JSONObject();
        put(host, "arch", "");
        put(host, "ip", "");
        put(host, "mac", "");
        put(host, "name", serverName);
        put(host, "os", "Android");
        JSONObject snapserver = new JSONObject();
        put(snapserver, "controlProtocolVersion", 1);
        put(snapserver, "name", "Snapserver");
        put(snapserver, "protocolVersion", 1);
        put(snapserver, "version", version);
        JSONObject serverInfo = new JSONObject();
        put(serverInfo, "host", host);
        put(serverInfo, "snapserver", snapserver);
        put(server, "server", serverInfo);
        List<JSONObject> streamList = new ArrayList<>();
        for (StreamEntry s : streams.values()) {
            streamList.add(s.toJson());
        }
        put(server, "streams", new org.json.JSONArray(streamList));
        JSONObject result = new JSONObject();
        put(result, "server", server);
        return result;
    }

    private JSONObject update() {
        return notification("Server.OnUpdate", status());
    }

    private JSONObject groupJson(GroupEntry g) {
        JSONObject json = new JSONObject();
        put(json, "id", g.id);
        put(json, "name", g.name);
        put(json, "muted", g.muted);
        put(json, "stream_id", g.streamId == null ? "" : g.streamId);
        List<JSONObject> list = new ArrayList<>();
        for (String id : g.clients) {
            ClientEntry c = clients.get(id);
            if (c != null) {
                list.add(clientJson(c));
            }
        }
        put(json, "clients", new org.json.JSONArray(list));
        return json;
    }

    private JSONObject clientJson(ClientEntry c) {
        JSONObject config = new JSONObject();
        put(config, "instance", c.instance);
        put(config, "latency", c.latencyMs);
        put(config, "name", c.name);
        put(config, "volume", volumeJson(c));
        JSONObject host = new JSONObject();
        put(host, "arch", c.arch);
        put(host, "ip", c.ip);
        put(host, "mac", c.mac);
        put(host, "name", c.hostName);
        put(host, "os", c.os);
        JSONObject lastSeen = new JSONObject();
        put(lastSeen, "sec", c.lastSeenMillis / 1000L);
        put(lastSeen, "usec", (c.lastSeenMillis % 1000L) * 1000L);
        JSONObject snapclient = new JSONObject();
        put(snapclient, "name", c.clientName);
        put(snapclient, "protocolVersion", c.protocolVersion);
        put(snapclient, "version", c.version);
        JSONObject json = new JSONObject();
        put(json, "id", c.id);
        put(json, "connected", c.connected);
        put(json, "config", config);
        put(json, "host", host);
        put(json, "lastSeen", lastSeen);
        put(json, "snapclient", snapclient);
        return json;
    }

    private static JSONObject volumeJson(ClientEntry c) {
        JSONObject volume = new JSONObject();
        put(volume, "muted", c.muted);
        put(volume, "percent", c.volume);
        return volume;
    }

    // ------------------------------------------------------------------ helpers

    private String firstStream() {
        Iterator<String> it = streams.keySet().iterator();
        return it.hasNext() ? it.next() : "";
    }

    private ClientEntry client(String id) {
        ClientEntry c = clients.get(id);
        if (c == null) {
            throw new StateException(-32602, "Client with id '" + id + "' not found");
        }
        return c;
    }

    private GroupEntry group(String id) {
        GroupEntry g = groups.get(id);
        if (g == null) {
            throw new StateException(-32602, "Group with id '" + id + "' not found");
        }
        return g;
    }

    private void removeFromGroup(ClientEntry c) {
        GroupEntry g = groups.get(c.groupId);
        if (g != null) {
            g.clients.remove(c.id);
        }
        c.groupId = null;
        dropEmptyGroups();
    }

    private void dropEmptyGroups() {
        Iterator<GroupEntry> it = groups.values().iterator();
        while (it.hasNext()) {
            if (it.next().clients.isEmpty()) {
                it.remove();
            }
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char ch = text.charAt(i);
            if (!Character.isISOControl(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString().trim();
    }

    private static JSONObject notification(String method, JSONObject params) {
        JSONObject json = new JSONObject();
        put(json, "jsonrpc", "2.0");
        put(json, "method", method);
        put(json, "params", params);
        return json;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("a plain value is always valid");
        }
    }

    private static String percentDecode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%' && i + 2 < text.length()) {
                try {
                    bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException notHex) {
                    // kept as it is
                }
            }
            byte[] raw = String.valueOf(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bytes.write(raw, 0, raw.length);
        }
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
