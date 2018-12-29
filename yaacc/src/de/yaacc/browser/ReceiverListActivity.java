/*
* Copyright (C) 2014 www.yaacc.de
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

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.ListView;

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
public class ReceiverListActivity extends Activity implements
        UpnpClientListener {
    private static final String RECEIVER_LIST_NAVIGATOR = "RECEIVER_LIST_NAVIGATOR";
    private UpnpClient upnpClient = null;
    BrowseReceiverDeviceClickListener bReceiverDeviceClickListener = null;
    protected ListView contentList;

    @Override
    public void onResume() {
        super.onResume();
        populateReceiverDeviceList();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init(savedInstanceState);
    }

    private void init(Bundle savedInstanceState) {
        setContentView(R.layout.activity_receiver_list);
        upnpClient = ((Yaacc)getApplicationContext()).getUpnpClient();
        bReceiverDeviceClickListener = new BrowseReceiverDeviceClickListener();
        contentList = (ListView) findViewById(R.id.receiverList);
        registerForContextMenu(contentList);
        upnpClient.addUpnpClientListener(this);
        populateReceiverDeviceList();
    }

    /**
     * load app preferences
     *
     * @return app preferences
     */
    private SharedPreferences getPreferences() {
        return PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());

    }


    @Override
    public void onBackPressed() {
        Log.d(ReceiverListActivity.class.getName(), "onBackPressed() CurrentPosition");

        if (getParent() instanceof TabBrowserActivity) {
            ((TabBrowserActivity) getParent()).setCurrentTab(TabBrowserActivity.Tabs.CONTENT);
        }

    }


    /**
     * Shows all available devices in the receiver device list.
     */
    private void populateReceiverDeviceList() {
        IconDownloadCacheHandler.getInstance().resetCache();
        this.runOnUiThread(new Runnable() {
            public void run() {
// Define where to show the folder contents
                ListView deviceList = (ListView) findViewById(R.id.receiverList);
                deviceList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                LinkedList<Device> receiverDevices = new LinkedList<Device>(upnpClient.getDevicesProvidingAvTransportService());
                BrowseReceiverDeviceAdapter bDeviceAdapter = new BrowseReceiverDeviceAdapter(getApplicationContext(), receiverDevices, upnpClient.getReceiverDevices());
                deviceList.setAdapter(bDeviceAdapter);
                deviceList.setOnItemClickListener(bReceiverDeviceClickListener);
            }
        });
    }


    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {

        if (upnpClient.getReceiverDevices().contains(device)) {
            populateReceiverDeviceList();
        }
    }


    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        Log.d(this.getClass().toString(), "device removal called");
        if (upnpClient.getReceiverDevices().contains(device)) {
            populateReceiverDeviceList();
        }
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {

    }


}