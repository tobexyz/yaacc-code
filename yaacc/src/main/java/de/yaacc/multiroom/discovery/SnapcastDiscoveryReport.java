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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.yaacc.multiroom.control.SnapcastDisplayText;

/**
 * The text a user can read and copy to tell "the server does not announce itself" from "the app does not hear it" (task
 * 11.6): the facts of the phone (the switch, Wi-Fi, the multicast permission and lock), what was browsed and what each type
 * saw, the servers listed, the callbacks of {@code NsdManager} with their failure codes, and one line that reads the facts.
 * Pure. Everything that came from the network is cleaned and cut by {@link SnapcastDiscoveryLog}; the report is for the
 * screen and the clipboard only and is never written to the app log. It shows the addresses and names of the servers found:
 * the user decides where it is pasted.
 */
public final class SnapcastDiscoveryReport {

    /** A fact that may be unknown. */
    public enum Tri {
        YES, NO, UNKNOWN
    }

    /** The state of the multicast lock. */
    public enum LockState {
        HELD("held"),
        NOT_HELD("not held"),
        /** Taking the lock threw. */
        FAILED("could not be taken"),
        /** The phone has no Wi-Fi service. */
        UNAVAILABLE("no Wi-Fi service");

        private final String text;

        LockState(String text) {
            this.text = text;
        }
    }

    /** Where a listed server came from. */
    public enum Origin {
        TYPED("typed in"), FOUND("found on the network"), REMEMBERED("remembered from an earlier run");

        private final String text;

        Origin(String text) {
            this.text = text;
        }
    }

    /** What the facts say; the first that applies. */
    public enum Reading {
        SWITCH_OFF("The discovery switch is off, so nothing is browsed. Switch on \"Find Snapcast servers\"."),
        NOT_SCANNED("No scan has run since the app started. A scan runs while this screen or the receivers page is open and the switch is on."),
        NO_WIFI("The phone is not connected to Wi-Fi. mDNS does not cross mobile data; connect to the network of the server."),
        NO_PERMISSION("The multicast permission is missing, so the phone can not receive announcements."),
        START_FAILED("The system refused to start a browse (see the codes below), so that type is not searched."),
        NOTHING_HEARD("The scan runs and the system reports no Snapcast service at all. Either the server does not announce"
                + " itself (mDNS or Avahi off, as in the Docker test rig), or its announcements do not reach this phone"
                + " (router, access point isolation, another network)."),
        STREAM_ONLY("A server announces its stream service but not its control port, so it can not be listed. Its control"
                + " service is off (publish = false or the control port is disabled): add the server by address."),
        RESOLVE_FAILED("Services were found but none could be resolved to an address (see the codes below)."),
        REFUSED("Services were resolved, but the address or port is not accepted: only addresses of the local network and"
                + " ports from 1024 are taken from an announcement. Add the server by address."),
        FOUND("A server was found and listed.");

        private final String text;

        Reading(String text) {
            this.text = text;
        }

        /** One line, no line break. */
        public String text() {
            return text;
        }
    }

    /** A server of the list, for the report. */
    public static final class ServerLine {
        private final String endpoint;
        private final String name;
        private final Origin origin;

        public ServerLine(String endpoint, String name, Origin origin) {
            this.endpoint = SnapcastDisplayText.of(endpoint, 80);
            this.name = SnapcastDisplayText.of(name, 64);
            this.origin = origin;
        }
    }

    /** The facts of the phone and the list. Builder style; the defaults are "off", "not held", "unknown". */
    public static final class Facts {
        private String device = "";
        private boolean switchOn;
        private boolean scanRunning;
        private Tri wifi = Tri.UNKNOWN;
        private Tri multicastPermission = Tri.UNKNOWN;
        private LockState multicastLock = LockState.NOT_HELD;
        private List<ServerLine> servers = Collections.emptyList();

        /** The device model and the API level; the caller's text, not the network's. */
        public Facts device(String value) {
            this.device = SnapcastDisplayText.of(value, 80);
            return this;
        }

        /** The setting "Find Snapcast servers". */
        public Facts switchOn(boolean value) {
            this.switchOn = value;
            return this;
        }

        /** The browse is running now. */
        public Facts scanRunning(boolean value) {
            this.scanRunning = value;
            return this;
        }

        public Facts wifi(Tri value) {
            this.wifi = value;
            return this;
        }

        public Facts multicastPermission(Tri value) {
            this.multicastPermission = value;
            return this;
        }

        public Facts multicastLock(LockState value) {
            this.multicastLock = value;
            return this;
        }

        public Facts servers(List<ServerLine> value) {
            this.servers = value == null ? Collections.<ServerLine>emptyList() : new ArrayList<>(value);
            return this;
        }
    }

    private SnapcastDiscoveryReport() {
    }

