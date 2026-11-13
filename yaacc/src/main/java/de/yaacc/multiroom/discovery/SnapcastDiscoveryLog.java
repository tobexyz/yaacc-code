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
package de.yaacc.multiroom.discovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastDisplayText;

/**
 * What the mDNS discovery saw since the scan started, kept for the report the user can read and copy (task 11.6): the
 * callbacks of {@code NsdManager} (start, stop, found, lost, resolve) with the failure codes, and exact counters per browsed
 * type. Bounded: the last {@value #CAPACITY} events, the counters are numbers and exist for the browsed types only, so a
 * network that announces a flood of services (or of types) grows nothing. Names and details come from the network and
 * are cleaned and cut here. Never written to the app log (a name or an address of the local network is display text only).
 * Thread safe.
 */
public final class SnapcastDiscoveryLog {

    /** The most events kept. */
    public static final int CAPACITY = 40;
    private static final int MAX_NAME = 64;
    private static final int MAX_DETAIL = 80;
    private static final int MAX_TYPE = 40;

    /** What happened. */
    public enum Kind {
        BROWSE_REQUESTED("browse requested"),
        BROWSE_STARTED("browse started"),
        BROWSE_START_FAILED("browse start failed"),
        BROWSE_STOPPED("browse stopped"),
        BROWSE_STOP_FAILED("browse stop failed"),
        /** {@code discoverServices} threw (no service of the platform, a permission). */
        BROWSE_REFUSED("browse refused by the platform"),
        FOUND("found"),
        /** A service of a type that is not one of ours. */
        FOUND_IGNORED("found, ignored (other type)"),
        LOST("lost"),
        /** The queue of resolutions was full and the service dropped. */
        QUEUE_FULL("dropped, queue full"),
        RESOLVE_FAILED("resolve failed"),
        /** Resolved and listed. */
        RESOLVED("resolved, listed"),
        /** Resolved, but the address or port is not accepted (outside the local ranges, port below 1024, list full). */
        RESOLVED_REFUSED("resolved, refused"),
        /** Resolved without an address. */
        RESOLVED_EMPTY("resolved, no address");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One event. Immutable; the texts are cleaned. */
    public static final class Event {
        private final long atMillis;
        private final Kind kind;
        private final String type;
        private final String name;
        private final int code;
        private final String detail;

        Event(long atMillis, Kind kind, String type, String name, int code, String detail) {
            this.atMillis = atMillis;
            this.kind = kind;
            this.type = type;
            this.name = name;
            this.code = code;
            this.detail = detail;
        }

        /** Milliseconds since the scan started. */
        public long atMillis() {
            return atMillis;
        }

        public Kind kind() {
            return kind;
        }

        /** The service type as reported (cleaned and cut). */
        public String type() {
            return type;
        }

        /** The service name (cleaned and cut); empty for events without one. */
        public String name() {
            return name;
        }

        /** A failure code of {@code NsdManager}; 0 for events without one. */
        public int code() {
            return code;
        }

        /** An address and port, or empty. */
        public String detail() {
            return detail;
        }
    }

    /** The numbers of one browsed type. Immutable. */
    public static final class TypeStats {
        private final boolean requested;
        private final boolean started;
        private final int found;
        private final int lost;
        private final int resolved;
        private final int refused;
        private final int resolveFailed;
        private final int lastResolveFailure;
        private final int startFailure;

        TypeStats(boolean requested, boolean started, int found, int lost, int resolved, int refused, int resolveFailed,
                  int lastResolveFailure, int startFailure) {
            this.requested = requested;
            this.started = started;
            this.found = found;
            this.lost = lost;
            this.resolved = resolved;
            this.refused = refused;
            this.resolveFailed = resolveFailed;
            this.lastResolveFailure = lastResolveFailure;
            this.startFailure = startFailure;
        }

        public boolean requested() {
            return requested;
        }

        public boolean started() {
            return started;
        }

        public int found() {
            return found;
        }

        public int lost() {
            return lost;
        }

        /** Resolved and listed. */
        public int resolved() {
            return resolved;
        }

        /** Resolved but refused by the address rules. */
        public int refused() {
            return refused;
        }

        public int resolveFailed() {
            return resolveFailed;
        }

        /** The code of the last failed resolution; -1 if none failed. */
        public int lastResolveFailure() {
            return lastResolveFailure;
        }

        /** The code with which the browse could not start; -1 if it did not fail. */
        public int startFailure() {
            return startFailure;
        }

        public boolean startFailed() {
            return startFailure >= 0;
        }
    }

