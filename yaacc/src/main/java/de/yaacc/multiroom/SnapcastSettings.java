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
 * <p><b>Discovery is on by default</b> (the owner's decision of 2026-11-14; it reverses the default of security review S19).
 * With it on, the app sends mDNS queries on the Wi-Fi while a page that shows Snapcast (or the server list window) is open,
 * holds the multicast lock it needs for that, and the receivers page connects to every server that announces itself;
 * announcements are not authenticated. An install that never set the key gets the default (the key and the default are
 * declared once here, and the checkbox in {@code preference.xml} has the same default, pinned by a test); a stored choice of
 * the user is kept. A value that can not be read at all (another type stored, no preferences) counts as off: when the
 * user's choice is unknown nothing is scanned.
 */
public final class SnapcastSettings {

    /** The key of the discovery switch in the default preferences. */
    public static final String KEY_DISCOVERY = "settings_snapcast_discovery";
    /** The default of the discovery switch: on. */
    public static final boolean DISCOVERY_DEFAULT = true;
    /** What a value that can not be read counts as: off (fail safe, nothing is scanned when the choice is unknown). */
    private static final boolean DISCOVERY_UNREADABLE = false;

    /** Reads a boolean preference; {@code SharedPreferences} in the app. */
    public interface BooleanSource {
        boolean getBoolean(String key, boolean defaultValue);
    }

    private SnapcastSettings() {
    }

    /** @return whether discovery is switched on: the stored choice, {@link #DISCOVERY_DEFAULT} if none; false for no source or a value that can not be read */
    public static boolean isDiscoveryEnabled(BooleanSource source) {
        if (source == null) {
            return DISCOVERY_UNREADABLE;
        }
        try {
            return source.getBoolean(KEY_DISCOVERY, DISCOVERY_DEFAULT);
        } catch (RuntimeException damaged) {
            return DISCOVERY_UNREADABLE;
        }
    }

    public static boolean isDiscoveryKey(String key) {
        return KEY_DISCOVERY.equals(key);
    }
}
