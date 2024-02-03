/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.LinkedList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author @author Tobias Schoene (the openbit)
 */
public class ServerListFragment extends Fragment implements
        UpnpClientListener, OnBackPressedListener {
    private UpnpClient upnpClient = null;
    private RecyclerView contentList;
    private BrowseDeviceAdapter bDeviceAdapter;


    /**
     * load app preferences
     *
     * @return app preferences
     */
    private SharedPreferences getPreferences() {
        return PreferenceManager
                .getDefaultSharedPreferences(requireActivity().getApplicationContext());

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public boolean onBackPressed() {
        Log.d(ServerListFragment.class.getName(), "onBackPressed()");
        ((Yaacc) requireActivity().getApplicationContext()).exit();
        ServerListFragment.super.requireActivity().finish();
        return true;
    }

    /**
     * Shows all available devices in the main device list.
     */
    private void populateDeviceList() {
        //FIXME: Cache should be able to decide whether it is used for browsing or for devices lists
        //IconDownloadCacheHandler.getInstance().resetCache();
        //https://www.digitalocean.com/community/tutorials/android-recyclerview-android-cardview-example-tutorial
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                RecyclerView deviceList = contentList;
                if (deviceList.getAdapter() == null) {
                    bDeviceAdapter = new BrowseDeviceAdapter(getActivity(), deviceList, upnpClient, new ArrayList<>(upnpClient.getDevicesProvidingContentDirectoryService()));
                    deviceList.setAdapter(bDeviceAdapter);
                } else {
                    bDeviceAdapter.setDevices(new LinkedList<>(upnpClient.getDevicesProvidingContentDirectoryService()));
                }

            });

        }
    }

    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {
        populateDeviceList();

        if (upnpClient.getProviderDevice() != null && upnpClient.getProviderDevice().equals(device)) {
            try {
                requireActivity();
            } catch (IllegalStateException iex) {
                Log.d(getClass().getName(), "ignoring illegal state exception on device added", iex);
                return;
            }
            if (requireActivity().getParent() instanceof TabBrowserActivity) {
                ((TabBrowserActivity) requireActivity().getParent()).setCurrentTab(BrowserTabs.CONTENT);
            }
        }
    }

    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        Log.d(this.getClass().toString(), "device removal called");

        populateDeviceList();

    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {
        populateDeviceList();
    }

    @Override
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public void onResume() {
        super.onResume();
        //refresh device list
        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
        setLocalServerState(getView());
    }

    private void init(Bundle savedInstanceState, View view) {

        // local server startup
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();

        // Define where to show the folder contents for media
        contentList = view.findViewById(R.id.serverList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        ImageButton refresh = view.findViewById(R.id.serverListRefreshButton);
        refresh.setOnClickListener((v) -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), R.string.search_devices, Toast.LENGTH_LONG).show();
                });
            }
            upnpClient.searchDevices();
        });
        setLocalServerState(view);
        SwitchMaterial localServerEnabledSwitch = (SwitchMaterial) view.findViewById(R.id.serverListLocalServerEnabled);
        localServerEnabledSwitch.setOnClickListener((v -> {
            PreferenceManager.getDefaultSharedPreferences(v.getContext()).edit().putBoolean(v.getContext().getString(R.string.settings_local_server_chkbx), localServerEnabledSwitch.isChecked()).commit();
            if (v.getContext() instanceof TabBrowserActivity) {
                if (localServerEnabledSwitch.isChecked()) {
                    v.getContext().getApplicationContext().startForegroundService(((TabBrowserActivity) v.getContext()).getYaaccUpnpServerService());
                } else {
                    v.getContext().getApplicationContext().stopService(((TabBrowserActivity) v.getContext()).getYaaccUpnpServerService());
                }
                setLocalServerState(view);
            }


        }));
        // add ourself as listener
        upnpClient.addUpnpClientListener(this);
        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
    }

    private void setLocalServerState(View view) {
        SwitchMaterial localServerEnabledSwitch = (SwitchMaterial) view.findViewById(R.id.serverListLocalServerEnabled);
        localServerEnabledSwitch.setChecked(PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean(getContext().getString(R.string.settings_local_server_chkbx), false));
        ImageView providerImageView = (ImageView) view.findViewById(R.id.serverListProviderEnabled);
        if (PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean(getContext().getString(R.string.settings_local_server_provider_chkbx), false)) {
            providerImageView.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_sensors_32));
        } else {
            providerImageView.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_sensors_off_32));
        }
        ImageView receiverImageView = (ImageView) view.findViewById(R.id.serverListReceiverEnabled);
        if (PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean(getContext().getString(R.string.settings_local_server_receiver_chkbx), false)) {
            receiverImageView.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_devices_24));
        } else {
            receiverImageView.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_desktop_access_disabled_32));
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_server_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init(savedInstanceState, view);
    }

}