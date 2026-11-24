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

import java.util.Locale;

/** The mDNS service type of a Sendspin player and the id of a found service. Pure. */
public final class SendspinServiceType {

    /** The type a player advertises (the specification: {@code _sendspin._tcp.local.}); the platform's browse call takes it without the domain. */
    public static final String TYPE = "_sendspin._tcp";

    private SendspinServiceType() {
    }

    /** True for the type of a Sendspin player, whatever dots and case the platform reports it with. */
    public static boolean isSendspin(String reportedType) {
        if (reportedType == null) {
            return false;
        }
        String t = reportedType.trim().toLowerCase(Locale.ROOT);
        while (t.startsWith(".")) {
            t = t.substring(1);
        }
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.equals(TYPE) || t.equals(TYPE + ".local");
    }

    /** One id per advertised service: the type and the instance name. */
    public static String serviceId(String reportedType, String serviceName) {
        String type = reportedType == null ? "" : reportedType.trim().toLowerCase(Locale.ROOT);
        return type + "|" + (serviceName == null ? "" : serviceName);
    }
}
