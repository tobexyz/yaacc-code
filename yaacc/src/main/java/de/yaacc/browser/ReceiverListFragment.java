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
import android.widget.ImageButton;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;

import java.util.LinkedList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.util.image.IconDownloadCacheHandler;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schoene (the openbit)
 */
public class ReceiverListFragment extends Fragment implements
        UpnpClientListener, OnBackPressedListener {
    private static final String RECEIVER_LIST_NAVIGATOR = "RECEIVER_LIST_NAVIGATOR";
    protected RecyclerView contentList;
    private UpnpClient upnpClient = null;
    private Device selectedDevice = null;
    private BrowseReceiverDeviceAdapter bDeviceAdapter;

    @Override
    public void onResume() {
        super.onResume();
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                populateReceiverDeviceList();
            }
        });
        thread.start();
    }


    private void init(Bundle savedInstanceState, View view) {

        upnpClient = ((Yaacc) getActivity().getApplicationContext()).getUpnpClient();
        contentList = view.findViewById(R.id.receiverList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        upnpClient.addUpnpClientListener(this);
        ImageButton refresh = view.findViewById(R.id.receiverListRefreshButton);
        refresh.setOnClickListener((v) -> {
            upnpClient.searchDevices();
        });
        Thread thread = new Thread(() -> {
            populateReceiverDeviceList();
        });
        thread.start();
    }

    /**
     * load app preferences
     *
     * @return app preferences
     */
    private SharedPreferences getPreferences() {
        return PreferenceManager
                .getDefaultSharedPreferences(getActivity().getApplicationContext());

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public boolean onBackPressed() {

        Log.d(ReceiverListFragment.class.getName(), "onBackPressed() CurrentPosition");
        if (getActivity().getParent() instanceof TabBrowserActivity) {
            ((TabBrowserActivity) getActivity().getParent()).setCurrentTab(BrowserTabs.CONTENT);
        }

        return true;
    }

    /**
     * Shows all available devices in the receiver device list.
     */
    private void populateReceiverDeviceList() {
        IconDownloadCacheHandler.getInstance().resetCache();
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    LinkedList<Device<?, ?, ?>> receiverDevices = new LinkedList<>(upnpClient.getDevicesProvidingAvTransportService());
                    if (bDeviceAdapter == null) {
                        bDeviceAdapter = new BrowseReceiverDeviceAdapter(getActivity(), contentList, upnpClient, receiverDevices, upnpClient.getReceiverDevices());
                        contentList.setAdapter(bDeviceAdapter);
                    } else {
                        bDeviceAdapter.setDevices(receiverDevices);
                    }
                }
            });
        }
    }


    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {
        populateReceiverDeviceList();
    }


    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        populateReceiverDeviceList();
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {
        
    }

    @Override
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_receiver_list, container, false);
        init(savedInstanceState, v);
        return v;
    }
}