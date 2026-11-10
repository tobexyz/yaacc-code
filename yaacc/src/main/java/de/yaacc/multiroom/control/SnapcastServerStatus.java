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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The whole state of a server as {@code Server.GetStatus} (and {@code Server.OnUpdate}) report it: the server's own
 * information, the groups with their clients, and the streams. Immutable: a change makes a new status
 * ({@link #withGroup}, {@link #withStream}). Parsing is tolerant (unknown fields ignored, defaults for missing optional
 * ones) and bounded by {@link SnapcastLimits}.
 */
public final class SnapcastServerStatus {

    private final SnapcastServerInfo info;
    private final List<SnapcastGroup> groups;
    private final List<SnapcastStream> streams;

    public SnapcastServerStatus(SnapcastServerInfo info, List<SnapcastGroup> groups, List<SnapcastStream> streams) {
        this.info = info == null ? new SnapcastServerInfo("", "", "", 0, 0) : info;
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        this.streams = Collections.unmodifiableList(new ArrayList<>(streams));
    }

    /**
     * Reads a status from text: the {@code result} of a {@code Server.GetStatus} answer or the {@code params} of a
     * {@code Server.OnUpdate} notification, that is {@code {"server": {...}}}.
     *
     * @throws SnapcastException of kind MALFORMED when the text is no such object
     */
    public static SnapcastServerStatus parseResult(String text) throws SnapcastException {
        return fromResult(SnapcastJson.parseObject(text));
    }

    /**
     * @param result an object with a {@code server} object inside
     * @throws SnapcastException of kind MALFORMED when there is none
     */
    public static SnapcastServerStatus fromResult(JSONObject result) throws SnapcastException {
        JSONObject server = SnapcastJson.object(result, "server");
        if (server == null) {
            throw SnapcastException.malformed("no server object in the status");
        }
        return fromServer(server);
    }

    private static SnapcastServerStatus fromServer(JSONObject server) {
        List<SnapcastGroup> groups = new ArrayList<>();
        Set<String> groupIds = new HashSet<>();
        Set<String> clientIds = new HashSet<>();
        for (JSONObject groupJson : SnapcastJson.objects(server, "groups", Integer.MAX_VALUE)) {
            if (groups.size() >= SnapcastLimits.MAX_GROUPS) {
                break;
            }
            String id = SnapcastJson.strictString(groupJson, "id");
            if (id == null || id.isEmpty() || !groupIds.add(id)) {
                continue;
            }
            SnapcastGroup group = SnapcastGroup.fromJson(groupJson, clientIds);
            if (group != null) {
                groups.add(group);
            }
        }
        List<SnapcastStream> streams = new ArrayList<>();
        Set<String> streamIds = new HashSet<>();
        for (JSONObject streamJson : SnapcastJson.objects(server, "streams", Integer.MAX_VALUE)) {
            if (streams.size() >= SnapcastLimits.MAX_STREAMS) {
                break;
            }
            SnapcastStream stream = SnapcastStream.fromJson(streamJson);
            if (stream != null && streamIds.add(stream.getId())) {
                streams.add(stream);
            }
        }
        return new SnapcastServerStatus(SnapcastServerInfo.fromJson(SnapcastJson.object(server, "server")), groups, streams);
    }

    public SnapcastServerInfo getInfo() {
        return info;
    }

    public List<SnapcastGroup> getGroups() {
        return groups;
    }

    public List<SnapcastStream> getStreams() {
        return streams;
    }

    /** @return the group with this id, or null */
    public SnapcastGroup findGroup(String groupId) {
        for (SnapcastGroup group : groups) {
            if (group.getId().equals(groupId)) {
                return group;
            }
        }
        return null;
    }

    /** @return the group that has this client as a member, or null */
    public SnapcastGroup findGroupOfClient(String clientId) {
        for (SnapcastGroup group : groups) {
            if (group.findClient(clientId) != null) {
                return group;
            }
        }
        return null;
    }

    /** @return the client with this id in any group, or null */
    public SnapcastClient findClient(String clientId) {
        SnapcastGroup group = findGroupOfClient(clientId);
        return group == null ? null : group.findClient(clientId);
    }

    /** @return the stream with this id, or null */
    public SnapcastStream findStream(String streamId) {
        for (SnapcastStream stream : streams) {
            if (stream.getId().equals(streamId)) {
                return stream;
            }
        }
        return null;
    }

    /** Replaces the group with the id of {@code group}, keeping its place; this status if there is no such group. */
    public SnapcastServerStatus withGroup(SnapcastGroup group) {
        List<SnapcastGroup> updated = new ArrayList<>(groups.size());
        boolean found = false;
        for (SnapcastGroup existing : groups) {
            if (existing.getId().equals(group.getId())) {
                updated.add(group);
                found = true;
            } else {
                updated.add(existing);
            }
        }
        return found ? new SnapcastServerStatus(info, updated, streams) : this;
    }

    /** Replaces the stream with the id of {@code stream}, keeping its place; this status if there is no such stream. */
    public SnapcastServerStatus withStream(SnapcastStream stream) {
        List<SnapcastStream> updated = new ArrayList<>(streams.size());
        boolean found = false;
        for (SnapcastStream existing : streams) {
            if (existing.getId().equals(stream.getId())) {
                updated.add(stream);
                found = true;
            } else {
                updated.add(existing);
            }
        }
        return found ? new SnapcastServerStatus(info, groups, updated) : this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastServerStatus)) {
            return false;
        }
        SnapcastServerStatus that = (SnapcastServerStatus) other;
        return info.equals(that.info) && groups.equals(that.groups) && streams.equals(that.streams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(info, groups, streams);
    }

    /** Counts only: names, hosts and addresses of a server do not go to logs. */
    @Override
    public String toString() {
        int clients = 0;
        for (SnapcastGroup group : groups) {
            clients += group.getClients().size();
        }
        return "SnapcastServerStatus{groups=" + groups.size() + ", clients=" + clients + ", streams=" + streams.size() + "}";
    }
}
