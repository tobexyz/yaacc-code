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

import java.util.Objects;

/**
 * One speaker of a Snapcast server (a snapclient). The id is the server's (the MAC address of the machine, with
 * {@code #instance} appended for a second client on it); name and host name are display text of the server and are
 * never used as an identity. Immutable.
 */
public final class SnapcastClient {

    private final String id;
    private final String name;
    private final String hostName;
    private final boolean connected;
    private final SnapcastVolume volume;
    private final int latencyMs;

    public SnapcastClient(String id, String name, String hostName, boolean connected, SnapcastVolume volume, int latencyMs) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null ? "" : SnapcastJson.truncate(name);
        this.hostName = hostName == null ? "" : SnapcastJson.truncate(hostName);
        this.connected = connected;
        this.volume = volume == null ? new SnapcastVolume(100, false) : volume;
        this.latencyMs = Math.max(0, Math.min(SnapcastLimits.MAX_LATENCY_MS, latencyMs));
    }

    /** @return the client, or null when the JSON has no id (such a client can not be addressed) */
    static SnapcastClient fromJson(JSONObject json) {
        String id = SnapcastJson.strictString(json, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        JSONObject config = SnapcastJson.object(json, "config");
        return new SnapcastClient(id,
                SnapcastJson.string(config, "name", ""),
                SnapcastJson.string(SnapcastJson.object(json, "host"), "name", ""),
                SnapcastJson.bool(json, "connected", false),
                SnapcastVolume.fromJson(SnapcastJson.object(config, "volume")),
                SnapcastJson.integer(config, "latency", 0, SnapcastLimits.MAX_LATENCY_MS, 0));
    }

    public String getId() {
        return id;
    }

    /** The name set for the client; empty when none was set. */
    public String getName() {
        return name;
    }

    /** The host name the client reported; empty when unknown. */
    public String getHostName() {
        return hostName;
    }

    /** The name to show: the set name, else the host name, else the id. */
    public String getDisplayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return hostName.isEmpty() ? id : hostName;
    }

    public boolean isConnected() {
        return connected;
    }

    public SnapcastVolume getVolume() {
        return volume;
    }

    /** The latency setting in milliseconds, 0 to 10,000. */
    public int getLatencyMs() {
        return latencyMs;
    }

    public SnapcastClient withName(String newName) {
        return new SnapcastClient(id, newName, hostName, connected, volume, latencyMs);
    }

    public SnapcastClient withConnected(boolean newConnected) {
        return new SnapcastClient(id, name, hostName, newConnected, volume, latencyMs);
    }

    public SnapcastClient withVolume(SnapcastVolume newVolume) {
        return new SnapcastClient(id, name, hostName, connected, newVolume, latencyMs);
    }

    public SnapcastClient withLatencyMs(int newLatencyMs) {
        return new SnapcastClient(id, name, hostName, connected, volume, newLatencyMs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastClient)) {
            return false;
        }
        SnapcastClient that = (SnapcastClient) other;
        return connected == that.connected && latencyMs == that.latencyMs && id.equals(that.id)
                && name.equals(that.name) && hostName.equals(that.hostName) && volume.equals(that.volume);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, hostName, connected, volume, latencyMs);
    }

    /** No host or name: the text of a server does not go to logs. */
    @Override
    public String toString() {
        return "SnapcastClient{connected=" + connected + ", volume=" + volume + "}";
    }
}
