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
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schoene (the openbit)
 */
public class PlayerListActivity extends Activity implements
        UpnpClientListener {

    private UpnpClient upnpClient = null;
    private PlayerListItemAdapter itemAdapter;
    PlayerListItemClickListener itemClickListener = null;
    protected ListView contentList;
    private Player selectedPlayer;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init(savedInstanceState);
    }

    private void init(Bundle savedInstanceState) {

        setContentView(R.layout.activity_player_list);
        upnpClient = ((Yaacc)getApplicationContext()).getUpnpClient();
        itemClickListener = new PlayerListItemClickListener();
        contentList = (ListView) findViewById(R.id.playerList);
        registerForContextMenu(contentList);
        upnpClient.addUpnpClientListener(this);
        populatePlayerList();

    }

    @Override
    protected void onResume() {
        super.onResume();
        populatePlayerList();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

    }

    /**
     * Selects the place in the UI where the items are shown and renders the
     * content directory
     */
    private void populatePlayerList() {


        this.runOnUiThread(new Runnable() {
            public void run() {
                itemAdapter = new PlayerListItemAdapter(upnpClient);
                contentList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                contentList.setAdapter(itemAdapter);
                contentList.setOnItemClickListener(itemClickListener);
            }
        });
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
        if (getParent() instanceof TabBrowserActivity) {
            ((TabBrowserActivity) getParent()).setCurrentTab(TabBrowserActivity.Tabs.RECEIVER);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        if (v instanceof ListView) {
            ListView listView = (ListView) v;
            Object item = listView.getAdapter().getItem(info.position);
            if (item instanceof Player) {
                selectedPlayer = (Player) item;
            }
        }
        itemClickListener.onCreateContextMenu(menu, v, menuInfo);
    }


    @Override
    public boolean onContextItemSelected(MenuItem item) {
        boolean result = itemClickListener.onContextItemSelected(selectedPlayer,
                item, getApplicationContext());
        populatePlayerList();
        return result;
    }

    @Override
    public void deviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {

    }

} 