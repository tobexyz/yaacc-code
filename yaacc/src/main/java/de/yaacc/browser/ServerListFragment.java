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
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.fragment.app.Fragment;

import org.fourthline.cling.model.meta.Device;

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
    private ListView contentList;
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
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                ListView deviceList = contentList;
                deviceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                if (deviceList.getAdapter() == null) {
                    bDeviceAdapter = new BrowseDeviceAdapter(getActivity(), new LinkedList<>(upnpClient.getDevicesProvidingContentDirectoryService()));
                    deviceList.setAdapter(bDeviceAdapter);
                    deviceList.setOnItemClickListener(new ServerListClickListener(upnpClient, ServerListFragment.this));
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
    public void onResume() {
        super.onResume();
        //refresh device list
        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
    }

    private void init(Bundle savedInstanceState, View view) {

        // local server startup
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();


        // Define where to show the folder contents for media
        contentList = (ListView) view.findViewById(R.id.serverList);
        registerForContextMenu(contentList);

        // add ourself as listener
        upnpClient.addUpnpClientListener(this);

        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_server_list, container, false);
        init(savedInstanceState, v);
        return v;
    }

}