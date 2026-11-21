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
package de.yaacc.fcast.discovery;

import java.util.ArrayList;
import java.util.List;

/** The rows of the list of FCast receivers in the settings (task 5.4): what the user sees and what may be removed. Pure. */
public final class FCastReceiverRows {

    public enum Kind {
        /** Typed in, pasted or scanned (also when it is announced now). */
        MANUAL,
        /** Remembered from the last run and not seen in this one. */
        REMEMBERED,
        /** Announced on the network. */
        DISCOVERED
    }

    /** One row. */
    public static final class Row {
        private final FCastEndpoint endpoint;
        private final String name;
        private final Kind kind;
        private final boolean announced;

        private Row(FCastEndpoint endpoint, String name, Kind kind, boolean announced) {
            this.endpoint = endpoint;
            this.name = name == null ? "" : name;
            this.kind = kind;
            this.announced = announced;
        }

        public FCastEndpoint getEndpoint() {
            return endpoint;
        }

        public String getEndpointText() {
            return endpoint.toString();
        }

        /** The name to show; display text, already cleaned. */
        public String getName() {
            return name;
        }

        public Kind getKind() {
            return kind;
        }

        /** For a manual row: the receiver is announced on the network now. */
        public boolean isAnnounced() {
            return announced;
        }

        /** Only what the user typed in can be removed; what is remembered goes with the selection, what is announced is on the network. */
        public boolean isRemovable() {
            return kind == Kind.MANUAL;
        }
    }

    private FCastReceiverRows() {
    }

    public static List<Row> of(List<FCastDirectory.Entry> entries) {
        List<Row> rows = new ArrayList<>();
        for (FCastDirectory.Entry entry : entries) {
            Kind kind = entry.isManual() ? Kind.MANUAL : entry.isDiscovered() ? Kind.DISCOVERED : Kind.REMEMBERED;
            rows.add(new Row(entry.getEndpoint(), entry.getDisplayName(), kind, entry.isDiscovered()));
        }
        return rows;
    }

    /** Equal for equal lists: the screen redraws only when this changes (a redraw would eat a tap on "remove"). */
    public static String signature(List<Row> rows) {
        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            text.append(row.getEndpointText()).append('|').append(row.getName()).append('|').append(row.getKind())
                    .append('|').append(row.isAnnounced()).append('\n');
        }
        return text.toString();
    }
}
