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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;

/**
 * The members of a group as rows of the group sheet: name, connected flag, volume, mute and latency. Pure.
 *
 * <p><b>Names.</b> The name of a member is the name set for it, else its host name, else a generic label (never its id,
 * which is a MAC address); it is display text and goes through {@link SnapcastDisplayText}. Members with the same name
 * are told apart by a number ("Speaker", "Speaker (2)").
 *
 * <p><b>Order.</b> By name (ignoring case), then by id. Neither the volume nor the connection state is part of the
 * order, so a row does not jump under the finger when a client reconnects or its volume changes. The client id
 * decides ties and is never shown.
 */
public final class SnapcastMemberRows {

    /** The longest member name shown. */
    public static final int MAX_NAME = 64;

    /** The one localized word. */
    public interface Words {
        /** The label of a client that has neither a name nor a host name. */
        String unnamedClient();
    }

    /** One member row. Immutable. */
    public static final class Member {
        private final String clientId;
        private final String name;
        private final boolean connected;
        private final int volume;
        private final boolean muted;
        private final int latencyMs;

        Member(String clientId, String name, boolean connected, int volume, boolean muted, int latencyMs) {
            this.clientId = clientId;
            this.name = name;
            this.connected = connected;
            this.volume = volume;
            this.muted = muted;
            this.latencyMs = latencyMs;
        }

        /**
         * The id the server uses for the client, for the calls that change it. Never shown and never logged (it is
         * the MAC address of the machine).
         */
        public String getClientId() {
            return clientId;
        }

        /** The name to show: cleaned display text, made unique within the group. */
        public String getName() {
            return name;
        }

        public boolean isConnected() {
            return connected;
        }

        /** The volume, 0 to 100. */
        public int getVolume() {
            return volume;
        }

        public boolean isMuted() {
            return muted;
        }

        /** The latency setting in milliseconds, 0 to 10,000. */
        public int getLatencyMs() {
            return latencyMs;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Member)) {
                return false;
            }
            Member that = (Member) other;
            return connected == that.connected && volume == that.volume && muted == that.muted
                    && latencyMs == that.latencyMs && clientId.equals(that.clientId) && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId, name, connected, volume, muted, latencyMs);
        }

        /** Numbers only: the name and the id are the server's. */
        @Override
        public String toString() {
            return "SnapcastMemberRows.Member{connected=" + connected + ", volume=" + volume + ", muted=" + muted + "}";
        }
    }

    private SnapcastMemberRows() {
    }

    /** @return the rows of the group's members in the order described above; empty for a group without members */
    public static List<Member> of(SnapcastGroup group, Words words) {
        List<SnapcastClient> clients = new ArrayList<>(group.getClients());
        final String unnamed = words.unnamedClient();
        final List<String> base = new ArrayList<>();
        for (SnapcastClient client : clients) {
            base.add(baseName(client, unnamed));
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
        List<String> sortedBase = new ArrayList<>(order.size());
        for (int index : order) {
            sortedBase.add(base.get(index));
        }
        List<String> names = uniqueNames(sortedBase);
        List<Member> rows = new ArrayList<>(clients.size());
        for (int position = 0; position < order.size(); position++) {
            SnapcastClient client = clients.get(order.get(position));
            rows.add(new Member(client.getId(), names.get(position), client.isConnected(), client.getVolume().getPercent(),
                    client.getVolume().isMuted(), client.getLatencyMs()));
        }
        return rows;
    }

    /**
     * Makes names that are shown together different: the second "Speaker" becomes "Speaker (2)", the third "Speaker (3)".
     * A number that another name already uses is skipped, so the result never has a duplicate.
     *
     * @param names the names in the order they are shown
     */
    static List<String> uniqueNames(List<String> names) {
        Set<String> taken = new HashSet<>(names);
        Set<String> used = new HashSet<>();
        List<String> result = new ArrayList<>(names.size());
        for (String name : names) {
            String unique = name;
            if (!used.add(unique)) {
                int number = 2;
                unique = name + " (" + number + ")";
                while (taken.contains(unique) || used.contains(unique)) {
                    number++;
                    unique = name + " (" + number + ")";
                }
                used.add(unique);
            }
            result.add(unique);
        }
        return result;
    }

    static String sanitizedName(String name, String hostName, String unnamed) {
        String cleaned = SnapcastDisplayText.of(name, MAX_NAME).trim();
        if (cleaned.isEmpty()) {
            cleaned = SnapcastDisplayText.of(hostName, MAX_NAME).trim();
        }
        return cleaned.isEmpty() ? unnamed : cleaned;
    }

    private static String baseName(SnapcastClient client, String unnamed) {
        return sanitizedName(client.getName(), client.getHostName(), unnamed);
    }

    /**
     * A member can leave the group unless it is the last one: {@code Group.SetClients} with an empty list would dissolve
     * the group, and the library refuses it.
     */
    public static boolean canRemove(SnapcastGroup group) {
        return group.getClients().size() > 1;
    }
}
