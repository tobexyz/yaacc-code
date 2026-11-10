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
 * a {@link SnapcastException}. Ids that are handed in come from the model (they were truncated when parsed); names are
 * cut at {@link SnapcastLimits#MAX_STRING_LENGTH} characters before they are sent.
 */
public final class SnapcastOperations {

    /** The longest stream URI we send. */
    private static final int MAX_URI_LENGTH = 2048;

    private final SnapcastCaller caller;

    public SnapcastOperations(SnapcastCaller caller) {
        this.caller = caller;
    }

    /** {@code Server.GetStatus}: the whole state of the server. */
    public SnapcastServerStatus getStatus() throws SnapcastException {
        return SnapcastServerStatus.fromResult(caller.call("Server.GetStatus", null));
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
        JSONObject answer = caller.call("Client.SetVolume", json("id", clientId, "volume", volume.toJson()));
        JSONObject answered = SnapcastJson.object(answer, "volume");
        return answered == null ? volume : SnapcastVolume.fromJson(answered);
    }

    /** {@code Client.SetLatency}; the latency is clamped into 0..10,000 ms. */
    public void setClientLatency(String clientId, int latencyMs) throws SnapcastException {
        requireId(clientId);
        int clamped = Math.max(0, Math.min(SnapcastLimits.MAX_LATENCY_MS, latencyMs));
        caller.call("Client.SetLatency", json("id", clientId, "latency", clamped));
    }

    /** {@code Client.SetName}. */
    public void setClientName(String clientId, String name) throws SnapcastException {
        requireId(clientId);
        caller.call("Client.SetName", json("id", clientId, "name", SnapcastJson.truncate(name == null ? "" : name)));
    }

    /** {@code Group.SetMute}. */
    public void setGroupMute(String groupId, boolean muted) throws SnapcastException {
        requireId(groupId);
        caller.call("Group.SetMute", json("id", groupId, "mute", muted));
    }

    /** {@code Group.SetStream}: the group plays this stream. */
    public void setGroupStream(String groupId, String streamId) throws SnapcastException {
        requireId(groupId);
        requireId(streamId);
        caller.call("Group.SetStream", json("id", groupId, "stream_id", streamId));
    }

    /**
     * {@code Group.SetClients}: the group gets <b>exactly</b> these members. Clients of the group that are not in the
     * list leave it (each gets a group of its own), the others are moved in. So the list must be the full wanted
     * membership; one that exceeds {@link SnapcastLimits#MAX_CLIENTS_PER_GROUP} is refused, not cut (a cut list would
     * remove members).
     *
     * @return the status the server answered with
     */
    public SnapcastServerStatus setGroupClients(String groupId, List<String> clientIds) throws SnapcastException {
        requireId(groupId);
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
        return SnapcastServerStatus.fromResult(caller.call("Group.SetClients", json("id", groupId, "clients", array)));
    }

    /** {@code Group.SetName}. */
    public void setGroupName(String groupId, String name) throws SnapcastException {
        requireId(groupId);
        caller.call("Group.SetName", json("id", groupId, "name", SnapcastJson.truncate(name == null ? "" : name)));
    }

    /**
     * {@code Stream.AddStream}.
     *
     * @return the id of the new stream; the server answers {@code id} (its source) or {@code stream_id} (its
     * documentation), both are read
     * @throws SnapcastException MALFORMED when the answer has neither
     */
    public String addStream(String streamUri) throws SnapcastException {
        if (streamUri == null || streamUri.isEmpty() || streamUri.length() > MAX_URI_LENGTH) {
            throw new IllegalArgumentException("bad stream uri");
        }
        JSONObject answer = caller.call("Stream.AddStream", json("streamUri", streamUri));
        String id = streamIdOf(answer);
        if (id == null) {
            throw SnapcastException.malformed("the answer has no stream id");
        }
        return id;
    }

    /**
     * {@code Stream.RemoveStream}.
     *
     * @return the id the server answered, or the requested id if the answer has none
     */
    public String removeStream(String streamId) throws SnapcastException {
        requireId(streamId);
        JSONObject answer = caller.call("Stream.RemoveStream", json("id", streamId));
        String id = streamIdOf(answer);
        return id == null ? streamId : id;
    }

    private static String streamIdOf(JSONObject answer) {
        String id = SnapcastJson.strictString(answer, "id");
        if (id == null || id.isEmpty()) {
            id = SnapcastJson.strictString(answer, "stream_id");
        }
        return id == null || id.isEmpty() ? null : id;
    }

    /**
     * Sets the volume of a group (the server has none): the connected members move by the same number of points so that
     * their average becomes {@code percent}, see {@link GroupVolume}. A muted member gets its new level and stays muted. A
     * member whose level does not change gets no call. Every member is tried; if a call fails, the first failure is
     * thrown after the others were sent.
     *
     * @return the new level of every member that was changed, by client id
     */
    public Map<String, Integer> setGroupVolume(SnapcastGroup group, int percent) throws SnapcastException {
        Map<String, Integer> start = GroupVolume.levels(group);
        Map<String, Integer> target = GroupVolume.target(start, percent);
        Map<String, Integer> changed = new LinkedHashMap<>();
        SnapcastException firstFailure = null;
        for (SnapcastClient client : group.getClients()) {
            Integer wanted = target.get(client.getId());
            if (wanted == null || wanted.intValue() == client.getVolume().getPercent()) {
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
