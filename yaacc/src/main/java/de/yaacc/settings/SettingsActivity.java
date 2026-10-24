/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class SettingsActivity extends AppCompatActivity {

    // Set when the user leaves the settings, read once by the browser that resumes next, so that
    // changed server settings take effect. Lifecycle callbacks only, i.e. main thread.
    private static boolean leftSettings = false;

    /**
     * Tells whether the settings were left since the last call, and resets that. Called by the
     * browser activities in {@code onResume}.
     *
     * @return true if the settings were left since the last call
     */
    public static boolean consumeLeftSettings() {
        boolean result = leftSettings;
        leftSettings = false;
        return result;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment()).commit();
    }

    @Override
    protected void onPause() {
        super.onPause();
        leftSettings = true;
    }
}
