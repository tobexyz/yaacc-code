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
    private final long lastSeenMicros;

    public SnapcastClient(String id, String name, String hostName, boolean connected, SnapcastVolume volume, int latencyMs) {
        this(id, name, hostName, connected, volume, latencyMs, 0L);
    }

    /** @param lastSeenMicros when the server last saw the client, in microseconds since 1970 (0 when it does not say) */
    public SnapcastClient(String id, String name, String hostName, boolean connected, SnapcastVolume volume, int latencyMs,
                          long lastSeenMicros) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null ? "" : SnapcastJson.truncate(name);
        this.hostName = hostName == null ? "" : SnapcastJson.truncate(hostName);
        this.connected = connected;
        this.volume = volume == null ? new SnapcastVolume(100, false) : volume;
        this.latencyMs = Math.max(0, Math.min(SnapcastLimits.MAX_LATENCY_MS, latencyMs));
        this.lastSeenMicros = Math.max(0L, lastSeenMicros);
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
                SnapcastJson.integer(config, "latency", 0, SnapcastLimits.MAX_LATENCY_MS, 0),
                lastSeenMicros(SnapcastJson.object(json, "lastSeen")));
    }

    /** The latest second that counts (year 5138): a larger value is cut, so the arithmetic below can not overflow. */
    private static final long MAX_LAST_SEEN_SECONDS = 100_000_000_000L;

    /** {@code {"sec": 1488026416, "usec": 135973}} as microseconds; 0 for a missing object, a missing or odd part. */
    private static long lastSeenMicros(JSONObject lastSeen) {
        if (lastSeen == null) {
            return 0L;
        }
        long seconds = SnapcastJson.longInRange(lastSeen, "sec", 0L, MAX_LAST_SEEN_SECONDS, 0L);
        long micros = SnapcastJson.longInRange(lastSeen, "usec", 0L, 999_999L, 0L);
        return seconds * 1_000_000L + micros;
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
        return new SnapcastClient(id, newName, hostName, connected, volume, latencyMs, lastSeenMicros);
    }

    public SnapcastClient withConnected(boolean newConnected) {
        return new SnapcastClient(id, name, hostName, newConnected, volume, latencyMs, lastSeenMicros);
    }

    public SnapcastClient withVolume(SnapcastVolume newVolume) {
        return new SnapcastClient(id, name, hostName, connected, newVolume, latencyMs, lastSeenMicros);
    }

    public SnapcastClient withLatencyMs(int newLatencyMs) {
        return new SnapcastClient(id, name, hostName, connected, volume, newLatencyMs, lastSeenMicros);
    }

    /**
     * When the server last saw the client ({@code lastSeen} of the status: seconds and microseconds), as microseconds since 1970;
     * 0 when the status does not say (a missing or unreadable value). Only used to decide which server shows a client that is
     * connected nowhere (task 12.12).
     */
    public long getLastSeenMicros() {
        return lastSeenMicros;
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
        return connected == that.connected && latencyMs == that.latencyMs && lastSeenMicros == that.lastSeenMicros && id.equals(that.id)
                && name.equals(that.name) && hostName.equals(that.hostName) && volume.equals(that.volume);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, hostName, connected, volume, latencyMs, lastSeenMicros);
    }

    /** No host or name: the text of a server does not go to logs. */
    @Override
    public String toString() {
        return "SnapcastClient{connected=" + connected + ", volume=" + volume + "}";
    }
}
