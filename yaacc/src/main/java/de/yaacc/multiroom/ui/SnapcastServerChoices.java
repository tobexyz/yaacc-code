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
package de.yaacc.multiroom.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.protocol.SnapcastConnection;

/**
 * The servers that the client settings (the listen test, later the renderer client) offer to choose from (task 11.7): the
 * servers of the Snapcast server list (typed in, found on the network, remembered) and the one that was used last, each
 * once. Pure Java; the dialog only shows the choices and puts the text of the chosen one into its address field, which the
 * client checks again as for any typed address.
 *
 * <ul>
 *   <li><b>The target is the address the user chose.</b> A choice carries the host of its row (a number or the name the user
 *   typed), never anything the network said about itself. The client connects to the <i>stream</i> port, which is not the
 *   control port of the list (the default 1704 against 1705), so a choice names the host and leaves the port to the
 *   client's default; the last used text keeps the port the user typed.</li>
 *   <li><b>Once.</b> Two rows with the same host (two control ports) lead to the same stream port, so they are one choice.</li>
 *   <li><b>Names are display text.</b> Cleaned and cut again, as in the server list; a name that looks like an address
 *   changes nothing, and the label starts with the host ({@code 10.0.0.9 (Living room)}), so a name such as
 *   {@code 192.168.1.5} can not stand in front of the real address.</li>
 *   <li><b>No prefill from the network.</b> {@link #prefill} fills an empty field only with a server the user made known
 *   (last used, typed in, remembered), never with one that was only found.</li>
 *   <li><b>Link-local IPv6.</b> A found or remembered server at an {@code fe80::} address is not offered: the text of a choice has
 *   no scope id, so the client could not connect to it (a dual-stack phone may resolve such an address first). One the user
 *   typed in stays a choice. A found or remembered server is offered with the default stream port 1704 whatever stream port
 *   it announced (the announced stream port of {@code _snapcast._tcp} is browsed for the report only); another stream port
 *   has to be typed.</li>
 *   <li><b>Order.</b> The last used first, then the typed-in servers, the remembered ones, the found ones, each by address.</li>
 * </ul>
 */
public final class SnapcastServerChoices {

    /** The most choices: every server of the list and the last used one. */
    public static final int MAX_CHOICES = 17;

    /** Where a choice comes from. */
    public enum Source {
        LAST_USED, TYPED, REMEMBERED, FOUND
    }

    /** One choice. Immutable. */
    public static final class Choice {
        private final String hostText;
        private final String label;
        private final Source source;
        private final boolean lastUsed;

        Choice(String hostText, String label, Source source, boolean lastUsed) {
            this.hostText = hostText;
            this.label = label;
            this.source = source;
            this.lastUsed = lastUsed;
        }

        /** What goes into the address field: {@code host} (IPv6 in brackets), with {@code :port} when it is not the default. */
        public String hostText() {
            return hostText;
        }

        /** The address first, then the name (cleaned) in brackets, or the address alone. */
        public String label() {
            return label;
        }

        public Source source() {
            return source;
        }

        /** This is the server the client used last. */
        public boolean isLastUsed() {
            return lastUsed;
        }

        @Override
        public String toString() {
            return "SnapcastServerChoices.Choice{" + source + "}";
        }
    }

    private SnapcastServerChoices() {
    }

    /**
     * @param rows     the rows of the server list (may be null)
     * @param lastUsed the text of the address field the client used last (may be null or damaged)
     */
    public static List<Choice> of(List<SnapcastServerListModel.Row> rows, String lastUsed) {
        Map<String, Choice> byTarget = new HashMap<>();
        List<Choice> others = new ArrayList<>();
        if (rows != null) {
            for (SnapcastServerListModel.Row row : rows) {
                if (row.getKind() != SnapcastServerListModel.Kind.MANUAL && isLinkLocalIpv6(row.getKey().getEndpoint())) {
                    continue; // its text has lost the scope id, so the client could not connect to it; the user can type it with one
                }
                String host = hostOnly(row.getKey().getEndpoint()); // the control port of the row is not the stream port
                SnapcastEndpoint target = SnapcastEndpoint.parse(host, SnapcastConnection.DEFAULT_PORT);
                if (target == null || byTarget.containsKey(target.toKey().asString())) {
                    continue;
                }
                Choice choice = new Choice(host, label(SnapcastServerListModel.cleanName(row.getName()), host), sourceOf(row.getKind()), false);
                byTarget.put(target.toKey().asString(), choice);
                others.add(choice);
            }
        }
        Collections.sort(others, new Comparator<Choice>() {
            @Override
            public int compare(Choice a, Choice b) {
                int bySource = a.source.compareTo(b.source);
                return bySource != 0 ? bySource : a.hostText.compareTo(b.hostText);
            }
        });
        List<Choice> result = new ArrayList<>();
        SnapcastEndpoint last = lastUsed == null || lastUsed.trim().isEmpty() ? null
                : SnapcastEndpoint.parse(lastUsed.trim(), SnapcastConnection.DEFAULT_PORT);
        if (last != null) {
            String text = hostText(last);
            Choice known = byTarget.get(last.toKey().asString());
            if (known != null) {
                others.remove(known);
                result.add(new Choice(known.hostText, known.label, known.source, true));
            } else {
                result.add(new Choice(text, text, Source.LAST_USED, true));
            }
        }
        result.addAll(others);
        return result.size() > MAX_CHOICES ? new ArrayList<>(result.subList(0, MAX_CHOICES)) : result;
    }

    /**
     * What the address field of the client settings may be filled with when it is empty (S67): the first choice, but only
     * when the user made it: the server used last, a typed-in server or a remembered one. A server that was only
     * <i>found</i> on the network is never prefilled (announcements are not authenticated): the user has to choose it.
     * The order of {@link #of} puts the user's own servers first, so a found server can only be first when there is none.
     *
     * @return the text for the field, or null for nothing
     */
    public static String prefill(List<Choice> choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice first = choices.get(0);
        return first.isLastUsed() || first.source != Source.FOUND ? first.hostText : null;
    }

    private static boolean isLinkLocalIpv6(SnapcastEndpoint endpoint) {
        java.net.InetAddress address = endpoint.getAddress();
        return address instanceof java.net.Inet6Address && address.isLinkLocalAddress();
    }

    private static String hostOnly(SnapcastEndpoint endpoint) {
        String host = endpoint.getHost();
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    /** {@code host} when the port is the stream port of the client, else {@code host:port}; IPv6 in brackets. */
    private static String hostText(SnapcastEndpoint endpoint) {
        String host = endpoint.getHost();
        String text = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return endpoint.getPort() == SnapcastConnection.DEFAULT_PORT ? text : text + ":" + endpoint.getPort();
    }

    /** The host first, then the name in brackets: a name from the network can not pose as the address in front of it. */
    private static String label(String name, String host) {
        return name.isEmpty() ? host : host + " (" + name + ")";
    }

    private static Source sourceOf(SnapcastServerListModel.Kind kind) {
        switch (kind) {
            case MANUAL:
                return Source.TYPED;
            case REMEMBERED:
                return Source.REMEMBERED;
            case DISCOVERED:
            default:
                return Source.FOUND;
        }
    }
}
