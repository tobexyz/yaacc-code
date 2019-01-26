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

import android.Manifest;
import android.app.ActivityGroup;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TabHost;
import android.widget.Toast;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.YaaccLogActivity;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schoene (the openbit)
 */
public class TabBrowserActivity extends ActivityGroup implements OnClickListener,
        UpnpClientListener {
    private static String[] permissions = new String[]{
        Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.GET_TASKS,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.WAKE_LOCK
    };
    private TabHost tabHost;
    //FIXME dirty
    public static boolean leftSettings=false;
    private static String CURRENT_TAB_KEY="currentTab";

    public enum Tabs {
        SERVER,
        CONTENT,
        RECEIVER,
        PLAYER;

        public static Tabs valueOf(int ordinal){
            for( Tabs tab : values()){
                if(tab.ordinal() == ordinal){
                    return tab;

                }
            }
            return null;
        }

    }

    private UpnpClient upnpClient = null;


    private Intent serverService = null;
    private TabHost.TabSpec serverTab;
    private TabHost.TabSpec contentTab;
    private TabHost.TabSpec receiverTab;
    private TabHost.TabSpec playerTab;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_TAB_KEY, tabHost.getCurrentTab());
    }

    private boolean checkIfAlreadyhavePermission() {
        for (String permission: permissions) {
            int permissionState = ContextCompat.checkSelfPermission(this, permission);
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestForSpecificPermission() {
        ActivityCompat.requestPermissions(this, permissions,
                101);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tab_browse);
        // local server startup
        upnpClient = ((Yaacc)getApplicationContext()).getUpnpClient();


        tabHost = (TabHost) findViewById(R.id.browserTabHost);
        tabHost.setup(this.getLocalActivityManager());
        serverTab = tabHost.newTabSpec("server").setIndicator(getResources().getString(R.string.title_activity_server_list), getResources().getDrawable(R.drawable.device_48_48)).setContent(new Intent(this, ServerListActivity.class));
        tabHost.addTab(serverTab);
        contentTab = tabHost.newTabSpec("content").setIndicator(getResources().getString(R.string.title_activity_content_list), getResources().getDrawable(R.drawable.cdtrack)).setContent(new Intent(this, ContentListActivity.class));
        tabHost.addTab(contentTab);
        receiverTab = tabHost.newTabSpec("receiver").setIndicator(getResources().getString(R.string.title_activity_receiver_list), getResources().getDrawable(R.drawable.laptop_48_48)).setContent(new Intent(this, ReceiverListActivity.class));
        tabHost.addTab(receiverTab);
        playerTab = tabHost.newTabSpec("player").setIndicator(getResources().getString(R.string.title_activity_player_list), getResources().getDrawable(R.drawable.player_play)).setContent(new Intent(this, PlayerListActivity.class));
        tabHost.addTab(playerTab);
        // ask for permissions
        int appVersion = Build.VERSION.SDK_INT;
        if (appVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (!checkIfAlreadyhavePermission()) {
                requestForSpecificPermission();
            }else{
                Log.d(getClass().getName(), "All permissions granted");
            }
        }

        // add ourself as listener
        upnpClient.addUpnpClientListener(this);
        if(savedInstanceState != null){
            setCurrentTab(Tabs.valueOf(savedInstanceState.getInt(CURRENT_TAB_KEY, Tabs.CONTENT.ordinal())));
        }else if (upnpClient.getProviderDevice() != null) {
            setCurrentTab(Tabs.CONTENT);

        }
    }

    public void setCurrentTab(final Tabs content) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (content) {
                    case CONTENT: {
                        tabHost.setCurrentTab(Tabs.CONTENT.ordinal());
                        break;
                    }
                    case SERVER: {
                        tabHost.setCurrentTab(Tabs.SERVER.ordinal());
                        break;
                    }
                    case PLAYER: {
                        tabHost.setCurrentTab(Tabs.PLAYER.ordinal());
                        break;
                    }
                    case RECEIVER: {
                        tabHost.setCurrentTab(Tabs.RECEIVER.ordinal());
                        break;
                    }
                }
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
    public void onResume() {
        boolean serverOn = getPreferences().getBoolean(
                getString(R.string.settings_local_server_chkbx), false);
        if (serverOn) {
            // (Re)Start upnpserver service for avtransport
            if(leftSettings){
                getApplicationContext().stopService(getYaaccUpnpServerService());
            }
            getApplicationContext().startService(getYaaccUpnpServerService());
            Log.d(this.getClass().getName(), "Starting local service");
        } else {
            getApplicationContext().stopService(getYaaccUpnpServerService());
            Log.d(this.getClass().getName(), "Stopping local service");
        }
        leftSettings = false;
        super.onResume();
    }



    /**
     * Singleton to avoid multiple instances when switch
     *
     * @return
     */
    private Intent getYaaccUpnpServerService() {
        if (serverService == null) {
            serverService = new Intent(getApplicationContext(),
                    YaaccUpnpServerService.class);
        }
        return serverService;
    }

    @Override
    public void onBackPressed() {
        Log.d(TabBrowserActivity.class.getName(), "onBackPressed() ");

    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenuInfo menuInfo) {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }


    /**
     * Navigation in option menu
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_exit:
                ((Yaacc)getApplicationContext()).exit();
                return true;
            case R.id.menu_settings:
                Intent i = new Intent(this, SettingsActivity.class);
                startActivity(i);
                return true;
            case R.id.yaacc_about:
                AboutActivity.showAbout(this);
                return true;
            case R.id.yaacc_log:
                YaaccLogActivity.showLog(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
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

    @Override
    public void onClick(View view) {

    }
}