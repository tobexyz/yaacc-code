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
package de.yaacc.settings;

import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * The "Theme" setting: follow the system, always dark or always light. Replaces the former on/off
 * "Dark mode" check box (stored under another key as a boolean).
 */
public final class ThemeChoice {

    /** Follow the system setting. */
    public static final String STORED_SYSTEM = "system";
    /** Always dark. */
    public static final String STORED_DARK = "dark";
    /** Always light. */
    public static final String STORED_LIGHT = "light";
    /** What a fresh install gets (the former check box was on by default); also the fallback for unknown values. */
    public static final String DEFAULT_STORED_VALUE = STORED_DARK;

    private ThemeChoice() {
    }

    /**
     * @param storedValue the stored value, may be null
     * @return the {@link AppCompatDelegate} night mode for it; null and unknown values give the mode of
     * {@link #DEFAULT_STORED_VALUE}
     */
    public static int nightMode(String storedValue) {
        if (STORED_SYSTEM.equals(storedValue)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        if (STORED_LIGHT.equals(storedValue)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        return AppCompatDelegate.MODE_NIGHT_YES;
    }

    /**
     * The value the former check box maps to: on was "always dark", off was "use the system settings".
     */
    public static String fromLegacyDarkMode(boolean darkMode) {
        return darkMode ? STORED_DARK : STORED_SYSTEM;
    }

    /**
     * To be called once at app start, after the preference defaults were applied: an install updated
     * from a version with the check box has no value under the new key and keeps the look it had.
     *
     * @return true if a value was migrated
     */
    public static boolean migrateLegacyDarkMode(SharedPreferences preferences, String legacyKey, String key) {
        if (preferences.contains(key) || !(preferences.getAll().get(legacyKey) instanceof Boolean)) {
            return false;
        }
        boolean legacy = (Boolean) preferences.getAll().get(legacyKey);
        preferences.edit().putString(key, fromLegacyDarkMode(legacy)).remove(legacyKey).apply();
        return true;
    }
}
