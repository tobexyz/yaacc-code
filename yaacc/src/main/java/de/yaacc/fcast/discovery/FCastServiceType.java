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

/** The mDNS service type of FCast and how the platform reports it. Pure. */
public final class FCastServiceType {
    /** What {@code NsdManager.discoverServices} is asked for. */
    public static final String TYPE = "_fcast._tcp";

    private FCastServiceType() {
    }

    /**
     * The platform reports a type with a trailing dot ({@code _fcast._tcp.}), some releases with the domain as well, in the case
     * the announcer used.
     *
     * @return the type in lower case without the domain and the trailing dot, or "" for null
     */
    public static String canonical(String reported) {
        if (reported == null) {
            return "";
        }
        String type = reported.trim().toLowerCase(java.util.Locale.ROOT);
        while (type.endsWith(".")) {
            type = type.substring(0, type.length() - 1);
        }
        if (type.endsWith(".local")) {
            type = type.substring(0, type.length() - ".local".length());
        }
        return type;
    }

    /** True if the platform's report names the FCast service type. */
    public static boolean isFCast(String reported) {
        return TYPE.equals(canonical(reported));
    }

    /** The key of a service for the lost events: type and instance name (the name is display text). */
    public static String serviceId(String reportedType, String serviceName) {
        return canonical(reportedType) + "|" + (serviceName == null ? "" : serviceName);
    }
}
