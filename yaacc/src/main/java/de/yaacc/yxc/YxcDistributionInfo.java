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
package de.yaacc.yxc;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Answer of {@code dist/getDistributionInfo}: the state of a device in a group ("link").
 * <p>
 * Verified on three devices (2026-11-02): a device in no group says {@code "role": "none"} with a group id of 32 zeros
 * and an empty client list; a server says {@code "server"}, a {@code status} ({@code working}) and lists its clients with
 * {@code ip_address} and {@code data_type}. The word of a client is not captured yet; {@code client} is what the
 * open-source libraries use. The role is kept as the raw word and as {@link Role}; without a usable word, {@link Role#NONE}
 * is derived from the null group id and an empty client list. The {@code group_name} of a device in no group is a name the
 * device makes up, so it is never used for a decision. A device that is part of a surround set adds an {@code mc_surround}
 * object; only its presence and its role word are kept.
 */
public final class YxcDistributionInfo {
    /** The group id of a device that is in no group: 32 zeros. */
    /** The most clients taken from an answer (the devices allow 9 to 19). */
    static final int MAX_CLIENTS = 64;
    static final int MAX_ADDRESS_CHARS = 64;
    public static final String NULL_GROUP_ID = "00000000000000000000000000000000";

    public enum Role {NONE, SERVER, CLIENT, UNKNOWN}

    private final String groupId;
    private final String groupName;
    private final String roleWord;
    private final Role role;
    private final String serverZone;
    private final String status;
    private final List<YxcDistributionClient> clientEntries;
    private final List<String> clients;
    private final boolean mcSurround;
    private final String mcSurroundRole;

    private YxcDistributionInfo(JSONObject json) {
        groupId = YxcJson.string(json, "group_id");
        groupName = YxcJson.string(json, "group_name");
        roleWord = YxcJson.string(json, "role");
        serverZone = YxcJson.string(json, "server_zone");
        status = YxcJson.string(json, "status");
        List<YxcDistributionClient> entries = new ArrayList<>();
        List<String> ips = new ArrayList<>();
        for (JSONObject entry : YxcJson.objects(json, "client_list")) {
            if (entries.size() >= MAX_CLIENTS) {
                break; // a device lists a handful; the rest of a huge answer is not stored
            }
            YxcDistributionClient client = YxcDistributionClient.fromJson(entry);
            if (client != null && client.getIpAddress() != null && client.getIpAddress().length() <= MAX_ADDRESS_CHARS) {
                entries.add(client);
                ips.add(client.getIpAddress());
            }
        }
        clientEntries = Collections.unmodifiableList(entries);
        clients = Collections.unmodifiableList(ips);
        JSONObject surround = YxcJson.object(json, "mc_surround");
        mcSurround = surround != null;
        mcSurroundRole = YxcJson.string(surround, "role");
        role = deriveRole(roleWord, groupId, clients);
    }

    static YxcDistributionInfo fromJson(JSONObject json) {
        return new YxcDistributionInfo(json);
    }

    private static Role deriveRole(String word, String groupId, List<String> clients) {
        if (word != null) {
            String normalized = word.toLowerCase(Locale.ROOT);
            if ("server".equals(normalized)) {
                return Role.SERVER;
            }
            if ("client".equals(normalized)) {
                return Role.CLIENT;
            }
            if ("none".equals(normalized)) {
                return Role.NONE;
            }
        }
        return isNullGroupId(groupId) && clients.isEmpty() ? Role.NONE : Role.UNKNOWN;
    }

    /** True for no id, an empty id and an id of zeros only (the id of a device that is in no group). */
    public static boolean isNullGroupId(String groupId) {
        if (groupId == null) {
            return true;
        }
        for (int i = 0; i < groupId.length(); i++) {
            if (groupId.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /** @return the group id as reported, {@code null} if absent */
    public String getGroupId() {
        return groupId;
    }

    public boolean isNullGroup() {
        return isNullGroupId(groupId);
    }

    /**
     * @return the name of the group, {@code null} if absent. A device in no group reports a name it makes up ({@code (Linked)}
     * and its room name), so this is for display only and never for a decision.
     */
    public String getGroupName() {
        return groupName;
    }

    /** @return the role word exactly as the device sent it, {@code null} if absent */
    public String getRoleWord() {
        return roleWord;
    }

    public Role getRole() {
        return role;
    }

    /** @return the zone that serves the group, {@code null} or empty if the device does not serve one */
    public String getServerZone() {
        return serverZone;
    }

    /** @return the IP addresses of the clients of a server; empty for other roles */
    public List<String> getClients() {
        return clients;
    }

    /** @return the clients of a server with their data type; empty for other roles */
    public List<YxcDistributionClient> getClientEntries() {
        return clientEntries;
    }

    /** @return the {@code status} word of a server (seen: {@code working}), {@code null} if absent (a device in no group has none) */
    public String getStatus() {
        return status;
    }

    /** @return true when the answer has an {@code mc_surround} object */
    public boolean hasMcSurround() {
        return mcSurround;
    }

    /** @return the role word inside {@code mc_surround} (seen: {@code none}), {@code null} if absent */
    public String getMcSurroundRole() {
        return mcSurroundRole;
    }
}
