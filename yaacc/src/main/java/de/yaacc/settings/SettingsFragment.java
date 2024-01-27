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
import android.text.InputType;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import de.yaacc.R;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preference, rootKey);
        EditTextPreference numberPreference = findPreference(getString(R.string.settings_device_playback_offset_key));
        if (numberPreference != null) {
            numberPreference.setOnBindEditTextListener(
                    editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        }
        numberPreference = findPreference(getString(R.string.settings_browse_load_threads_key));
        if (numberPreference != null) {
            numberPreference.setOnBindEditTextListener(
                    editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        }
        numberPreference = findPreference(getString(R.string.settings_browse_chunk_size_key));
        if (numberPreference != null) {
            numberPreference.setOnBindEditTextListener(
                    editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        }

        CheckBoxPreference checkBoxPreference = findPreference(getString(R.string.settings_dark_mode_key));
        if (checkBoxPreference != null) {
            checkBoxPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof Boolean && (Boolean) newValue) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                }
                return true;
            });
        }

    }

}
