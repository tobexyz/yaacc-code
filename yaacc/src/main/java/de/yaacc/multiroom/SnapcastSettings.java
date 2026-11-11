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
package de.yaacc.multiroom;

/**
 * The keys and defaults of the Snapcast settings (the keys are the names of the string resources of the preference
 * screen, {@code settings_snapcast_discovery}, so a test pins them against the resource files). Pure.
 *
 * <p><b>Discovery is off by default.</b> mDNS discovery, the multicast lock it needs and the control connections to every
 * server that announces itself start only when the user switches it on (security review S19: otherwise every user who
 * opens the receivers tab would contact whatever announces a Snapcast service on a shared network). With it off, only the
 * servers the user typed in and the servers that hold a selected group (remembered across a restart) are used.
 */
public final class SnapcastSettings {

    /** The key of the discovery switch in the default preferences. */
    public static final String KEY_DISCOVERY = "settings_snapcast_discovery";
    /** The default of the discovery switch: off. */
    public static final boolean DISCOVERY_DEFAULT = false;

    /** Reads a boolean preference; {@code SharedPreferences} in the app. */
    public interface BooleanSource {
        boolean getBoolean(String key, boolean defaultValue);
    }

    private SnapcastSettings() {
    }

    /** @return whether discovery is switched on; false for no source or a value that can not be read */
    public static boolean isDiscoveryEnabled(BooleanSource source) {
        if (source == null) {
            return DISCOVERY_DEFAULT;
        }
        try {
            return source.getBoolean(KEY_DISCOVERY, DISCOVERY_DEFAULT);
        } catch (RuntimeException damaged) {
            return DISCOVERY_DEFAULT;
        }
    }

    public static boolean isDiscoveryKey(String key) {
        return KEY_DISCOVERY.equals(key);
    }
}