    private static final class MutableStats {
        boolean requested;
        boolean started;
        int found;
        int lost;
        int resolved;
        int refused;
        int resolveFailed;
        int lastResolveFailure = -1;
        int startFailure = -1;

        TypeStats freeze() {
            return new TypeStats(requested, started, found, lost, resolved, refused, resolveFailed, lastResolveFailure,
                    startFailure);
        }
    }

    /** A copy of the log at one moment; later events do not change it. */
    public static final class Snapshot {
        private final boolean scanned;
        private final List<Event> events;
        private final int dropped;
        private final int ignoredTypes;
        private final Map<String, TypeStats> stats;

        Snapshot(boolean scanned, List<Event> events, int dropped, int ignoredTypes, Map<String, TypeStats> stats) {
            this.scanned = scanned;
            this.events = events;
            this.dropped = dropped;
            this.ignoredTypes = ignoredTypes;
            this.stats = stats;
        }

        /** A scan was started since the app started. */
        public boolean hasScanned() {
            return scanned;
        }

        /** The kept events, oldest first. */
        public List<Event> events() {
            return events;
        }

        /** How many older events were dropped to stay within the capacity. */
        public int droppedEvents() {
            return dropped;
        }

        /** How many services of other types were reported (counted, not kept). */
        public int ignoredTypes() {
            return ignoredTypes;
        }

        /** The numbers of a browsed type; all zero for any other type. */
        public TypeStats stats(String type) {
            String canonical = SnapcastServiceTypes.canonical(type);
            TypeStats found = canonical == null ? null : stats.get(canonical);
            return found != null ? found : new MutableStats().freeze();
        }
    }

    private final MonotonicClock clock;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Map<String, MutableStats> stats = new HashMap<>();
    private boolean scanned;
    private long startedAt = -1;
    private int dropped;
    private int ignoredTypes;

    public SnapcastDiscoveryLog(MonotonicClock clock) {
        this.clock = clock;
    }

    /** A new scan starts: what the last one saw is forgotten. */
    public synchronized void scanStarted() {
        events.clear();
        stats.clear();
        dropped = 0;
        ignoredTypes = 0;
        scanned = true;
        startedAt = clock.nowMillis();
    }

    /**
     * Records an event.
     *
     * @param type   the service type as the platform reported it
     * @param name   the service name, or empty
     * @param code   the failure code of {@code NsdManager}, 0 if the event has none
     * @param detail an address and port, or empty
     */
    public synchronized void record(Kind kind, String type, String name, int code, String detail) {
        long now = clock.nowMillis();
        if (startedAt < 0) {
            startedAt = now;
        }
        String canonical = SnapcastServiceTypes.canonical(type);
        if (kind == Kind.FOUND_IGNORED) {
            ignoredTypes++;
        } else if (canonical != null) {
            count(stats.computeIfAbsent(canonical, k -> new MutableStats()), kind, code);
        }
        if (events.size() >= CAPACITY) {
            events.pollFirst();
            dropped++;
        }
        events.addLast(new Event(now - startedAt, kind, SnapcastDisplayText.of(type, MAX_TYPE),
                SnapcastDisplayText.of(name, MAX_NAME), code, SnapcastDisplayText.of(detail, MAX_DETAIL)));
    }

    private static void count(MutableStats s, Kind kind, int code) {
        switch (kind) {
            case BROWSE_REQUESTED:
                s.requested = true;
                break;
            case BROWSE_STARTED:
                s.started = true;
                break;
            case BROWSE_START_FAILED:
                s.startFailure = code;
                break;
            case BROWSE_REFUSED:
                s.startFailure = code;
                break;
            case BROWSE_STOPPED:
                s.requested = false;
                s.started = false;
                break;
            case FOUND:
                s.found++;
                break;
            case LOST:
                s.lost++;
                break;
            case RESOLVED:
                s.resolved++;
                break;
            case RESOLVED_REFUSED:
            case RESOLVED_EMPTY:
                s.refused++;
                break;
            case RESOLVE_FAILED:
                s.resolveFailed++;
                s.lastResolveFailure = code;
                break;
            default:
                break;
        }
    }

    public synchronized Snapshot snapshot() {
        Map<String, TypeStats> frozen = new HashMap<>();
        for (Map.Entry<String, MutableStats> entry : stats.entrySet()) {
            frozen.put(entry.getKey(), entry.getValue().freeze());
        }
        return new Snapshot(scanned, Collections.unmodifiableList(new ArrayList<>(events)), dropped, ignoredTypes, frozen);
    }
}
