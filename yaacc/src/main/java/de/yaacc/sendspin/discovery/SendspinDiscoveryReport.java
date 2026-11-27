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
package de.yaacc.sendspin.discovery;

import de.yaacc.multiroom.discovery.SnapcastNsdErrors;
import de.yaacc.sendspin.SendspinAddress;

import java.util.List;
import java.util.Locale;

/**
 * The text of the discovery report (task 11.12): what was browsed for players and for servers, what the system reported, the state of
 * the switches, Wi-Fi and the multicast lock, and the players and servers known. It names the devices found and their addresses (it
 * is for the user to copy to a support request; nothing is sent anywhere). Pure.
 */
public final class SendspinDiscoveryReport {

    /** The state of the multicast lock. */
    public enum LockState {
        NOT_HELD, HELD, UNAVAILABLE, FAILED
    }

    /** What the app knows about its surroundings when the report is made. */
    public static final class Facts {
        private final String device;
        private final boolean playerSwitch;
        private final boolean serverSwitch;
        private final boolean playersRunning;
        private final Boolean wifi;
        private final LockState lock;
        private final boolean serversRunning;

        /**
         * @param wifi null if it could not be read
         */
        public Facts(String device, boolean playerSwitch, boolean serverSwitch, boolean playersRunning, Boolean wifi, LockState lock,
                     boolean serversRunning) {
            this.device = device;
            this.playerSwitch = playerSwitch;
            this.serverSwitch = serverSwitch;
            this.playersRunning = playersRunning;
            this.wifi = wifi;
            this.lock = lock;
            this.serversRunning = serversRunning;
        }
    }

    private SendspinDiscoveryReport() {
    }

    public static String build(Facts facts, List<SendspinDirectory.Entry> players, List<SendspinDirectory.Entry> servers,
                               SendspinDiscoveryLog.Snapshot playersLog, SendspinDiscoveryLog.Snapshot serversLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sendspin discovery report\n");
        sb.append("device: ").append(facts.device == null ? "?" : facts.device).append('\n');
        sb.append("search for players (the switch): ").append(facts.playerSwitch ? "on" : "off").append('\n');
        sb.append("search for servers (the switch): ").append(facts.serverSwitch ? "on" : "off").append('\n');
        sb.append("Wi-Fi: ").append(facts.wifi == null ? "unknown" : facts.wifi ? "connected" : "not connected").append('\n');
        sb.append("multicast lock: ").append(facts.lock.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append('\n');
        section(sb, "players", SendspinServiceType.TYPE, facts.playerSwitch, facts.playersRunning, players, playersLog);
        section(sb, "servers", SendspinServiceType.SERVER_TYPE, facts.serverSwitch, facts.serversRunning, servers, serversLog);
        return sb.toString();
    }

    private static void section(StringBuilder sb, String what, String type, boolean switchOn, boolean running,
                                List<SendspinDirectory.Entry> entries, SendspinDiscoveryLog.Snapshot log) {
        sb.append('\n').append("-- ").append(what).append(" --\n");
        sb.append("service type browsed: ").append(type).append('\n');
        sb.append("searching now: ").append(running ? "yes" : "no").append('\n');
        sb.append("scans since the app started: ").append(log.getScans()).append('\n');
        sb.append("events: found ").append(log.count(SendspinDiscoveryLog.Kind.FOUND))
                .append(", resolved ").append(log.count(SendspinDiscoveryLog.Kind.RESOLVED))
                .append(", refused ").append(log.count(SendspinDiscoveryLog.Kind.RESOLVED_REFUSED))
                .append(", own ").append(log.count(SendspinDiscoveryLog.Kind.RESOLVED_OWN))
                .append(", resolve failed ").append(log.count(SendspinDiscoveryLog.Kind.RESOLVE_FAILED))
                .append(", lost ").append(log.count(SendspinDiscoveryLog.Kind.LOST)).append('\n');
        sb.append(what).append(" known: ").append(entries.size()).append('\n');
        for (SendspinDirectory.Entry entry : entries) {
            sb.append("  ").append(entry.name().isEmpty() ? "(no name)" : entry.name()).append("  ")
                    .append(SendspinAddress.format(entry.endpoint())).append("  [found by the network]");
            if (entry.yaacc() != null) {
                sb.append("  [YAACC phone]");
            }
            sb.append('\n');
        }
        if (switchOn && log.getScans() > 0 && log.count(SendspinDiscoveryLog.Kind.FOUND) == 0) {
            sb.append("Nothing was announced: check that the ").append(what.substring(0, what.length() - 1))
                    .append(" runs and announces itself (mDNS), that the phone and it are in the same network (a guest network or an access point")
                    .append(" with client isolation hides devices from each other) and that nothing blocks UDP 5353.\n");
        }
        sb.append("last events:\n");
        for (SendspinDiscoveryLog.Event event : log.getEvents()) {
            sb.append("  scan ").append(event.getScan()).append(' ').append(event.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            if (!event.getName().isEmpty()) {
                sb.append(" \"").append(event.getName()).append('"');
            }
            if (event.getCode() != 0) {
                sb.append(' ').append(SnapcastNsdErrors.describe(event.getCode()));
            }
            if (!event.getDetail().isEmpty()) {
                sb.append(' ').append(event.getDetail());
            }
            sb.append('\n');
        }
    }
}