    /** What the facts and the log say, the first rule that applies. */
    public static Reading reading(Facts facts, SnapcastDiscoveryLog.Snapshot log) {
        if (!facts.switchOn) {
            return Reading.SWITCH_OFF;
        }
        if (!log.hasScanned()) {
            return Reading.NOT_SCANNED;
        }
        if (facts.wifi == Tri.NO) {
            return Reading.NO_WIFI;
        }
        if (facts.multicastPermission == Tri.NO) {
            return Reading.NO_PERMISSION;
        }
        int controlFound = 0;
        int controlResolved = 0;
        int controlRefused = 0;
        int streamFound = 0;
        for (String type : SnapcastServiceTypes.browsed()) {
            SnapcastDiscoveryLog.TypeStats stats = log.stats(type);
            if (stats.startFailed()) {
                return Reading.START_FAILED;
            }
            if (SnapcastServiceTypes.isListed(SnapcastServiceTypes.roleOf(type))) {
                controlFound += stats.found();
                controlResolved += stats.resolved();
                controlRefused += stats.refused();
            } else {
                streamFound += stats.found();
            }
        }
        if (controlFound == 0) {
            return streamFound > 0 ? Reading.STREAM_ONLY : Reading.NOTHING_HEARD;
        }
        if (controlResolved > 0) {
            return Reading.FOUND;
        }
        return controlRefused > 0 ? Reading.REFUSED : Reading.RESOLVE_FAILED;
    }

    /** The report. */
    public static String of(Facts facts, SnapcastDiscoveryLog.Snapshot log) {
        StringBuilder out = new StringBuilder(2048);
        out.append("YAACC Snapcast discovery report\n");
        out.append("device: ").append(facts.device).append('\n');
        out.append("switch \"Find Snapcast servers\": ").append(facts.switchOn ? "on" : "off").append('\n');
        out.append("scan running now: ").append(facts.scanRunning ? "yes" : "no").append('\n');
        out.append("Wi-Fi: ").append(triText(facts.wifi, "connected", "not connected")).append('\n');
        out.append("multicast permission: ").append(triText(facts.multicastPermission, "granted", "missing")).append('\n');
        out.append("multicast lock: ").append(facts.multicastLock.text).append('\n');
        out.append("types browsed:\n");
        for (String type : SnapcastServiceTypes.browsed()) {
            appendType(out, type, log.stats(type));
        }
        out.append("reading: ").append(reading(facts, log).text()).append('\n');
        if (facts.servers.isEmpty()) {
            out.append("servers listed: none\n");
        } else {
            out.append("servers listed (").append(facts.servers.size()).append("):\n");
            for (ServerLine server : facts.servers) {
                out.append("  ").append(server.endpoint);
                if (!server.name.isEmpty()) {
                    out.append("  ").append(server.name);
                }
                out.append("  (").append(server.origin.text).append(")\n");
            }
        }
        appendEvents(out, log);
        return out.toString();
    }

    private static String triText(Tri value, String yes, String no) {
        return value == Tri.YES ? yes : value == Tri.NO ? no : "unknown";
    }

    private static void appendType(StringBuilder out, String type, SnapcastDiscoveryLog.TypeStats stats) {
        out.append("  ").append(type).append(" (").append(SnapcastServiceTypes.roleOf(type).label()).append(", ")
                .append(SnapcastServiceTypes.versionNote(type)).append("): ");
        if (stats.startFailed()) {
            out.append("start failed, ").append(SnapcastNsdErrors.describe(stats.startFailure()));
        } else if (stats.started()) {
            out.append("started");
        } else if (stats.requested()) {
            out.append("requested, waiting for the system");
        } else {
            out.append("not running");
        }
        out.append("; found ").append(stats.found()).append(", lost ").append(stats.lost())
                .append(", resolved ").append(stats.resolved()).append(", refused ").append(stats.refused())
                .append(", resolve failed ").append(stats.resolveFailed());
        if (stats.resolveFailed() > 0) {
            out.append(" (last: ").append(SnapcastNsdErrors.describe(stats.lastResolveFailure())).append(')');
        }
        out.append('\n');
    }

    private static void appendEvents(StringBuilder out, SnapcastDiscoveryLog.Snapshot log) {
        List<SnapcastDiscoveryLog.Event> events = log.events();
        if (events.isEmpty()) {
            out.append("events: none yet\n");
            return;
        }
        out.append("events (the last ").append(events.size());
        if (log.droppedEvents() > 0) {
            out.append(" of ").append(events.size() + log.droppedEvents());
        }
        out.append(", oldest first, seconds since the scan started):\n");
        for (SnapcastDiscoveryLog.Event event : events) {
            out.append(String.format(Locale.ROOT, "  +%.1f s  ", event.atMillis() / 1000.0)).append(event.kind().label());
            if (!event.type().isEmpty()) {
                out.append("  ").append(event.type());
            }
            if (!event.name().isEmpty()) {
                out.append("  ").append(event.name());
            }
            if (!event.detail().isEmpty()) {
                out.append("  ").append(event.detail());
            }
            if (hasCode(event.kind())) {
                out.append("  ").append(SnapcastNsdErrors.describe(event.code()));
            }
            out.append('\n');
        }
        if (log.ignoredTypes() > 0) {
            out.append("services of other types seen and ignored: ").append(log.ignoredTypes()).append('\n');
        }
    }

    private static boolean hasCode(SnapcastDiscoveryLog.Kind kind) {
        return kind == SnapcastDiscoveryLog.Kind.BROWSE_START_FAILED || kind == SnapcastDiscoveryLog.Kind.BROWSE_STOP_FAILED
                || kind == SnapcastDiscoveryLog.Kind.RESOLVE_FAILED || kind == SnapcastDiscoveryLog.Kind.BROWSE_REFUSED;
    }
}
