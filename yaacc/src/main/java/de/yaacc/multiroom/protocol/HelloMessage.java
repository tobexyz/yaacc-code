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
package de.yaacc.multiroom.protocol;

import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastJson;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * What the client tells the server when it joins. The server and every controller of the server see these values, so
 * the disclosure is deliberate (design, "Disclosure (Hello)"): {@code MAC} is a fixed placeholder (Android does not
 * expose the real one and it would identify the hardware), {@code ID} is the stable identifier the caller derives from
 * the YAACC device, there is no {@code Auth}, and every text is cleaned and cut like display text.
 */
public final class HelloMessage implements SnapcastBinaryMessage {

    /** The MAC address that is sent instead of a real one. */
    public static final String MAC_PLACEHOLDER = "00:00:00:00:00:00";
    /** The version of the stream protocol (2 since Snapcast 0.x, see the protocol documentation). */
    public static final int PROTOCOL_VERSION = 2;
    /** Characters kept of every text field. */
    public static final int MAX_FIELD_LENGTH = 128;

    private final String id;
    private final String hostName;
    private final String clientName;
    private final String os;
    private final String arch;
    private final String version;
    private final String mac;
    private final int instance;
    private final int protocolVersion;

    private HelloMessage(String id, String hostName, String clientName, String os, String arch, String version, String mac,
                         int instance, int protocolVersion) {
        this.id = clean(id);
        this.hostName = clean(hostName);
        this.clientName = clean(clientName);
        this.os = clean(os);
        this.arch = clean(arch);
        this.version = clean(version);
        this.mac = clean(mac);
        this.instance = instance;
        this.protocolVersion = protocolVersion;
    }

    private static String clean(String text) {
        return SnapcastDisplayText.of(text, MAX_FIELD_LENGTH);
    }

    /**
     * The Hello of this client.
     *
     * @param id         a stable identifier of this phone for the server (not the hardware id); required
     * @param hostName   the name of the phone as the UPnP device already announces it
     * @param clientName the name of the program, for example "YAACC"
     * @throws IllegalArgumentException when the id is missing or blank
     */
    public static HelloMessage create(String id, String hostName, String clientName, String os, String arch, String version) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("a client id is required");
        }
        return new HelloMessage(id, hostName, clientName, os, arch, version, MAC_PLACEHOLDER, 1, PROTOCOL_VERSION);
    }

    public String getId() {
        return id;
    }

    public String getHostName() {
        return hostName;
    }

    public String getClientName() {
        return clientName;
    }

    public String getOs() {
        return os;
    }

    public String getArch() {
        return arch;
    }

    public String getVersion() {
        return version;
    }

    public String getMac() {
        return mac;
    }

    public int getInstance() {
        return instance;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public MessageType type() {
        return MessageType.HELLO;
    }

    byte[] encodeBody() {
        try {
            JSONObject json = new JSONObject();
            json.put("Arch", arch);
            json.put("ClientName", clientName);
            json.put("HostName", hostName);
            json.put("ID", id);
            json.put("Instance", instance);
            json.put("MAC", mac);
            json.put("OS", os);
            json.put("SnapStreamProtocolVersion", protocolVersion);
            json.put("Version", version);
            return JsonBodies.write(json.toString());
        } catch (JSONException e) {
            throw new IllegalStateException("could not write the Hello", e);
        }
    }

    static HelloMessage decode(ByteReader reader) throws SnapcastProtocolException {
        JSONObject json = JsonBodies.read(reader);
        return new HelloMessage(text(json, "ID"), text(json, "HostName"), text(json, "ClientName"), text(json, "OS"),
                text(json, "Arch"), text(json, "Version"), text(json, "MAC"),
                SnapcastJson.integer(json, "Instance", 0, 65535, 1),
                SnapcastJson.integer(json, "SnapStreamProtocolVersion", 0, 65535, 0));
    }

    private static String text(JSONObject json, String key) {
        return SnapcastJson.string(json, key, "");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof HelloMessage)) {
            return false;
        }
        HelloMessage that = (HelloMessage) other;
        return instance == that.instance && protocolVersion == that.protocolVersion && id.equals(that.id)
                && hostName.equals(that.hostName) && clientName.equals(that.clientName) && os.equals(that.os)
                && arch.equals(that.arch) && version.equals(that.version) && mac.equals(that.mac);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, hostName, clientName, os, arch, version, mac, instance, protocolVersion);
    }
}
