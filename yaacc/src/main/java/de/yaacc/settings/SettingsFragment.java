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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.client.SnapcastClientPower;
import de.yaacc.multiroom.client.SnapcastClientPowerTexts;
import de.yaacc.multiroom.client.SnapcastClientPowers;
import de.yaacc.multiroom.client.SnapcastClientRuntime;
import de.yaacc.multiroom.client.SnapcastClientService;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NetworkInterfaceManager;
import de.yaacc.upnp.server.ServerAutostart;
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

        androidx.preference.ListPreference themePreference = findPreference(getString(R.string.settings_theme_key));
        if (themePreference != null) {
            themePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                AppCompatDelegate.setDefaultNightMode(
                        ThemeChoice.nightMode(newValue instanceof String ? (String) newValue : null));
                return true;
            });
        }

        androidx.preference.ListPreference logLevelPreference = findPreference(getString(R.string.settings_log_level_key));
        if (logLevelPreference != null) {
            logLevelPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof String) {
                    YaaccLogger.setLogLevel((String) newValue);
                }
                return true;
            });
        }

        // The boot receiver follows this setting (also applied at every start of the app, see Yaacc.onCreate). The listener runs
        // before the value is stored, so the new value is used.
        androidx.preference.CheckBoxPreference autostart = findPreference(getString(R.string.settings_local_server_autostart_chkbx));
        if (autostart != null) {
            autostart.setOnPreferenceChangeListener((preference, newValue) -> {
                ServerAutostart.applySetting(requireContext().getApplicationContext(), Boolean.TRUE.equals(newValue));
                return true;
            });
        }

        androidx.preference.Preference snapcastServers = findPreference(getString(R.string.settings_snapcast_servers));
        if (snapcastServers != null) {
            snapcastServers.setOnPreferenceClickListener(preference -> {
                SnapcastServersDialog.show(requireContext());
                return true;
            });
        }

        setUpSnapcastClientSpike();
        setUpRendererOutput();
        scrollToRequestedSection();

        // Populate renderer settings dynamically
        populateRendererSettings();

        // Populate network interface list
        populateNetworkInterfaceList();
    }

    /**
     * The output of the media renderer (task 9.1): "This device" or "Snapcast group". The chooser of the group is visible only
     * with the second; choosing the second without a group opens it at once. The summary of the group says which one is
     * stored (its name when the server is known, else the server), or that the stored value is damaged.
     */
    private void setUpRendererOutput() {
        final ListPreference output = findPreference(getString(R.string.settings_renderer_output));
        final androidx.preference.Preference group = findPreference(getString(R.string.settings_renderer_output_group));
        if (output == null || group == null) {
            return;
        }
        output.setEntries(new CharSequence[]{getString(R.string.settings_renderer_output_device),
                getString(R.string.settings_renderer_output_group_option)});
        output.setEntryValues(new CharSequence[]{SnapcastRendererOutput.VALUE_DEVICE, SnapcastRendererOutput.VALUE_GROUP});
        group.setVisible(SnapcastRendererOutput.VALUE_GROUP.equals(output.getValue()));
        updateRendererGroupSummary(group);
        output.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean isGroup = SnapcastRendererOutput.VALUE_GROUP.equals(newValue);
            group.setVisible(isGroup);
            if (isGroup && !SnapcastRendererOutput.isCanonicalGroupId(
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .getString(SnapcastRendererOutput.KEY_GROUP, null))) {
                SnapcastRendererGroupDialog.show(requireContext(), () -> updateRendererGroupSummary(group));
            }
            return true;
        });
        group.setOnPreferenceClickListener(preference -> {
            SnapcastRendererGroupDialog.show(requireContext(), () -> updateRendererGroupSummary(group));
            return true;
        });
    }

    // The output can also be chosen as a source of the OpenHome renderer (task 12.5): while this screen is shown it follows such a
    // change, so that both always show the same state. Kept in a field: the preferences hold their listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener rendererOutputListener = (preferences, key) -> {
        if (SnapcastRendererOutput.KEY_OUTPUT.equals(key) || SnapcastRendererOutput.KEY_GROUP.equals(key)) {
            android.app.Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(this::showRendererOutputAsStored);
            }
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(rendererOutputListener);
    }

    @Override
    public void onStop() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(rendererOutputListener);
        super.onStop();
    }

    /** Makes the list, the chooser's visibility and its summary show what is stored now (somebody else changed it). */
    private void showRendererOutputAsStored() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        final ListPreference output = findPreference(getString(R.string.settings_renderer_output));
        final androidx.preference.Preference group = findPreference(getString(R.string.settings_renderer_output_group));
        if (output == null || group == null) {
            return;
        }
        String stored = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(SnapcastRendererOutput.KEY_OUTPUT, SnapcastRendererOutput.VALUE_DEVICE);
        output.setValue(SnapcastRendererOutput.VALUE_GROUP.equals(stored) ? SnapcastRendererOutput.VALUE_GROUP
                : SnapcastRendererOutput.VALUE_DEVICE); // an unknown value shows as this device, as the renderer reads it (damaged)
        group.setVisible(SnapcastRendererOutput.VALUE_GROUP.equals(output.getValue()));
        updateRendererGroupSummary(group);
    }

    private void updateRendererGroupSummary(androidx.preference.Preference group) {
        if (!isAdded() || getContext() == null) {
            return; // the chooser answers after the fragment was detached (a rotation, the screen was left): nothing to show
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String stored;
        try {
            stored = preferences.getString(SnapcastRendererOutput.KEY_GROUP, null);
        } catch (RuntimeException damaged) {
            stored = "damaged"; // another type is stored: not a group id
        }
        if (stored == null || stored.isEmpty()) {
            group.setSummary(R.string.settings_renderer_output_group_none);
        } else if (!SnapcastRendererOutput.isCanonicalGroupId(stored)) {
            group.setSummary(R.string.settings_renderer_output_group_damaged);
        } else {
            // the name is display text of the server (already cleaned), the server is shown by its address; never the id
            de.yaacc.multiroom.SnapcastGroupDevice known = SnapcastHub.getShared(requireContext()).findGroup(stored);
            String endpoint = de.yaacc.multiroom.ui.SnapcastRowText.endpointOf(stored);
            group.setSummary(known == null
                    ? getString(R.string.settings_renderer_output_group_server, endpoint)
                    : getString(R.string.settings_renderer_output_group_named, known.getDisplayString(), endpoint));
        }
    }

    /**
     * The gear on the first page opens the settings at the Snapcast client section: the intent names the entry, only the keys of
     * {@link SnapcastSettingsTarget} are accepted. The list is not laid out yet, the library scrolls when it is.
     */
    private void scrollToRequestedSection() {
        if (getActivity() == null || getActivity().getIntent() == null) {
            return;
        }
        String key = SnapcastSettingsTarget.scrollKey(getActivity().getIntent().getStringExtra(SnapcastSettingsTarget.EXTRA_SCROLL_TO));
        if (key != null && findPreference(key) != null) {
            scrollToPreference(key);
        }
    }

    /**
     * The experimental client spike: the entries below the option (the checkbox "Snapcast client" and the test screen) are visible
     * only while the option is on, and switching the option off ends a running session. The checkbox goes through the one power API
     * of the client (the same state as the switch on the first page and the test screen).
     */
    private void setUpSnapcastClientSpike() {
        androidx.preference.CheckBoxPreference spike = findPreference(getString(R.string.settings_snapcast_client_spike));
        androidx.preference.CheckBoxPreference switchedOn = findPreference(getString(R.string.settings_snapcast_client_switched_on));
        androidx.preference.Preference open = findPreference(getString(R.string.settings_snapcast_client_spike_open));
        if (spike == null || open == null) {
            return;
        }
        open.setVisible(spike.isChecked());
        if (switchedOn != null) {
            switchedOn.setVisible(spike.isChecked());
            switchedOn.setChecked(SnapcastClientPowers.of(requireContext()).isOn());
            switchedOn.setOnPreferenceChangeListener((preference, newValue) -> {
                SnapcastClientPower.Outcome outcome = SnapcastClientPowers.of(requireContext()).setOn(Boolean.TRUE.equals(newValue));
                String reason = SnapcastClientPowerTexts.refusalText(requireContext(), outcome);
                if (reason != null) {
                    // the state stays on (the intent); the reason is shown, the first page and the test screen show "stopped"
                    android.widget.Toast.makeText(requireContext(), reason, android.widget.Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }
        spike.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = Boolean.TRUE.equals(newValue);
            open.setVisible(on);
            if (switchedOn != null) {
                switchedOn.setVisible(on);
            }
            if (!on && SnapcastClientRuntime.isActive()) {
                SnapcastClientService.stop(requireContext());
            }
            return true;
        });
        open.setOnPreferenceClickListener(preference -> {
            SnapcastClientSpikeDialog.show(requireContext());
            return true;
        });
    }

    private void populateNetworkInterfaceList() {
        ListPreference interfacePreference =
                findPreference(getString(R.string.settings_upnp_selected_interface_key));
        if (interfacePreference == null) return;

        List<NetworkInterfaceManager.NetworkInterfaceInfo> interfaces =
                NetworkInterfaceManager.getAvailableInterfaces(requireContext());

        // Get blacklist
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String blacklistStr = prefs.getString(getString(R.string.settings_upnp_if_filter_key),
                getString(R.string.settings_upnp_if_filter_default));
        List<String> blacklist = new ArrayList<>(List.of(blacklistStr.split(",")));

        List<String> entries = new ArrayList<>();
        List<String> values = new ArrayList<>();

        entries.add(getString(R.string.settings_upnp_selected_interface_none));
        values.add("");

        for (NetworkInterfaceManager.NetworkInterfaceInfo info : interfaces) {
            boolean isBlacklisted = blacklist.stream().anyMatch(b -> info.name.startsWith(b.trim()));
            String displayName = isBlacklisted ? info.displayName + " ⚠️" : info.displayName;
            entries.add(displayName);
            values.add(info.name);
        }

        interfacePreference.setEntries(entries.toArray(new String[0]));
        interfacePreference.setEntryValues(values.toArray(new String[0]));

        // Update warning when selection changes
        interfacePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            String selectedInterface = (String) newValue;
            if (!selectedInterface.isEmpty() && blacklist.stream().anyMatch(b -> selectedInterface.startsWith(b.trim()))) {
                interfacePreference.setSummary(getString(R.string.settings_upnp_selected_interface_blacklisted_warning));
            } else {
                interfacePreference.setSummary(getString(R.string.settings_upnp_selected_interface_summary));
            }
            return true;
        });

        // Set initial warning summary if selected interface is blacklisted
        String selectedInterface = prefs.getString(getString(R.string.settings_upnp_selected_interface_key), "");
        if (!selectedInterface.isEmpty() && blacklist.stream().anyMatch(b -> selectedInterface.startsWith(b.trim()))) {
            interfacePreference.setSummary(getString(R.string.settings_upnp_selected_interface_blacklisted_warning));
        }
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
