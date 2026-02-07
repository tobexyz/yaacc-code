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
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.util.Collection;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    public static final String MANAGE_EXTERNAL_SEEKING = "manage_external_seeking_";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preference, rootKey);
        EditTextPreference numberPreference = findPreference(getString(R.string.settings_browse_load_threads_key));
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

        androidx.preference.ListPreference logLevelPreference = findPreference(getString(R.string.settings_log_level_key));
        if (logLevelPreference != null) {
            logLevelPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                YaaccLogger.updateLogLevel();
                return true;
            });
        }

        // Populate renderer settings dynamically
        populateRendererSettings();
    }

    private void populateRendererSettings() {
        PreferenceCategory rendererCategory = findPreference("renderer_settings_category");
        if (rendererCategory == null) return;

        // Clear existing preferences
        rendererCategory.removeAll();

        // Get UPnP client and discovered devices
        UpnpClient upnpClient = ((de.yaacc.Yaacc) requireActivity().getApplicationContext()).getUpnpClient();
        if (upnpClient != null) {
            Collection<Device<?, ?, ?>> devices = upnpClient.getDevices();

            for (Device<?, ?, ?> device : devices) {
                if (device instanceof RemoteDevice && device.hasServices()) {
                    // Check if device has AVTransport service (is a renderer)
                    if (device.findService(org.fourthline.cling.model.types.ServiceType.valueOf("urn:schemas-upnp-org:service:AVTransport:1")) != null) {
                        addRendererPreference(rendererCategory, device);
                    }
                }
            }
        }

        if (rendererCategory.getPreferenceCount() == 0) {
            // Add info message if no renderers found
            androidx.preference.Preference infoPreference = new androidx.preference.Preference(getContext());
            infoPreference.setTitle("No UPnP renderers discovered");
            infoPreference.setSummary("Renderers will appear here when discovered on the network");
            infoPreference.setEnabled(false);
            rendererCategory.addPreference(infoPreference);
        }
    }

    private void addRendererPreference(PreferenceCategory category, Device<?, ?, ?> device) {
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        String deviceName = device.getDetails().getFriendlyName();

        CheckBoxPreference preference = new CheckBoxPreference(getContext());
        preference.setKey(MANAGE_EXTERNAL_SEEKING + deviceId);
        preference.setTitle(deviceName);
        preference.setSummary(getString(R.string.enable_server_side_seeking_for_external_urls));
        preference.setDefaultValue(false);

        category.addPreference(preference);
    }

}
