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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The control calls of a Snapcast server with the parameters of the JSON-RPC API
 * ({@code doc/json_rpc_api/control.md} of Snapcast): volume, mute, latency and name of clients, mute, stream, members
 * and name of groups, adding and removing streams, and the status. The calls block (they are made on the worker of the
 * server, never on the main thread).
 *
 * <p>A wrong argument of the caller (a missing id) is an {@link IllegalArgumentException}; everything the server does is
 * a {@link SnapcastException}. A call that changes the model, and {@link #getStatus()}, throws an
 * {@link IllegalStateException} when it is made from inside a delivery of the listeners (a notification handler, a
 * re-read requester): the call would wait for the thread it blocks. Ids that are handed in come from the model (they were truncated when parsed); names are
 * cut at {@link SnapcastLimits#MAX_STRING_LENGTH} characters before they are sent.
 *
 * <p><b>The model.</b> With a {@link SnapcastModelUpdater} the status that a call returns, and every change of our own,
 * are applied to the model through {@link SnapcastCaller#callOrdered}, so in the order of the lines of the connection.
 * That is needed: the server tells every connection about a change except the one that made it, so without it the
 * model would not show what we just did. A stream that was added or removed can not be patched; it asks for a re-read.
 * A failed call changes nothing.
 *
 * <p><b>For the code that uses this (Group 3).</b> Give {@link SnapcastRpc} a listener executor of its own, used for
 * nothing but the deliveries: a task of anything else on the same single thread blocks the delivery that a
 * {@code callOrdered} waits for, and the call ends in TIMEOUT after its deadline. The re-read requester of the
 * {@link SnapcastModelUpdater} runs on that thread: it must only hand the read to a worker, never call
 * {@link #getStatus()} itself. Read a fresh status (on a worker) in {@code onConnected}, and repeat a re-read that
 * failed. Do not call {@link SnapcastRpc#reconnect()} or {@link SnapcastRpc#retry()} in an automatic loop: both reset
 * the failure count, so the ceiling of the reconnects is gone. A single call that takes the whole deadline (10 s)
 * while the server sends nothing else (an {@code AddStream} of a slow {@code process://} source) drops the connection:
 * prefer sources that answer at once, or give such a call a longer deadline.
 */
public final class SnapcastOperations {

    /** The longest stream URI we send. */
    private static final int MAX_URI_LENGTH = 2048;

    private final SnapcastCaller caller;
    private final SnapcastModelUpdater updater;

    /** Operations that do not keep a model. */
    public SnapcastOperations(SnapcastCaller caller) {
        this(caller, null);
    }

    /** @param updater the model to keep up to date with the answers and our own changes; null for none */
    public SnapcastOperations(SnapcastCaller caller, SnapcastModelUpdater updater) {
        this.caller = caller;
        this.updater = updater;
    }

    /** Patches the model with what the server would have told the other connections. */
    private void patch(String notification, JSONObject params) {
        if (updater != null) {
            updater.applyOwnChange(notification, params);
        }
    }

    /** {@code Server.GetStatus}: the whole state of the server. */
    public SnapcastServerStatus getStatus() throws SnapcastException {
        return caller.callOrdered("Server.GetStatus", null, this::takeStatus);
    }

    /** Parses a status answer and makes it the model (runs in line order). */
    private SnapcastServerStatus takeStatus(JSONObject result) throws SnapcastException {
        SnapcastServerStatus status = SnapcastServerStatus.fromResult(result);
        if (updater != null) {
            updater.setStatus(status);
        }
        return status;
    }

    /**
     * {@code Client.SetVolume}.
     *
     * @return the volume the server answered, or the requested one if the answer has none
     */
    public SnapcastVolume setClientVolume(String clientId, SnapcastVolume volume) throws SnapcastException {
        requireId(clientId);
        if (volume == null) {
            throw new IllegalArgumentException("no volume");
        }
        return caller.callOrdered("Client.SetVolume", json("id", clientId, "volume", volume.toJson()), answer -> {
            JSONObject answered = SnapcastJson.object(answer, "volume");
            SnapcastVolume actual = answered == null ? volume : SnapcastVolume.fromJson(answered);
            patch("Client.OnVolumeChanged", json("id", clientId, "volume", actual.toJson()));
            return actual;
        });
    }

    /** {@code Client.SetLatency}; the latency is clamped into 0..10,000 ms. */
    public void setClientLatency(String clientId, int latencyMs) throws SnapcastException {
        requireId(clientId);
        final int clamped = Math.max(0, Math.min(SnapcastLimits.MAX_LATENCY_MS, latencyMs));
        caller.callOrdered("Client.SetLatency", json("id", clientId, "latency", clamped), answer -> {
            // the server may clamp further (to its buffer); the answer says what it did
            int actual = SnapcastJson.isNumber(answer, "latency")
                    ? SnapcastJson.integer(answer, "latency", 0, SnapcastLimits.MAX_LATENCY_MS, clamped) : clamped;
            patch("Client.OnLatencyChanged", json("id", clientId, "latency", actual));
            return null;
        });
    }

    /** {@code Client.SetName}. */
    public void setClientName(String clientId, String name) throws SnapcastException {
        requireId(clientId);
        final String sent = SnapcastJson.truncate(name == null ? "" : name);
        caller.callOrdered("Client.SetName", json("id", clientId, "name", sent), answer -> {
            patch("Client.OnNameChanged", json("id", clientId, "name", sent));
            return null;
        });
    }

    /** {@code Group.SetMute}. */
    public void setGroupMute(String groupId, boolean muted) throws SnapcastException {
        requireId(groupId);
        caller.callOrdered("Group.SetMute", json("id", groupId, "mute", muted), answer -> {
            patch("Group.OnMute", json("id", groupId, "mute", SnapcastJson.bool(answer, "mute", muted)));
            return null;
        });
    }

    /** {@code Group.SetStream}: the group plays this stream. */
    public void setGroupStream(String groupId, String streamId) throws SnapcastException {
        requireId(groupId);
        requireId(streamId);
        caller.callOrdered("Group.SetStream", json("id", groupId, "stream_id", streamId), answer -> {
            patch("Group.OnStreamChanged", json("id", groupId, "stream_id", streamId));
            return null;
        });
    }

    /**
     * {@code Group.SetClients}: the group gets <b>exactly</b> these members. Clients of the group that are not in the
     * list leave it (each gets a group of its own), the others are moved in. So the list must be the full wanted
     * membership; one that exceeds {@link SnapcastLimits#MAX_CLIENTS_PER_GROUP} is refused, not cut (a cut list would
     * remove members). An empty list would dissolve the group and is refused too.
     *
     * @return the status the server answered with (it is also the new model)
     * @throws IllegalArgumentException for {@code null}, an empty list or an empty id
     */
    public SnapcastServerStatus setGroupClients(String groupId, List<String> clientIds) throws SnapcastException {
        requireId(groupId);
        if (clientIds == null || clientIds.isEmpty()) {
            throw new IllegalArgumentException("no clients");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String clientId : clientIds) {
            requireId(clientId);
            unique.add(clientId);
        }
        if (unique.size() > SnapcastLimits.MAX_CLIENTS_PER_GROUP) {
            throw SnapcastException.limit("too many clients for one group");
        }
        JSONArray array = new JSONArray();
        for (String clientId : unique) {
            array.put(clientId);
        }
        return caller.callOrdered("Group.SetClients", json("id", groupId, "clients", array), this::takeStatus);
    }

    /** {@code Group.SetName}. */
    public void setGroupName(String groupId, String name) throws SnapcastException {
        requireId(groupId);
        final String sent = SnapcastJson.truncate(name == null ? "" : name);
        caller.callOrdered("Group.SetName", json("id", groupId, "name", sent), answer -> {
            patch("Group.OnNameChanged", json("id", groupId, "name", sent));
            return null;
        });
    }

    /**
     * {@code Stream.AddStream}.
     *
     * <p>The URI is sent as given: the server may accept any scheme (the documented restriction was not found in its
     * sources), so a caller builds only {@code tcp} sources it controls and never passes a URI taken from server data.
     * The control connection has no TLS and no authentication.
     *
     * @return the id of the new stream; the server answers {@code id} (its source) or {@code stream_id} (its
     * documentation), both are read
     * @throws SnapcastException MALFORMED when the answer has neither
     */
    public String addStream(String streamUri) throws SnapcastException {
        if (streamUri == null || streamUri.isEmpty() || streamUri.length() > MAX_URI_LENGTH) {
            throw new IllegalArgumentException("bad stream uri");
        }
        return caller.callOrdered("Stream.AddStream", json("streamUri", streamUri), answer -> {
            String id = streamIdOf(answer);
            if (id == null) {
                throw SnapcastException.malformed("the answer has no stream id");
            }
            requestReRead();
            return id;
        });
    }

    /** The stream list changed and the server does not tell this connection: ask for the status again. */
    private void requestReRead() {
        if (updater != null) {
            updater.requestReRead();
        }
    }

    /**
     * {@code Stream.RemoveStream}.
     *
     * @return the id the server answered, or the requested id if the answer has none
     */
    public String removeStream(String streamId) throws SnapcastException {
        requireId(streamId);
        return caller.callOrdered("Stream.RemoveStream", json("id", streamId), answer -> {
            String id = streamIdOf(answer);
            requestReRead();
            return id == null ? streamId : id;
        });
    }

    private static String streamIdOf(JSONObject answer) {
        String id = SnapcastJson.strictString(answer, "id");
        if (id == null || id.isEmpty()) {
            id = SnapcastJson.strictString(answer, "stream_id");
        }
        return id == null || id.isEmpty() ? null : id;
    }

    /**
     * Sets the volume of a group (the server has none) from one snapshot: the start and the current levels are the
     * levels of {@code group}. Fine for a single step; a drag uses {@link #setGroupVolume(SnapcastGroup, Map, int)}.
     */
    public Map<String, Integer> setGroupVolume(SnapcastGroup group, int percent) throws SnapcastException {
        return setGroupVolume(group, GroupVolume.levels(group), percent);
    }

    /**
     * Sets the volume of a group (the server has none): the connected members move by the same number of points so that
     * their average becomes {@code percent}, see {@link GroupVolume}. A muted member gets its new level and stays muted. A
     * member whose level does not change gets no call. Every member is tried; if a call fails, the first failure is
     * thrown after the others were sent (the members that were set are in the model already).
     *
     * <p>For a drag, pass the levels from the <b>start</b> of the drag as {@code startLevels} and the group as it is
     * <b>now</b> (the model) as {@code current}: the targets come from the start levels, so the steps do not add up
     * and a member whose target is back at its start level gets that level again.
     *
     * @param current     the group as it is now; its clients are the ones that are set
     * @param startLevels the levels the targets are calculated from, see {@link GroupVolume#levels}
     * @return the new level of every member that was changed, by client id
     */
    public Map<String, Integer> setGroupVolume(SnapcastGroup current, Map<String, Integer> startLevels, int percent)
            throws SnapcastException {
        Map<String, Integer> changes = GroupVolume.changes(startLevels, GroupVolume.levels(current), percent);
        Map<String, Integer> changed = new LinkedHashMap<>();
        SnapcastException firstFailure = null;
        for (SnapcastClient client : current.getClients()) {
            Integer wanted = changes.get(client.getId());
            if (wanted == null) {
                continue;
            }
            try {
                setClientVolume(client.getId(), client.getVolume().withPercent(wanted));
                changed.put(client.getId(), wanted);
            } catch (SnapcastException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return changed;
    }

    private static void requireId(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("no id");
        }
    }

    private static JSONObject json(Object... keysAndValues) {
        try {
            JSONObject object = new JSONObject();
            for (int i = 0; i < keysAndValues.length; i += 2) {
                object.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
            return object;
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
