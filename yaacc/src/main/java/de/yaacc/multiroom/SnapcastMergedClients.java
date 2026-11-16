/*
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
package de.yaacc.multiroom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * A client known to several servers is listed <b>once</b> (task 12.12). Snapcast clients keep an entry on every server they ever
 * connected to, so the phone (one client id for all servers) or a Raspberry Pi that moved shows up as an offline leftover on the old
 * server next to its live entry on the new one. This is a <b>filter over the statuses of all known servers</b>, a view for the
 * lists; nothing is changed or removed on a server, and the group keys stay.
 *
 * <p><b>The rule</b> (one client id at a time):
 * <ul>
 *   <li>the id is on one server only: shown there;</li>
 *   <li>connected on a server: shown there, and not as a leftover on the others. A server whose control connection is down counts
 *   as "not connected" for this (its last status may still say "connected", which can not be true any more);</li>
 *   <li>connected nowhere: shown on the server that saw it last ({@code lastSeen}; a missing one is 0, the oldest); a tie goes to the
 *   first server of the known order, the order of the list given to {@link #of};</li>
 *   <li>connected on <b>two or more</b> servers at once (it should not happen: a client has one connection): shown on all of them,
 *   nothing is hidden among live entries; offline copies on yet other servers are hidden.</li>
 * </ul>
 *
 * <p><b>Display only.</b> {@link #visible(SnapcastServerKey, SnapcastGroup)} and {@link #visible(SnapcastServerKey,
 * SnapcastServerStatus)} give what the lists show: the card (title from the first shown member), the member rows, the counts "x of
 * y connected", the link picker. A group whose members are all hidden gives {@code null} (no card; a group that never had a member is
 * kept). The control code must keep working on the full status of a fresh read: {@code Group.SetClients} takes the whole list of
 * members, so a hidden member has to be in it, or the change would drop it from the group. Nothing here is used by that code.
 *
 * <p>Pure Java, immutable. Where nothing is hidden the very same objects come back.
 */
public final class SnapcastMergedClients {

    /** One known server: its key, the last status it delivered (null: none yet) and whether its control connection is up. */
    public static final class Server {
        private final SnapcastServerKey key;
        private final SnapcastServerStatus status;
        private final boolean online;

        private Server(SnapcastServerKey key, SnapcastServerStatus status, boolean online) {
            this.key = key;
            this.status = status;
            this.online = online;
        }

        public static Server of(SnapcastServerKey key, SnapcastServerStatus status, boolean online) {
            return new Server(key, status, online);
        }
    }

    private static final SnapcastMergedClients NONE = new SnapcastMergedClients(Collections.<SnapcastServerKey, Set<String>>emptyMap());

    private final Map<SnapcastServerKey, Set<String>> hidden;

    private SnapcastMergedClients(Map<SnapcastServerKey, Set<String>> hidden) {
        this.hidden = hidden;
    }

    /** Nothing is hidden. */
    public static SnapcastMergedClients none() {
        return NONE;
    }

    /** One copy of a client id on one server. */
    private static final class Copy {
        final SnapcastServerKey key;
        final boolean connected;
        final long lastSeenMicros;

        Copy(SnapcastServerKey key, boolean connected, long lastSeenMicros) {
            this.key = key;
            this.connected = connected;
            this.lastSeenMicros = lastSeenMicros;
        }
    }

    /**
     * @param servers every known server in the known order (the first one wins a tie); a server without a status is skipped
     */
    public static SnapcastMergedClients of(List<Server> servers) {
        if (servers == null || servers.size() < 2) {
            return NONE; // one server: nothing to merge
        }
        Map<String, List<Copy>> copies = new LinkedHashMap<>();
        Set<SnapcastServerKey> seen = new HashSet<>();
        for (Server server : servers) {
            if (server == null || server.key == null || server.status == null || !seen.add(server.key)) {
                continue;
            }
            for (SnapcastGroup group : server.status.getGroups()) {
                for (SnapcastClient client : group.getClients()) {
                    List<Copy> list = copies.get(client.getId());
                    if (list == null) {
                        list = new ArrayList<>(2);
                        copies.put(client.getId(), list);
                    }
                    list.add(new Copy(server.key, server.online && client.isConnected(), client.getLastSeenMicros()));
                }
            }
        }
        Map<SnapcastServerKey, Set<String>> hidden = new HashMap<>();
        for (Map.Entry<String, List<Copy>> entry : copies.entrySet()) {
            List<Copy> list = entry.getValue();
            if (list.size() < 2) {
                continue;
            }
            Set<SnapcastServerKey> shownOn = new HashSet<>();
            for (Copy copy : list) {
                if (copy.connected) {
                    shownOn.add(copy.key);
                }
            }
            if (shownOn.isEmpty()) {
                Copy last = list.get(0); // the first of the known order wins a tie: only a strictly later one replaces it
                for (Copy copy : list) {
                    if (copy.lastSeenMicros > last.lastSeenMicros) {
                        last = copy;
                    }
                }
                shownOn.add(last.key);
            }
            for (Copy copy : list) {
                if (!shownOn.contains(copy.key)) {
                    Set<String> ids = hidden.get(copy.key);
                    if (ids == null) {
                        ids = new HashSet<>();
                        hidden.put(copy.key, ids);
                    }
                    ids.add(entry.getKey());
                }
            }
        }
        return hidden.isEmpty() ? NONE : new SnapcastMergedClients(hidden);
    }

    /** @return true when the lists do not show this client of this server (it is shown on another server) */
    public boolean isHidden(SnapcastServerKey key, String clientId) {
        Set<String> ids = key == null ? null : hidden.get(key);
        return ids != null && clientId != null && ids.contains(clientId);
    }

    /**
     * @return the group as the lists show it: the members that are not hidden, in their order; the group itself when none is hidden;
     * null for a null group and for a group whose members are all hidden
     */
    public SnapcastGroup visible(SnapcastServerKey key, SnapcastGroup group) {
        Set<String> ids = key == null || group == null ? null : hidden.get(key);
        if (group == null) {
            return null;
        }
        if (ids == null) {
            return group;
        }
        List<SnapcastClient> shown = new ArrayList<>(group.getClients().size());
        for (SnapcastClient client : group.getClients()) {
            if (!ids.contains(client.getId())) {
                shown.add(client);
            }
        }
        if (shown.size() == group.getClients().size()) {
            return group;
        }
        if (shown.isEmpty()) {
            return null;
        }
        return new SnapcastGroup(group.getId(), group.getName(), group.isMuted(), group.getStreamId(), shown);
    }

    /**
     * @return the status as the lists show it: the visible groups (a group with no shown member is left out); the same status when
     * nothing is hidden in it; null for null
     */
    public SnapcastServerStatus visible(SnapcastServerKey key, SnapcastServerStatus status) {
        if (status == null || key == null || !hidden.containsKey(key)) {
            return status;
        }
        List<SnapcastGroup> groups = new ArrayList<>(status.getGroups().size());
        boolean changed = false;
        for (SnapcastGroup group : status.getGroups()) {
            SnapcastGroup shown = visible(key, group);
            changed |= shown != group;
            if (shown != null) {
                groups.add(shown);
            }
        }
        return changed ? new SnapcastServerStatus(status.getInfo(), groups, status.getStreams()) : status;
    }
}
