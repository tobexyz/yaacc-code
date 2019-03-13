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
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ListView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.support.model.DIDLObject;

import java.util.ArrayList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.util.image.IconDownloadCacheHandler;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schöne (the openbit)
 */
public class ContentListActivity extends Activity implements OnClickListener,
        UpnpClientListener {
    public static final String CONTENT_LIST_NAVIGATOR = "CONTENT_LIST_NAVIGATOR";
    private UpnpClient upnpClient = null;
    private BrowseItemAdapter bItemAdapter;
    ContentListClickListener bItemClickListener = null;

    private DIDLObject selectedDIDLObject;


    protected ListView contentList;
    private Navigator navigator = null;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init(savedInstanceState);
    }

   @Override
    public void onResume() {
        super.onResume();
       bItemClickListener = new ContentListClickListener(upnpClient, this);
        if (upnpClient.getProviderDevice() != null) {
            if(navigator != null){
                populateItemList();
            }else {
                showMainFolder();
            }
        } else {

            clearItemList();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        bItemClickListener = new ContentListClickListener(upnpClient, this);
        if (upnpClient.getProviderDevice() != null) {
            if(navigator != null){
                populateItemList();
            }else {
                showMainFolder();
            }
        } else {

            clearItemList();
        }
    }

    private void init(Bundle savedInstanceState) {
        setContentView(R.layout.activity_content_list);
        upnpClient = ((Yaacc)getApplicationContext()).getUpnpClient();
        contentList = (ListView) findViewById(R.id.contentList);
        registerForContextMenu(contentList);
        upnpClient.addUpnpClientListener(this);
        bItemClickListener = new ContentListClickListener(upnpClient, this);
        if (upnpClient.getProviderDevice() != null) {
            /*
            if(savedInstanceState == null || savedInstanceState.getSerializable(CONTENT_LIST_NAVIGATOR) == null){

                showMainFolder();
            }else {
                navigator = (Navigator) savedInstanceState.getSerializable(CONTENT_LIST_NAVIGATOR);
                populateItemList();
            }
            */
            showMainFolder();
        } else {

            clearItemList();
        }

    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(CONTENT_LIST_NAVIGATOR,navigator);
    }


    /**
     * Tries to populate the browsing area if a providing device is configured
     */
    private void showMainFolder() {
        navigator = new Navigator();
        Position pos = new Position(Navigator.ITEM_ROOT_OBJECT_ID, upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString());
        navigator.pushPosition(pos);
        populateItemList();
    }

    @Override
    public void onClick(View v) {
        populateItemList();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return bItemClickListener.onContextItemSelected(selectedDIDLObject,
                item, getApplicationContext());
    }


    /**
     * Stepps 'up' in the folder hierarchy or closes App if on device level.
     */
    @Override
    public void onBackPressed() {
        Log.d(ContentListActivity.class.getName(), "onBackPressed() CurrentPosition: " + navigator.getCurrentPosition());
        if(bItemAdapter != null){
            bItemAdapter.cancelRunningTasks();
        }
        String currentObjectId = navigator.getCurrentPosition() == null ? Navigator.ITEM_ROOT_OBJECT_ID : navigator.getCurrentPosition().getObjectId();
        if (Navigator.ITEM_ROOT_OBJECT_ID.equals(currentObjectId)) {
            if (getParent() instanceof TabBrowserActivity) {
                ((TabBrowserActivity) getParent()).setCurrentTab(TabBrowserActivity.Tabs.SERVER);
            }

        } else {
            //Fixme: Cache should store information for different folders....
            //IconDownloadCacheHandler.getInstance().resetCache();
            final ListView itemList = (ListView) findViewById(R.id.contentList);
            Position pos = navigator.popPosition(); // First pop is our
            // currentPosition
            initBrowsItemAdapter(itemList);
            ContentListClickListener bItemClickListener = new ContentListClickListener(upnpClient,this);
            itemList.setOnItemClickListener(bItemClickListener);
        }
    }

    private void initBrowsItemAdapter(ListView itemList) {
        bItemAdapter = new BrowseItemAdapter(this,navigator);
        itemList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        itemList.setAdapter(bItemAdapter);
        itemList.setOnScrollListener(bItemAdapter);
    }

    /**
     * Creates context menu for certain actions on a specific item.
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenuInfo menuInfo) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        if (v instanceof ListView) {
            ListView listView = (ListView) v;
            Object item = listView.getAdapter().getItem(info.position);
            if (item instanceof DIDLObject) {
                selectedDIDLObject = (DIDLObject) item;
            }
        }
        menu.setHeaderTitle(v.getContext().getString(
                R.string.browse_context_title));
        ArrayList<String> menuItems = new ArrayList<String>();
        menuItems.add(v.getContext().getString(R.string.browse_context_play_all));
        menuItems.add(v.getContext().getString(R.string.browse_context_play));
        //menuItems.add(v.getContext().getString( R.string.browse_context_add_to_playplist));
        menuItems.add(v.getContext()
                .getString(R.string.browse_context_download));
        for (int i = 0; i < menuItems.size(); i++) {
            menu.add(Menu.NONE, i, i, menuItems.get(i));
        }
    }

    /**
     * Selects the place in the UI where the items are shown and renders the
     * content directory
     *
     */
    public void populateItemList() {

        //IconDownloadCacheHandler.getInstance().resetCache();
        this.runOnUiThread(new Runnable() {
            public void run() {
                if(bItemAdapter != null){
                    bItemAdapter.cancelRunningTasks();
                }
                initBrowsItemAdapter(contentList);
                contentList.setOnItemClickListener(bItemClickListener);
            }
        });
    }

    private void clearItemList() {
        this.runOnUiThread(new Runnable() {
            public void run() {
                navigator = new Navigator();
                Position pos =  new Position(Navigator.ITEM_ROOT_OBJECT_ID, null);
                navigator.pushPosition(pos);
                bItemAdapter = new BrowseItemAdapter(getApplicationContext(),navigator);
                contentList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                contentList.setAdapter(bItemAdapter);
                contentList.setOnItemClickListener(bItemClickListener);
            }
        });
    }


    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {

    }

    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        Log.d(this.getClass().toString(), "device removal called");
        if (!device.equals(upnpClient.getProviderDevice())) {
        //    clearItemList();
        }
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {

    }

    /**
     * Returns Object containing about the current navigation way
     *
     * @return information about current navigation
     */
    public Navigator getNavigator() {
        return navigator;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }
}

