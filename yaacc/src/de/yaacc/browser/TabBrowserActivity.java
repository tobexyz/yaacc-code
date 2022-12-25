/*
* Copyright (C) 2014-2022 www.yaacc.de
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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;

import com.google.android.material.tabs.TabLayout;

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
public class TabBrowserActivity extends FragmentActivity implements OnClickListener,
        UpnpClientListener {
    private static String[] permissions = new String[]{
        Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.GET_TASKS,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.WAKE_LOCK
    };
    private TabLayout tabLayout;
    //FIXME dirty
    public static boolean leftSettings=false;
    private static String CURRENT_TAB_KEY="currentTab";
    private ViewPager2 viewPager;
    private FragmentStateAdapter pagerAdapter;




    private UpnpClient upnpClient = null;


    private Intent serverService = null;
    private TabLayout.Tab serverTab;
    private TabLayout.Tab contentTab;
    private TabLayout.Tab receiverTab;
    private TabLayout.Tab playerTab;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_TAB_KEY, tabLayout.getSelectedTabPosition());
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

        viewPager = findViewById(R.id.browserTabPager);
        tabLayout =  findViewById(R.id.browserTabLayout);
        pagerAdapter = new TabBrowserFragmentStateAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        /*
        tabLayout = (TabLayout) findViewById(R.id.browserTabLayout);
        //FIXME tabLayout.setup(this.getLocalActivityManager());
        serverTab = tabLayout.newTab().setText("server"); //FIXME.setIndicator(getResources().getString(R.string.title_activity_server_list), getResources().getDrawable(R.drawable.device_48_48)).setContent(new Intent(this, ServerListActivity.class));
        tabLayout.addTab(serverTab);
        contentTab = tabLayout.newTab().setText("content"); //FIXME.setIndicator(getResources().getString(R.string.title_activity_content_list), getResources().getDrawable(R.drawable.cdtrack)).setContent(new Intent(this, ContentListActivity.class));
        tabLayout.addTab(contentTab);
        receiverTab = tabLayout.newTab().setText("receiver"); //FIXME.setIndicator(getResources().getString(R.string.title_activity_receiver_list), getResources().getDrawable(R.drawable.laptop_48_48)).setContent(new Intent(this, ReceiverListActivity.class));
        tabLayout.addTab(receiverTab);
        playerTab = tabLayout.newTab().setText("player"); //FIXME.setIndicator(getResources().getString(R.string.title_activity_player_list), getResources().getDrawable(R.drawable.player_play)).setContent(new Intent(this, PlayerListActivity.class));
        tabLayout.addTab(playerTab);
        */
        if (!checkIfAlreadyhavePermission()) {
            requestForSpecificPermission();
        }else{
            Log.d(getClass().getName(), "All permissions granted");
        }

        // local server startup
/*        upnpClient = ((Yaacc)getApplicationContext()).getUpnpClient();
        if (upnpClient == null){
           Log.d(getClass().getName(), "Upnp client is null");
          return;
        }
        // add ourself as listener
        upnpClient.addUpnpClientListener(this);

 */
        if(savedInstanceState != null){
            setCurrentTab(BrowserTabs.valueOf(savedInstanceState.getInt(CURRENT_TAB_KEY, BrowserTabs.CONTENT.ordinal())));
        }else if (upnpClient.getProviderDevice() != null) {
            setCurrentTab(BrowserTabs.CONTENT);

        }
    }

    public void setCurrentTab(final BrowserTabs content) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (content) {
                    case CONTENT: {
                        //FIXMEtabLayout.setSelectedTabIndicator(ContextCompat.getDrawable(TabBrowserActivity.this, R.drawable.device_48_48)).setContent(new Intent(this, ContentListActivity.class));
                        //FIXMEtabLayout.setCurrentTab(Tabs.CONTENT.ordinal());
                        break;
                    }
                    case SERVER: {
                        //FIXMEtabLayout.setCurrentTab(Tabs.SERVER.ordinal());
                        break;
                    }
                    case PLAYER: {
                        //FIXMEtabLayout.setCurrentTab(Tabs.PLAYER.ordinal());
                        break;
                    }
                    case RECEIVER: {
                        //FIXMEtabLayout.setCurrentTab(Tabs.RECEIVER.ordinal());
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
