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
 *   changes nothing.</li>
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

        /** The name (cleaned) and the address, or the address alone. */
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

    private static String label(String name, String host) {
        return name.isEmpty() ? host : name + " (" + host + ")";
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
