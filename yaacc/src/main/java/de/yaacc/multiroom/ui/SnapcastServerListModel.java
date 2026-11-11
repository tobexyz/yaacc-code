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
import java.util.List;

import de.yaacc.multiroom.SnapcastServerAdmin;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The server list of the Snapcast settings: what the user can type in, and what is listed. Pure Java (the dialog only
 * forwards text and shows the rows).
 *
 * <ul>
 *   <li><b>Strict input.</b> The text is trimmed and must be a host name or an address with an optional port
 *   ({@link SnapcastEndpoint#parse}); credentials ({@code user:password@host}) get their own message and are never
 *   stored, a URL is invalid. At most {@value SnapcastManualServers#MAX_SERVERS} servers; a server that is in the list
 *   already (written another way) is merged, not added twice.</li>
 *   <li><b>One row per server.</b> The typed-in servers and the ones the directory knows (found on the network, or
 *   remembered because a selected group lives there) are merged by endpoint. A name that came from the network is
 *   cleaned again before it is shown. Only what the user typed in, and what was remembered, can be removed: a server that
 *   is announced on the network is not the user's to remove.</li>
 *   <li><b>Remove</b> means removed: see {@link SnapcastServerAdmin}.</li>
 * </ul>
 */
public final class SnapcastServerListModel {

    /** The longest name of a discovered server shown. */
    static final int MAX_NAME = 64;

    /** What {@link #add} did. */
    public enum Outcome {
        ADDED(false),
        /** The server is in the list already (the same endpoint written another way counts). */
        ALREADY_LISTED(false),
        EMPTY(true),
        /** Not a host name or address with an optional port. */
        INVALID(true),
        /** The text carries a user name or password. */
        HAS_CREDENTIALS(true),
        /** The list holds the most servers. */
        FULL(true);

        private final boolean problem;

        Outcome(boolean problem) {
            this.problem = problem;
        }

        /** True when the text was not taken and the user must change it (or remove a server). */
        public boolean isProblem() {
            return problem;
        }
    }

    /** Why a server is in the list. */
    public enum Kind {
        /** The user typed it in. */
        MANUAL,
        /** It is (or just was) announced on the network. */
        DISCOVERED,
        /** A selected group lives there; it is listed from an earlier run without being announced. */
        REMEMBERED
    }

    /** One row of the list. Immutable. */
    public static final class Row {
        private final SnapcastServerKey key;
        private final String endpointText;
        private final String name;
        private final Kind kind;
        private final boolean announced;

        Row(SnapcastServerKey key, String endpointText, String name, Kind kind, boolean announced) {
            this.key = key;
            this.endpointText = endpointText;
            this.name = name;
            this.kind = kind;
            this.announced = announced;
        }

        public SnapcastServerKey getKey() {
            return key;
        }

        /** {@code host:port}. */
        public String getEndpointText() {
            return endpointText;
        }

        /** The name the network gave the service (cleaned display text); empty for a typed-in server. */
        public String getName() {
            return name;
        }

        public Kind getKind() {
            return kind;
        }

        /** The server is announced on the network right now. */
        public boolean isAnnounced() {
            return announced;
        }

        /** True for a typed-in or a remembered server. */
        public boolean isRemovable() {
            return kind != Kind.DISCOVERED;
        }

        @Override
        public String toString() {
            return "SnapcastServerListModel.Row{" + kind + "}";
        }
    }

    private final SnapcastManualServers manualServers;
    private final SnapcastDirectory directory;
    private final SnapcastServerAdmin admin;

    public SnapcastServerListModel(SnapcastManualServers manualServers, SnapcastDirectory directory, SnapcastServerAdmin admin) {
        this.manualServers = manualServers;
        this.directory = directory;
        this.admin = admin;
    }

    /** True while another server can be typed in. */
    public boolean canAdd() {
        return manualServers.list().size() < SnapcastManualServers.MAX_SERVERS;
    }

    /** Checks and stores what the user typed in; the directory lists the server at once when it was added. */
    public Outcome add(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return Outcome.EMPTY;
        }
        if (text.indexOf('@') >= 0) {
            return Outcome.HAS_CREDENTIALS; // never stored, whatever follows
        }
        switch (manualServers.add(text)) {
            case ADDED:
                directory.setManualServers(manualServers.list());
                return Outcome.ADDED;
            case DUPLICATE:
                return Outcome.ALREADY_LISTED;
            case FULL:
                return Outcome.FULL;
            default:
                return Outcome.INVALID;
        }
    }

    /** Removes a server, see {@link SnapcastServerAdmin#remove}. */
    public SnapcastServerAdmin.Result remove(SnapcastServerKey key) {
        return admin.remove(key);
    }

    /** The rows: the typed-in servers first, then the others, each group by endpoint. */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (SnapcastDirectory.Entry entry : directory.servers()) {
            Kind kind = entry.isManual() ? Kind.MANUAL
                    : entry.isDiscovered() || entry.isLost() || !entry.isRemembered() ? Kind.DISCOVERED : Kind.REMEMBERED;
            rows.add(new Row(entry.getKey(), entry.getKey().asString(), cleanName(entry.getDisplayName()), kind,
                    entry.isDiscovered()));
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                boolean aManual = a.kind == Kind.MANUAL;
                boolean bManual = b.kind == Kind.MANUAL;
                if (aManual != bManual) {
                    return aManual ? -1 : 1;
                }
                return a.endpointText.compareTo(b.endpointText);
            }
        });
        return rows;
    }

    /** A name from the network, cleaned again (the directory cleans it too; this is the last step before the screen). */
    static String cleanName(String name) {
        return SnapcastDisplayText.of(name, MAX_NAME).trim();
    }
}
