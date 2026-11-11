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
package de.yaacc.multiroom.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastLimits;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * The clients that can be added to a group, and the member lists that the calls need. Pure.
 *
 * <p><b>The rule of {@code Group.SetClients}.</b> The call takes the <i>full</i> list of members the group should have.
 * Clients of the group that are not in the list leave it, and each of them gets a group of its own; clients in the list
 * are moved in from their groups (a group that loses its last client disappears). So "add client" is the current members
 * plus the chosen ones ({@link #wantedWith}), "remove from group" is the current members minus one
 * ({@link #wantedWithout}), and the last member can not be removed (an empty list would dissolve the group; the library
 * refuses it).
 *
 * <p>Only clients of the <b>same server</b> are candidates: a status is one server's.
 */
public final class SnapcastClientCandidates {

    /** The localized labels for nameless clients and groups. */
    public interface Words {
        String unnamedClient();

        String unnamedGroup();
    }

    /** A client of another group of the same server. Immutable. */
    public static final class Candidate {
        private final String clientId;
        private final String name;
        private final boolean connected;
        private final String fromGroupName;
        private final boolean leavesGroupEmpty;

        Candidate(String clientId, String name, boolean connected, String fromGroupName, boolean leavesGroupEmpty) {
            this.clientId = clientId;
            this.name = name;
            this.connected = connected;
            this.fromGroupName = fromGroupName;
            this.leavesGroupEmpty = leavesGroupEmpty;
        }

        /** The server's id of the client, for the call; never shown or logged. */
        public String getClientId() {
            return clientId;
        }

        /** The name to show (cleaned, unique within the list). */
        public String getName() {
            return name;
        }

        public boolean isConnected() {
            return connected;
        }

        /** The name of the group the client is in now (cleaned display text). */
        public String getFromGroupName() {
            return fromGroupName;
        }

        /** True when the client is the only member of its group, which then disappears. */
        public boolean leavesGroupEmpty() {
            return leavesGroupEmpty;
        }

        @Override
        public String toString() {
            return "SnapcastClientCandidates.Candidate{connected=" + connected + "}";
        }
    }

    private SnapcastClientCandidates() {
    }

    /**
     * @param status  the server's status; null gives an empty list
     * @param groupId the group that gets the clients
     * @return the clients of the other groups, by name and then id; empty when the group is not in the status
     */
    public static List<Candidate> addable(SnapcastServerStatus status, String groupId, Words words) {
        List<Candidate> none = Collections.emptyList();
        if (status == null || status.findGroup(groupId) == null) {
            return none;
        }
        final List<SnapcastClient> clients = new ArrayList<>();
        final List<SnapcastGroup> owners = new ArrayList<>();
        for (SnapcastGroup other : status.getGroups()) {
            if (other.getId().equals(groupId)) {
                continue;
            }
            for (SnapcastClient client : other.getClients()) {
                clients.add(client);
                owners.add(other);
            }
        }
        final List<String> base = new ArrayList<>();
        for (SnapcastClient client : clients) {
            base.add(SnapcastMemberRows.sanitizedName(client.getName(), client.getHostName(), words.unnamedClient()));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                String left = base.get(a);
                String right = base.get(b);
                int byName = left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
                if (byName == 0) {
                    byName = left.compareTo(right);
                }
                return byName != 0 ? byName : clients.get(a).getId().compareTo(clients.get(b).getId());
            }
        });
        List<String> sortedBase = new ArrayList<>();
        for (int index : order) {
            sortedBase.add(base.get(index));
        }
        List<String> names = SnapcastMemberRows.uniqueNames(sortedBase);
        List<Candidate> result = new ArrayList<>(order.size());
        for (int position = 0; position < order.size(); position++) {
            int index = order.get(position);
            SnapcastClient client = clients.get(index);
            SnapcastGroup owner = owners.get(index);
            result.add(new Candidate(client.getId(), names.get(position), client.isConnected(),
                    groupName(owner, words), owner.getClients().size() == 1));
        }
        return result;
    }

    /** The name of a group: its own, else its first member's, else the label. Cleaned display text. */
    private static String groupName(SnapcastGroup group, Words words) {
        String name = SnapcastDisplayText.of(group.getName(), SnapcastMemberRows.MAX_NAME).trim();
        if (name.isEmpty() && !group.getClients().isEmpty()) {
            SnapcastClient first = group.getClients().get(0);
            name = SnapcastMemberRows.sanitizedName(first.getName(), first.getHostName(), "");
        }
        return name.isEmpty() ? words.unnamedGroup() : name;
    }

    /**
     * The full member list for {@code Group.SetClients} when {@code addedIds} join the group: the current members in
     * their order, then the new ones. Empty and duplicate ids are skipped.
     *
     * @return the list, or null when it would be longer than {@link SnapcastLimits#MAX_CLIENTS_PER_GROUP}
     */
    public static List<String> wantedWith(SnapcastGroup group, java.util.Collection<String> addedIds) {
        Set<String> wanted = new LinkedHashSet<>();
        for (SnapcastClient member : group.getClients()) {
            wanted.add(member.getId());
        }
        if (addedIds != null) {
            for (String id : addedIds) {
                if (id != null && !id.isEmpty()) {
                    wanted.add(id);
                }
            }
        }
        return wanted.size() > SnapcastLimits.MAX_CLIENTS_PER_GROUP ? null : new ArrayList<>(wanted);
    }

    /**
     * The full member list for {@code Group.SetClients} when one member leaves: the other members in their order.
     *
     * @return the list, or null when {@code clientId} is no member, or is the last one
     */
    public static List<String> wantedWithout(SnapcastGroup group, String clientId) {
        if (clientId == null || clientId.isEmpty() || group.findClient(clientId) == null || group.getClients().size() < 2) {
            return null;
        }
        List<String> wanted = new ArrayList<>();
        for (SnapcastClient member : group.getClients()) {
            if (!member.getId().equals(clientId)) {
                wanted.add(member.getId());
            }
        }
        return wanted;
    }
}
