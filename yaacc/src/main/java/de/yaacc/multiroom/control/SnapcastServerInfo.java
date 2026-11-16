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

/** What the server says about itself in a status. All values are display text of the server. Immutable. */
public final class SnapcastServerInfo {

    private final String hostName;
    private final String serverName;
    private final String version;
    private final int protocolVersion;
    private final int controlProtocolVersion;

    public SnapcastServerInfo(String hostName, String serverName, String version, int protocolVersion,
                              int controlProtocolVersion) {
        this.hostName = hostName == null ? "" : SnapcastJson.truncate(hostName);
        this.serverName = serverName == null ? "" : SnapcastJson.truncate(serverName);
        this.version = version == null ? "" : SnapcastJson.truncate(version);
        this.protocolVersion = protocolVersion;
        this.controlProtocolVersion = controlProtocolVersion;
    }

    /** @param json the {@code server.server} object of a status; may be null */
    static SnapcastServerInfo fromJson(JSONObject json) {
        JSONObject snapserver = SnapcastJson.object(json, "snapserver");
        return new SnapcastServerInfo(
                SnapcastJson.string(SnapcastJson.object(json, "host"), "name", ""),
                SnapcastJson.string(snapserver, "name", ""),
                SnapcastJson.string(snapserver, "version", ""),
                SnapcastJson.integer(snapserver, "protocolVersion", 0, 1_000_000, 0),
                SnapcastJson.integer(snapserver, "controlProtocolVersion", 0, 1_000_000, 0));
    }

    /** The host name of the machine the server runs on; empty when it did not say. */
    public String getHostName() {
        return hostName;
    }

    public String getServerName() {
        return serverName;
    }

    public String getVersion() {
        return version;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public int getControlProtocolVersion() {
        return controlProtocolVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapcastServerInfo)) {
            return false;
        }
        SnapcastServerInfo that = (SnapcastServerInfo) other;
        return protocolVersion == that.protocolVersion && controlProtocolVersion == that.controlProtocolVersion
                && hostName.equals(that.hostName) && serverName.equals(that.serverName) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostName, serverName, version, protocolVersion, controlProtocolVersion);
    }

    /** Numbers only: host, name and version are text of the server. */
    @Override
    public String toString() {
        return "SnapcastServerInfo{protocolVersion=" + protocolVersion + ", controlProtocolVersion=" + controlProtocolVersion + "}";
    }
}
