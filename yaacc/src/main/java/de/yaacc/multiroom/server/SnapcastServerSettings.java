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
package de.yaacc.multiroom.server;

/**
 * The settings of the built-in Snapcast server (issue #330). The switch is <b>off by default</b> and a value that can not be read counts
 * as off (rule S1): the server listens on the network, so it starts only when the user chose it.
 */
public final class SnapcastServerSettings {

    /** The key of the switch (the name of the string resource of the preference screen). */
    public static final String KEY_ENABLED = "settings_snapcast_server";
    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_BUFFER_MS = 1000;
    private static final int MIN_BUFFER_MS = 200;
    private static final int MAX_BUFFER_MS = 5000;
    /** An mDNS service name is at most 63 bytes. */
    private static final int MAX_SERVICE_NAME = 63;

    /** Reads a boolean preference. */
    public interface BooleanSource {
        boolean getBoolean(String key, boolean defaultValue);
    }

    private SnapcastServerSettings() {
    }

    public static boolean isEnabled(BooleanSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
        } catch (RuntimeException damaged) {
            return false;
        }
    }

    /** The buffer the clients are told: the value if it is in the range of 200 to 5000 ms, else the default for a value above and the minimum below. */
    public static int bufferMs(int requested) {
        if (requested > MAX_BUFFER_MS) {
            return DEFAULT_BUFFER_MS;
        }
        return Math.max(MIN_BUFFER_MS, requested);
    }

    /** The name of the announcement: {@code YAACC <name of the phone>}, cleaned and short. */
    public static String serviceName(String phoneName) {
        StringBuilder sb = new StringBuilder("YAACC");
        String clean = "";
        if (phoneName != null) {
            StringBuilder c = new StringBuilder();
            for (int i = 0; i < phoneName.length(); i++) {
                char ch = phoneName.charAt(i);
                c.append(Character.isISOControl(ch) ? ' ' : ch);
            }
            clean = c.toString().trim();
        }
        if (!clean.isEmpty()) {
            sb.append(' ').append(clean);
        }
        return sb.length() > MAX_SERVICE_NAME ? sb.substring(0, MAX_SERVICE_NAME) : sb.toString();
    }
}
