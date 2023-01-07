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
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.PreferenceFragment;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.LinkedList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class SettingsFragment extends PreferenceFragment implements UpnpClientListener {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preference);

        populateDeviceLists();
        ((Yaacc) getActivity().getApplicationContext()).getUpnpClient().addUpnpClientListener(this);


    }


    private void populateDeviceLists() {
        LinkedList<Device> devices = new LinkedList<Device>();
        // TODO: populate with found devices

        UpnpClient upnpClient = ((Yaacc) getActivity().getApplicationContext()).getUpnpClient();

        if (upnpClient != null) {
            if (upnpClient.isInitialized()) {
                devices.addAll(upnpClient
                        .getDevicesProvidingContentDirectoryService());
            }

            ListPreference providerLp = (ListPreference) findPreference(getString(R.string.settings_selected_provider_title));

            // One entry per found device for providing media data
            ArrayList<CharSequence> providerEntries = new ArrayList<CharSequence>();
            ArrayList<CharSequence> providerEntryValues = new ArrayList<CharSequence>();
            for (Device currentDevice : devices) {
                providerEntries.add(currentDevice.getDisplayString());
                providerEntryValues.add(currentDevice.getIdentity().getUdn()
                        .getIdentifierString());
            }

            providerLp.setEntries(providerEntries
                    .toArray(new CharSequence[providerEntries.size()]));
            providerLp.setEntryValues(providerEntryValues
                    .toArray(new CharSequence[providerEntries.size()]));

            devices = new LinkedList<Device>();
            devices.addAll(upnpClient.getDevicesProvidingAvTransportService());

            // One entry per found device for receiving media data
            MultiSelectListPreference receiverMsLp = (MultiSelectListPreference) findPreference(getString(R.string.settings_selected_receivers_title));
            ArrayList<CharSequence> receiverEntries = new ArrayList<CharSequence>();
            ArrayList<CharSequence> receiverEntryValues = new ArrayList<CharSequence>();
            for (Device currentDevice : devices) {
                receiverEntries.add(currentDevice.getDisplayString());
                receiverEntryValues.add(currentDevice.getIdentity().getUdn()
                        .getIdentifierString());
            }


            receiverMsLp.setEntries(receiverEntries
                    .toArray(new CharSequence[receiverEntries.size()]));
            receiverMsLp.setEntryValues(receiverEntryValues
                    .toArray(new CharSequence[receiverEntries.size()]));
        }
    }

    @Override
    public void deviceAdded(Device<?, ?, ?> device) {
        if (this.isVisible()) {
            populateDeviceLists();
        }
    }

    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        if (this.isVisible()) {
            populateDeviceLists();
        }
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {
        // TODO Auto-generated method stub

    }

}
