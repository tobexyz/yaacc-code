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
package de.yaacc.multiroom.discovery;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

/** The manual server list in the app's default preferences, as one string (see {@link SnapcastManualServers}). */
public final class SnapcastPreferencesStorage implements SnapcastManualServers.Storage {

    /** The preference key; a plain list of {@code host:port} lines, nothing else. */
    public static final String KEY = "snapcast_manual_servers";

    /** The key of the servers that hold a selected group (remembered across a restart); the same plain format. */
    public static final String SELECTED_KEY = "snapcast_selected_servers";

    private final SharedPreferences preferences;
    private final String key;

    public SnapcastPreferencesStorage(Context context) {
        this(context, KEY);
    }

    public SnapcastPreferencesStorage(Context context, String key) {
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        this.key = key;
    }

    @Override
    public String read() {
        return preferences.getString(key, null);
    }

    @Override
    public void write(String text) {
        preferences.edit().putString(key, text).apply();
    }
}
