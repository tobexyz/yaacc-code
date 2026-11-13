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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The mDNS service types of a snapserver that the discovery browses (task 11.6). Read from {@code server/snapserver.cpp} of
 * the tags (see {@code docs/tech.md}, "Discovery on Android"):
 *
 * <ul>
 *   <li>Snapcast 0.33 and newer: {@code _snapcast._tcp} (stream port, default 1704), {@code _snapcast-ctrl._tcp} (the TCP
 *   control port, default 1705), {@code _snapcast-http._tcp}, {@code _snapcast-https._tcp}.</li>
 *   <li>Snapcast 0.32 and older (the packages of most distributions): {@code _snapcast._tcp} and {@code _snapcast-stream._tcp}
 *   (stream port), {@code _snapcast-tcp._tcp} and {@code _snapcast-jsonrpc._tcp} (both the TCP control port),
 *   {@code _snapcast-http._tcp}. {@code _snapcast-ctrl._tcp} does not exist there: browsing only that name never found such
 *   a server.</li>
 * </ul>
 *
 * <p>The control port is the one YAACC lists and connects to. It is taken from a control service only: the stream service
 * is browsed to tell "the server announces itself, but not its control port" from "nothing is announced", and its port (the
 * stream port) is never turned into a control port. Pure.
 */
public final class SnapcastServiceTypes {

    /** What a service type says about the server. */
    public enum Role {
        /** The service carries the TCP control port: listed. */
        CONTROL("control port"),
        /** The service carries the stream port: a hint for the report, never listed. */
        STREAM_PROBE("stream port, a hint only");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final List<String> BROWSED = Collections.unmodifiableList(Arrays.asList(
            "_snapcast-ctrl._tcp", "_snapcast-tcp._tcp", "_snapcast._tcp"));

    private SnapcastServiceTypes() {
    }

    /** The types to browse, in the order they are started. */
    public static List<String> browsed() {
        return BROWSED;
    }

    /**
     * The browsed type that a reported type is, or null. {@code NsdManager} reports the type with a trailing dot, some
     * releases with the domain; anything else (another service, a longer name that only starts like ours, a domain other than
     * {@code local}) is not one of ours.
     */
    public static String canonical(String reported) {
        if (reported == null) {
            return null;
        }
        String type = reported.toLowerCase(Locale.ROOT);
        if (type.endsWith(".")) {
            type = type.substring(0, type.length() - 1);
        }
        if (type.endsWith(".local")) {
            type = type.substring(0, type.length() - ".local".length());
        }
        return BROWSED.contains(type) ? type : null;
    }

    /** @return the role of a reported type, or null if it is not one of the browsed types */
    public static Role roleOf(String reported) {
        String type = canonical(reported);
        if (type == null) {
            return null;
        }
        return "_snapcast._tcp".equals(type) ? Role.STREAM_PROBE : Role.CONTROL;
    }

    /** True for the roles whose services are resolved and listed. */
    public static boolean isListed(Role role) {
        return role == Role.CONTROL;
    }

    /** Which Snapcast versions announce this type (for the report). */
    public static String versionNote(String reported) {
        String type = canonical(reported);
        if ("_snapcast-ctrl._tcp".equals(type)) {
            return "Snapcast 0.33 and newer";
        }
        if ("_snapcast-tcp._tcp".equals(type)) {
            return "Snapcast 0.32 and older";
        }
        if ("_snapcast._tcp".equals(type)) {
            return "every version, the stream port";
        }
        return "not a browsed type";
    }

    /**
     * The identity of one announced service inside the directory: the same name under two types is two services (a server of
     * 0.32 announces its control port twice), so the loss of one of them does not drop the server while the other is there.
     */
    public static String serviceId(String reportedType, String serviceName) {
        String type = canonical(reportedType);
        return (type == null ? "?" : type) + "|" + (serviceName == null ? "" : serviceName);
    }
}
