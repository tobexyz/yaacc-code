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

/** The keys and defaults of the FCast settings (the keys are the names of the string resources of the preference screen, task 5.1). Pure. */
public final class FCastSettings {
    /** The key of the discovery switch in the default preferences. */
    public static final String KEY_DISCOVERY = "settings_fcast_discovery";
    /** The default of the discovery switch: on (decision D7). */
    public static final boolean DISCOVERY_DEFAULT = true;
    /** The key of the list of receivers that the user added by hand. */
    public static final String KEY_MANUAL_RECEIVERS = "fcast_manual_receivers";
    /** The key of the list of the receivers that were selected in the last run (shown as offline until they are seen). */
    public static final String KEY_REMEMBERED_RECEIVERS = "fcast_remembered_receivers";
    /** The key of the keys (fingerprints) that came with the links of receivers, one per receiver (task 7.2). */
    public static final String KEY_RECEIVER_PINS = "fcast_receiver_pins";
    /** The key of the switch for the encrypted version 4 (task 7.2; the same as the name of its string resource). */
    public static final String KEY_V4 = "settings_fcast_v4";
    /** The default of the version 4 switch: on. A value that can not be read counts as on too (never go back to the plain version because of a damaged value). */
    public static final boolean V4_DEFAULT = true;
    /** What a value that can not be read counts as: off (never search because of a damaged value). */
    public static final boolean DISCOVERY_UNREADABLE = false;

    /** Reads a boolean preference (the shape of {@code SharedPreferences.getBoolean}). */
    public interface BooleanSource {
        boolean getBoolean(String key, boolean defaultValue);
    }

    private FCastSettings() {
    }

    /** @return whether discovery is switched on: the stored choice, {@link #DISCOVERY_DEFAULT} if none; false for no source or an unreadable value */
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

    /** @return whether receivers whose key is known are spoken to in the encrypted version 4 (on Android 10 and later) */
    public static boolean isV4Enabled(BooleanSource source) {
        if (source == null) {
            return V4_DEFAULT;
        }
        try {
            return source.getBoolean(KEY_V4, V4_DEFAULT);
        } catch (RuntimeException damaged) {
            return V4_DEFAULT;
        }
    }

    /** True if a changed preference key is the discovery switch. */
    public static boolean isDiscoveryKey(String key) {
        return KEY_DISCOVERY.equals(key);
    }
}
