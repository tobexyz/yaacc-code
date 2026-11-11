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
package de.yaacc.multiroom.feed;

import de.yaacc.multiroom.control.SnapcastCaller;
import de.yaacc.multiroom.control.SnapcastException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of a snapserver as far as the feed needs it: streams, groups, the answers of {@code Server.GetStatus},
 * {@code Stream.AddStream}, {@code Stream.RemoveStream} and {@code Group.SetStream}, with the error texts and codes
 * of the real server (a taken port or a duplicate name is -32603 with the reason in {@code data}). It is a
 * {@link SnapcastCaller} for direct use and is served over a loopback socket by {@link LoopbackControlServer}.
 * A test can add hostile entries to the status with {@link #addRawStream}.
 */
final class SimulatedSnapcastServer implements SnapcastCaller {

    private final Object lock = new Object();
    private final List<JSONObject> streams = new ArrayList<>();
    private final List<JSONObject> groups = new ArrayList<>();
    private final Set<Integer> takenPorts = new HashSet<>();
    private final Map<Integer, ServerSocket> listeners = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> addUris = new ArrayList<>();
    private boolean bindRealListeners;
    private SnapcastException failNext;
    private final Map<String, SnapcastException> failMethodOnce = new HashMap<>();
    private final Map<String, Runnable> beforeCall = new HashMap<>();
    private SnapcastException failAddStream;
    private String answerIdOverride;

    // ---- setup

    /** A group with these client ids on this stream. */
    void addGroup(String groupId, String streamId, String... clientIds) {
        try {
            JSONArray clients = new JSONArray();
            for (String clientId : clientIds) {
                clients.put(new JSONObject()
                        .put("id", clientId)
                        .put("connected", true)
                        .put("config", new JSONObject().put("name", clientId).put("latency", 0)
                                .put("volume", new JSONObject().put("percent", 50).put("muted", false))));
            }
            synchronized (lock) {
                groups.add(new JSONObject().put("id", groupId).put("name", "").put("muted", false)
                        .put("stream_id", streamId).put("clients", clients));
            }
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    /** The group goes away (the server derives groups from clients). */
    void removeGroup(String groupId) {
        synchronized (lock) {
            for (int i = 0; i < groups.size(); i++) {
                if (groupId.equals(groups.get(i).optString("id"))) {
                    groups.remove(i);
                    return;
                }
            }
        }
    }

    /** A stream of the user (a pipe source). */
    void addPipeStream(String id) {
        try {
            synchronized (lock) {
                streams.add(new JSONObject().put("id", id).put("status", "idle").put("uri", new JSONObject()
                        .put("raw", "pipe:///tmp/" + id).put("scheme", "pipe").put("host", "").put("path", "/tmp/" + id)
                        .put("fragment", "").put("query", new JSONObject().put("name", id))));
            }
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    /** A tcp stream as the real server reports it. */
    void addTcpStream(String name, int port, String mode, String sampleFormat) {
        addTcpStream(name, "0.0.0.0:" + port, mode, sampleFormat);
    }

    /** A tcp stream with the given {@code uri.host} text (the server joins host and port there). */
    void addTcpStream(String name, String uriHost, String mode, String sampleFormat) {
        try {
            JSONObject query = new JSONObject().put("name", name).put("sampleformat", sampleFormat).put("codec", "flac");
            if (mode != null) {
                query.put("mode", mode);
            }
            synchronized (lock) {
                streams.add(new JSONObject().put("id", name).put("status", "idle").put("uri", new JSONObject()
                        .put("raw", "tcp://" + uriHost + "?name=" + name).put("scheme", "tcp").put("host", uriHost)
                        .put("path", "").put("fragment", "").put("query", query)));
            }
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    /** Any stream JSON at all, for hostile input. */
    void addRawStream(JSONObject stream) {
        synchronized (lock) {
            streams.add(stream);
        }
    }

    /** A port that the server can not bind although no stream names it (another program uses it). */
    void takePort(int port) {
        synchronized (lock) {
            takenPorts.add(port);
        }
    }

    /** {@code Stream.AddStream} really listens on the port (on loopback), so that a feeder can connect. */
    void bindRealListeners() {
        synchronized (lock) {
            bindRealListeners = true;
        }
    }

    /** The next call, whatever it is, fails with this. */
    void failNextCall(SnapcastException failure) {
        synchronized (lock) {
            failNext = failure;
        }
    }

    /** The next call of this method (and no other) fails with this. */
    void failNextCallOf(String method, SnapcastException failure) {
        synchronized (lock) {
            failMethodOnce.put(method, failure);
        }
    }

    /** Runs this (once, outside the lock) just before the next call of the method is answered. */
    void beforeNextCallOf(String method, Runnable action) {
        synchronized (lock) {
            beforeCall.put(method, action);
        }
    }

    /** Every {@code Stream.AddStream} fails with this. */
    void failAddStream(SnapcastException failure) {
        synchronized (lock) {
            failAddStream = failure;
        }
    }

    /** {@code Stream.AddStream} answers this id whatever it created. */
    void answerAddStreamWith(String id) {
        synchronized (lock) {
            answerIdOverride = id;
        }
    }

    static SnapcastException error(int code, String title, String data) {
        return new SnapcastException(SnapcastException.Kind.SERVER_ERROR, code, title, data, null);
    }

    // ---- inspection

    List<String> calls() {
        synchronized (lock) {
            return new ArrayList<>(calls);
        }
    }

    /** The calls of one method. */
    int callCount(String method) {
        synchronized (lock) {
            int count = 0;
            for (String call : calls) {
                if (call.equals(method) || call.startsWith(method + " ")) {
                    count++;
                }
            }
            return count;
        }
    }

    List<String> addUris() {
        synchronized (lock) {
            return new ArrayList<>(addUris);
        }
    }

    boolean hasStream(String id) {
        synchronized (lock) {
            for (JSONObject stream : streams) {
                if (id.equals(stream.optString("id"))) {
                    return true;
                }
            }
            return false;
        }
    }

    int streamCount() {
        synchronized (lock) {
            return streams.size();
        }
    }

    String groupStream(String groupId) {
        synchronized (lock) {
            for (JSONObject group : groups) {
                if (groupId.equals(group.optString("id"))) {
                    return group.optString("stream_id");
                }
            }
            return null;
        }
    }

    /** The first query value of the stream with this id, or null. */
    String streamQuery(String streamId, String key) {
        synchronized (lock) {
            for (JSONObject stream : streams) {
                if (streamId.equals(stream.optString("id"))) {
                    JSONObject query = stream.optJSONObject("uri").optJSONObject("query");
                    return query == null ? null : query.optString(key, null);
                }
            }
            return null;
        }
    }

    /** The port of a stream with this id as the status reports it, or -1. */
    int streamPort(String streamId) {
        synchronized (lock) {
            for (JSONObject stream : streams) {
                if (streamId.equals(stream.optString("id"))) {
                    String host = stream.optJSONObject("uri").optString("host");
                    int colon = host.lastIndexOf(':');
                    return colon < 0 ? -1 : Integer.parseInt(host.substring(colon + 1));
                }
            }
            return -1;
        }
    }

    /** The listener that {@link #bindRealListeners} opened for a port, or null. */
    ServerSocket listener(int port) {
        synchronized (lock) {
            return listeners.get(port);
        }
    }

    void close() {
        synchronized (lock) {
            for (ServerSocket socket : listeners.values()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
            listeners.clear();
        }
    }

    // ---- the calls

    @Override
    public JSONObject call(String method, JSONObject params) throws SnapcastException {
        Runnable before;
        synchronized (lock) {
            before = beforeCall.remove(method);
        }
        if (before != null) {
            before.run();
        }
        synchronized (lock) {
            calls.add(method + (params == null ? "" : " " + shortParams(method, params)));
            if (failNext != null) {
                SnapcastException failure = failNext;
                failNext = null;
                throw failure;
            }
            SnapcastException methodFailure = failMethodOnce.remove(method);
            if (methodFailure != null) {
                throw methodFailure;
            }
            try {
                switch (method) {
                    case "Server.GetStatus":
                        return status();
                    case "Stream.AddStream":
                        return addStream(params.getString("streamUri"));
                    case "Stream.RemoveStream":
                        return removeStream(params.getString("id"));
                    case "Group.SetStream":
                        return setStream(params.getString("id"), params.getString("stream_id"));
                    default:
                        throw error(-32601, "Method not found", method);
                }
            } catch (JSONException e) {
                throw error(-32602, "Invalid params", "missing parameter");
            }
        }
    }

    private static String shortParams(String method, JSONObject params) {
        if ("Group.SetStream".equals(method)) {
            return params.optString("id") + " " + params.optString("stream_id");
        }
        if ("Stream.RemoveStream".equals(method)) {
            return params.optString("id");
        }
        return "";
    }

    private JSONObject status() throws JSONException {
        JSONArray streamArray = new JSONArray();
        for (JSONObject stream : streams) {
            streamArray.put(stream);
        }
        JSONArray groupArray = new JSONArray();
        for (JSONObject group : groups) {
            groupArray.put(group);
        }
        JSONObject server = new JSONObject()
                .put("groups", groupArray)
                .put("streams", streamArray)
                .put("server", new JSONObject()
                        .put("host", new JSONObject().put("name", "test-server"))
                        .put("snapserver", new JSONObject().put("version", "0.35.0")));
        // a copy: the caller owns what it gets
        try {
            return new JSONObject(new JSONObject().put("server", server).toString());
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private JSONObject addStream(String uri) throws SnapcastException, JSONException {
        addUris.add(uri);
        if (failAddStream != null) {
            throw failAddStream;
        }
        Map<String, String> query = parseQuery(uri);
        String name = query.get("name");
        String host = authority(uri);
        int colon = host.lastIndexOf(':');
        int port = Integer.parseInt(host.substring(colon + 1));
        if (name == null || name.isEmpty()) {
            throw error(-32603, "Internal error", "Stream name is empty");
        }
        for (JSONObject stream : streams) {
            if (name.equals(stream.optString("id"))) {
                throw error(-32603, "Internal error", "Stream with name '" + name + "' already exists");
            }
        }
        if (takenPorts.contains(port) || portNamedByAStream(port)) {
            throw error(-32603, "Internal error", "Address already in use");
        }
        if (bindRealListeners) {
            try {
                listeners.put(port, new ServerSocket(port, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1})));
            } catch (IOException e) {
                throw error(-32603, "Internal error", "Address already in use");
            }
        }
        JSONObject queryJson = new JSONObject();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            queryJson.put(entry.getKey(), entry.getValue());
        }
        streams.add(new JSONObject().put("id", name).put("status", "idle").put("uri", new JSONObject()
                .put("raw", uri).put("scheme", "tcp").put("host", host).put("path", "").put("fragment", "")
                .put("query", queryJson)));
        return new JSONObject().put("id", answerIdOverride != null ? answerIdOverride : name);
    }

    private boolean portNamedByAStream(int port) {
        for (JSONObject stream : streams) {
            String host = stream.optJSONObject("uri") == null ? "" : stream.optJSONObject("uri").optString("host");
            if (host.endsWith(":" + port)) {
                return true;
            }
        }
        return false;
    }

    private JSONObject removeStream(String id) throws SnapcastException, JSONException {
        for (int i = 0; i < streams.size(); i++) {
            if (id.equals(streams.get(i).optString("id"))) {
                int port = streamPortLocked(streams.get(i));
                streams.remove(i);
                ServerSocket socket = listeners.remove(port);
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // closing
                    }
                }
                for (JSONObject group : groups) {
                    if (id.equals(group.optString("stream_id"))) {
                        group.put("stream_id", "");
                    }
                }
                return new JSONObject().put("id", id);
            }
        }
        throw error(-32602, "Invalid params", "Stream not found");
    }

    private static int streamPortLocked(JSONObject stream) {
        String host = stream.optJSONObject("uri") == null ? "" : stream.optJSONObject("uri").optString("host");
        int colon = host.lastIndexOf(':');
        try {
            return colon < 0 ? -1 : Integer.parseInt(host.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private JSONObject setStream(String groupId, String streamId) throws SnapcastException, JSONException {
        boolean streamExists = false;
        for (JSONObject stream : streams) {
            if (streamId.equals(stream.optString("id"))) {
                streamExists = true;
            }
        }
        if (!streamExists) {
            throw error(-32603, "Internal error", "Stream not found");
        }
        for (JSONObject group : groups) {
            if (groupId.equals(group.optString("id"))) {
                group.put("stream_id", streamId);
                return new JSONObject().put("stream_id", streamId);
            }
        }
        throw error(-32603, "Internal error", "Group not found");
    }

    // ---- uri parsing the way the server reads it (percent-decoding, no '+' decoding)

    static String authority(String uri) {
        int start = uri.indexOf("://") + 3;
        int end = uri.indexOf('?', start);
        return uri.substring(start, end < 0 ? uri.length() : end);
    }

    static Map<String, String> parseQuery(String uri) {
        Map<String, String> query = new HashMap<>();
        int question = uri.indexOf('?');
        if (question < 0) {
            return query;
        }
        for (String pair : uri.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                query.put(decode(pair), "");
            } else if (!query.containsKey(decode(pair.substring(0, equals)))) {
                query.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
            }
        }
        return query;
    }

    static String decode(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                out.write(Integer.parseInt(new String(raw, i + 1, 2, StandardCharsets.US_ASCII), 16));
                i += 2;
            } else {
                out.write(raw[i]);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
