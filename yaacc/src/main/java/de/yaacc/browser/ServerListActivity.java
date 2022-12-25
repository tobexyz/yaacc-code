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
 * @author @author Tobias Schoene (the openbit)
 */
public class ServerListActivity extends Activity implements
        UpnpClientListener {
    private UpnpClient upnpClient = null;
    private ListView contentList;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_server_list);
// local server startup
        upnpClient = ((Yaacc)getApplicationContext()).getUpnpClient();


// Define where to show the folder contents for media
        contentList = (ListView) findViewById(R.id.serverList);
        registerForContextMenu(contentList);

// add ourself as listener
        upnpClient.addUpnpClientListener(this);

        populateDeviceList();

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
        Log.d(ServerListActivity.class.getName(), "onBackPressed()");
        ((Yaacc)getApplicationContext()).exit();
        super.finish();


    }


    /**
     * Shows all available devices in the main device list.
     */
    private void populateDeviceList() {
        //FIXME: Cache should be able to decide whether it is used for browsing or for devices lists
        IconDownloadCacheHandler.getInstance().resetCache();
        this.runOnUiThread(new Runnable() {
            public void run() {
                ListView deviceList = (ListView) findViewById(R.id.serverList);
                deviceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                BrowseDeviceAdapter bDeviceAdapter = new BrowseDeviceAdapter(getApplicationContext(), new LinkedList<Device>(upnpClient.getDevicesProvidingContentDirectoryService()));
                deviceList.setAdapter(bDeviceAdapter);
                deviceList.setOnItemClickListener(new ServerListClickListener(upnpClient, ServerListActivity.this));
            }
        });
    }


    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {
        populateDeviceList();

        if (upnpClient.getProviderDevice() != null && upnpClient.getProviderDevice().equals(device)) {
            if (getParent() instanceof TabBrowserActivity) {
                ((TabBrowserActivity) getParent()).setCurrentTab(BrowserTabs.CONTENT);
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
    public void onResume(){
        super.onResume();
        //refresh device list
        populateDeviceList();
    }

}