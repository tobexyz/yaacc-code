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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A group of clients that play one stream. The id is a UUID made by the server. Immutable. */
public final class SnapcastGroup {

    private final String id;
    private final String name;
    private final boolean muted;
    private final String streamId;
    private final List<SnapcastClient> clients;

    public SnapcastGroup(String id, String name, boolean muted, String streamId, List<SnapcastClient> clients) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null ? "" : SnapcastJson.truncate(name);
        this.muted = muted;
        this.streamId = streamId == null ? "" : SnapcastJson.truncate(streamId);
        List<SnapcastClient> copy = new ArrayList<>(clients.size());
        for (SnapcastClient client : clients) {
            if (copy.size() >= SnapcastLimits.MAX_CLIENTS_PER_GROUP) {
                break;
            }
            copy.add(client);
        }
        this.clients = Collections.unmodifiableList(copy);
    }

    /**
     * @return the group, or null when the JSON has no id
     * @param seenClientIds the ids of the clients of the groups read so far; a client that was seen already is skipped, so
     *                      that an id is found in one place only (the set grows with the clients of this group)
     */
    static SnapcastGroup fromJson(JSONObject json, Set<String> seenClientIds) {
        String id = SnapcastJson.strictString(json, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        List<SnapcastClient> clients = new ArrayList<>();
        for (JSONObject clientJson : SnapcastJson.objects(json, "clients", Integer.MAX_VALUE)) {
            if (clients.size() >= SnapcastLimits.MAX_CLIENTS_PER_GROUP) {
                break;
            }
            SnapcastClient client = SnapcastClient.fromJson(clientJson);
            if (client != null && seenClientIds.add(client.getId())) {
                clients.add(client);
            }
        }
        return new SnapcastGroup(id,
                SnapcastJson.string(json, "name", ""),
                SnapcastJson.bool(json, "muted", false),
                SnapcastJson.string(json, "stream_id", ""),
                clients);
    }

    public String getId() {
        return id;
    }

    /** The name set for the group; empty when none was set. */
    public String getName() {
        return name;
    }

    public boolean isMuted() {
        return muted;
    }

    /** The id of the stream the group plays; empty when it has none. */
    public String getStreamId() {
        return streamId;
    }

    public List<SnapcastClient> getClients() {
        return clients;
    }

    /** @return the member with this id, or null */
    public SnapcastClient findClient(String clientId) {
        for (SnapcastClient client : clients) {
            if (client.getId().equals(clientId)) {
                return client;
            }
        }
        return null;
    }

    public SnapcastGroup withName(String newName) {
        return new SnapcastGroup(id, newName, muted, streamId, clients);
    }

    public SnapcastGroup withMuted(boolean newMuted) {
        return new SnapcastGroup(id, name, newMuted, streamId, clients);
    }

    public SnapcastGroup withStreamId(String newStreamId) {
        return new SnapcastGroup(id, name, muted, newStreamId, clients);
    }

    /** Replaces the member with the id of {@code client}; this group if there is no such member. */
    public SnapcastGroup withClient(SnapcastClient client) {
        List<SnapcastClient> updated = new ArrayList<>(clients.size());
        boolean found = false;
        for (SnapcastClient existing : clients) {
            if (existing.getId().equals(client.getId())) {
                updated.add(client);
                found = true;
            } else {
                updated.add(existing);
            }
        }
        return found ? new SnapcastGroup(id, name, muted, streamId, updated) : this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastGroup)) {
            return false;
        }
        SnapcastGroup that = (SnapcastGroup) other;
        return muted == that.muted && id.equals(that.id) && name.equals(that.name) && streamId.equals(that.streamId)
                && clients.equals(that.clients);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, muted, streamId, clients);
    }

    @Override
    public String toString() {
        return "SnapcastGroup{clients=" + clients.size() + ", muted=" + muted + "}";
    }
}
