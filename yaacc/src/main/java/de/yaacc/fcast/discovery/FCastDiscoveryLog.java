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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import de.yaacc.fcast.protocol.FCastText;

/**
 * What the platform's service discovery did, for the report the user can copy when no receiver is found (Wi-Fi, permission and
 * multicast problems all look the same from the outside). A ring of the last 100 events plus a count per kind. Names that come from
 * the network are cleaned and cut. Thread safe.
 */
public final class FCastDiscoveryLog {
    /** The events. */
    public enum Kind {
        BROWSE_REQUESTED, BROWSE_STARTED, BROWSE_START_FAILED, BROWSE_REFUSED, BROWSE_STOPPED, BROWSE_STOP_FAILED,
        FOUND, FOUND_IGNORED, LOST, QUEUE_FULL, RESOLVE_FAILED, RESOLVED, RESOLVED_EMPTY, RESOLVED_REFUSED
    }

    /** One event. */
    public static final class Event {
        private final int scan;
        private final Kind kind;
        private final String name;
        private final int code;
        private final String detail;

        Event(int scan, Kind kind, String name, int code, String detail) {
            this.scan = scan;
            this.kind = kind;
            this.name = name;
            this.code = code;
            this.detail = detail;
        }

        public int getScan() {
            return scan;
        }

        public Kind getKind() {
            return kind;
        }

        /** The service name (display text). */
        public String getName() {
            return name;
        }

        /** The failure code of the platform, 0 if none. */
        public int getCode() {
            return code;
        }

        /** What came out of it (an address for a resolved service). */
        public String getDetail() {
            return detail;
        }
    }

    /** What the log held at one moment. */
    public static final class Snapshot {
        private final int scans;
        private final List<Event> events;
        private final Map<Kind, Integer> counts;

        Snapshot(int scans, List<Event> events, Map<Kind, Integer> counts) {
            this.scans = scans;
            this.events = events;
            this.counts = counts;
        }

        /** How many scans were started since the app started. */
        public int getScans() {
            return scans;
        }

        /** The last events, oldest first. */
        public List<Event> getEvents() {
            return events;
        }

        public int count(Kind kind) {
            Integer n = counts.get(kind);
            return n == null ? 0 : n;
        }
    }

    private static final int CAPACITY = 100;
    private static final int MAX_NAME = 64;
    private static final int MAX_DETAIL = 80;

    private final Event[] ring = new Event[CAPACITY];
    private final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
    private int next;
    private int size;
    private int scans;

    /** A scan starts: the events that follow belong to it. */
    public synchronized void scanStarted() {
        scans++;
    }

    public synchronized int scanId() {
        return scans;
    }

    public synchronized void record(int scan, Kind kind, String name, int code, String detail) {
        ring[next] = new Event(scan, kind, FCastText.clean(name == null ? "" : name, MAX_NAME), code,
                FCastText.clean(detail == null ? "" : detail, MAX_DETAIL));
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
        Integer old = counts.get(kind);
        counts.put(kind, old == null ? 1 : old + 1);
    }

    public synchronized Snapshot snapshot() {
        List<Event> events = new ArrayList<>(size);
        int start = size < CAPACITY ? 0 : next;
        for (int i = 0; i < size; i++) {
            events.add(ring[(start + i) % CAPACITY]);
        }
        return new Snapshot(scans, events, new EnumMap<>(counts.isEmpty() ? new EnumMap<Kind, Integer>(Kind.class) : counts));
    }
}
