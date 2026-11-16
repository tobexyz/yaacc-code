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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * What the group chooser of the renderer settings offers (task 9.1): first the servers of the server list (typed in, found
 * on the network, remembered; the list of the settings dialog is reused, nothing new is discovered), then the groups of the
 * chosen server. Pure Java; the dialog only shows the choices and stores the receiver id of the chosen group.
 *
 * <ul>
 *   <li><b>The key of a server is its control endpoint</b> ({@code host:port} of the list), because the groups live there;
 *   it is not the stream port that the client settings use ({@link SnapcastServerChoices}).</li>
 *   <li><b>Labels are display text.</b> The label starts with the address and puts the cleaned name from the network in
 *   brackets ({@link SnapcastServerChoices#label}), so a name that looks like an address can not stand in front of the real
 *   one. A group name is cleaned again and never carries its id.</li>
 *   <li><b>What is stored is an id.</b> A group's receiver id is built from the server key and the group id the server
 *   gave ({@link SnapcastGroupDevice#idOf}); only a group that {@link SnapcastGroupDevice} accepted (a safe id) is a
 *   choice.</li>
 * </ul>
 */
public final class SnapcastRendererGroupChoices {

    /** The group's own name is at most 64 characters, plus the product suffix and the offline marker the device adds. */
    private static final int MAX_GROUP_NAME = 96;

    private SnapcastRendererGroupChoices() {
    }

    /** A server to choose. Immutable. */
    public static final class Server {
        private final SnapcastServerKey key;
        private final String label;
        private final SnapcastServerListModel.Kind kind;

        Server(SnapcastServerKey key, String label, SnapcastServerListModel.Kind kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }

        public SnapcastServerKey key() {
            return key;
        }

        public String label() {
            return label;
        }

        public SnapcastServerListModel.Kind kind() {
            return kind;
        }

        @Override
        public String toString() {
            return "SnapcastRendererGroupChoices.Server{" + kind + "}";
        }
    }

    /** A group to choose. Immutable. */
    public static final class Group {
        private final String receiverId;
        private final String name;
        private final int clients;

        Group(String receiverId, String name, int clients) {
            this.receiverId = receiverId;
            this.name = name;
            this.clients = clients;
        }

        /** What the setting stores: {@code snapcast:<server key>:<group id>}. */
        public String receiverId() {
            return receiverId;
        }

        /** The name for the list (display text, cleaned). */
        public String name() {
            return name;
        }

        /** The number of members of the group. */
        public int clients() {
            return clients;
        }

        @Override
        public String toString() {
            return "SnapcastRendererGroupChoices.Group{clients=" + clients + "}";
        }
    }

    /** @param rows the rows of the server list, in their order; null for none. Each server once. */
    public static List<Server> servers(List<SnapcastServerListModel.Row> rows) {
        List<Server> servers = new ArrayList<>();
        if (rows == null) {
            return servers;
        }
        Set<SnapcastServerKey> seen = new HashSet<>();
        for (SnapcastServerListModel.Row row : rows) {
            if (row == null || row.getKey() == null || !seen.add(row.getKey())) {
                continue;
            }
            String address = row.getKey().asString();
            String name = SnapcastDisplayText.of(row.getName(), SnapcastServerListModel.MAX_NAME).trim();
            servers.add(new Server(row.getKey(), SnapcastServerChoices.label(name, address), row.getKind()));
        }
        return servers;
    }

    /** @param devices the groups of one server (as {@code SnapcastReceivers.groupsOf} gives them); null for none */
    public static List<Group> groups(List<SnapcastGroupDevice> devices) {
        List<Group> groups = new ArrayList<>();
        if (devices == null) {
            return groups;
        }
        for (SnapcastGroupDevice device : devices) {
            if (device == null) {
                continue;
            }
            String name = SnapcastDisplayText.of(device.getDisplayString(), MAX_GROUP_NAME).trim();
            groups.add(new Group(SnapcastGroupDevice.idOf(device.getServerKey(), device.getGroupId()), name,
                    device.getClientCount()));
        }
        return groups;
    }
}
