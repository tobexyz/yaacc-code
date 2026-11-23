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

import java.util.List;

import de.yaacc.multiroom.discovery.SnapcastNsdErrors;

/**
 * The text of the discovery report (what was browsed, what the system reported, the state of the switch, Wi-Fi and the multicast
 * lock, the receivers known). It names the receivers found and their addresses (it is for the user to copy to a support request;
 * nothing is sent anywhere). Pure.
 */
public final class FCastDiscoveryReport {
    /** The state of the multicast lock. */
    public enum LockState {
        NOT_HELD, HELD, UNAVAILABLE, FAILED
    }

    /** What the app knows about its surroundings when the report is made. */
    public static final class Facts {
        private final boolean discoveryEnabled;
        private final boolean running;
        private final Boolean wifiConnected;
        private final LockState lockState;
        private final String device;

        public Facts(boolean discoveryEnabled, boolean running, Boolean wifiConnected, LockState lockState, String device) {
            this.discoveryEnabled = discoveryEnabled;
            this.running = running;
            this.wifiConnected = wifiConnected;
            this.lockState = lockState;
            this.device = device;
        }
    }

    private FCastDiscoveryReport() {
    }

    public static String build(Facts facts, List<FCastDirectory.Entry> receivers, FCastDiscoveryLog.Snapshot log) {
        StringBuilder sb = new StringBuilder();
        sb.append("FCast discovery report\n");
        sb.append("device: ").append(facts.device == null ? "?" : facts.device).append('\n');
        sb.append("search for receivers (the switch): ").append(facts.discoveryEnabled ? "on" : "off").append('\n');
        sb.append("searching now: ").append(facts.running ? "yes" : "no").append('\n');
        sb.append("Wi-Fi: ").append(facts.wifiConnected == null ? "unknown" : facts.wifiConnected ? "connected" : "not connected").append('\n');
        sb.append("multicast lock: ").append(facts.lockState.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')).append('\n');
        sb.append("service type browsed: ").append(FCastServiceType.TYPE).append('\n');
        sb.append("scans since the app started: ").append(log.getScans()).append('\n');
        sb.append("events: found ").append(log.count(FCastDiscoveryLog.Kind.FOUND))
                .append(", resolved ").append(log.count(FCastDiscoveryLog.Kind.RESOLVED))
                .append(", refused ").append(log.count(FCastDiscoveryLog.Kind.RESOLVED_REFUSED))
                .append(", resolve failed ").append(log.count(FCastDiscoveryLog.Kind.RESOLVE_FAILED))
                .append(", lost ").append(log.count(FCastDiscoveryLog.Kind.LOST)).append('\n');
        sb.append("receivers known: ").append(receivers.size()).append('\n');
        for (FCastDirectory.Entry entry : receivers) {
            sb.append("  ").append(entry.getDisplayName()).append("  ").append(entry.getEndpoint())
                    .append(entry.isDiscovered() ? "  [found by the network]" : "").append(entry.isManual() ? "  [added by hand]" : "")
                    .append(entry.isOffline() ? "  [remembered, not seen yet]" : "");
            FCastServiceTxt hints = entry.getHints();
            if (hints.getProtocolVersion() != null) {
                sb.append("  v").append(hints.getProtocolVersion());
            }
            sb.append('\n');
        }
        if (facts.discoveryEnabled && log.getScans() > 0 && log.count(FCastDiscoveryLog.Kind.FOUND) == 0) {
            sb.append("Nothing was announced: check that the receiver runs, that the phone and the receiver are in the same network ")
                    .append("(a guest network or an access point with client isolation hides devices from each other), ")
                    .append("and that the firewall of the receiver's computer allows UDP 5353 (mDNS) and TCP 46899.\n");
        }
        sb.append("last events:\n");
        for (FCastDiscoveryLog.Event event : log.getEvents()) {
            sb.append("  scan ").append(event.getScan()).append(' ').append(event.getKind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
            if (!event.getName().isEmpty()) {
                sb.append(" \"").append(event.getName()).append('"');
            }
            if (event.getCode() != 0) {
                sb.append(" ").append(SnapcastNsdErrors.describe(event.getCode()));
            }
            if (!event.getDetail().isEmpty()) {
                sb.append(" ").append(event.getDetail());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
