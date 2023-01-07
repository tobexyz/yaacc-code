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

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

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
public class TabBrowserActivity extends AppCompatActivity implements OnClickListener,
        UpnpClientListener {
    //FIXME dirty
    public static boolean leftSettings = false;
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
    private static String CURRENT_TAB_KEY = "currentTab";
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private FragmentStateAdapter pagerAdapter;


    private UpnpClient upnpClient = null;


    private Intent serverService = null;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_TAB_KEY, tabLayout.getSelectedTabPosition());
    }

    private boolean checkIfAlreadyhavePermission() {
        for (String permission : permissions) {
            int permissionState = ContextCompat.checkSelfPermission(this, permission);
            if (permissionState == PackageManager.PERMISSION_DENIED) {
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
        long start = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tab_browse);


        viewPager = findViewById(R.id.browserTabPager);
        tabLayout = findViewById(R.id.browserTabLayout);
        pagerAdapter = new TabBrowserFragmentStateAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                tabLayout.getTabAt(position).select();
            }
        });
        if (!checkIfAlreadyhavePermission()) {
            requestForSpecificPermission();
        } else {
            Log.d(getClass().getName(), "All permissions granted");
        }

        // local server startup
        upnpClient = ((Yaacc) getApplicationContext()).getUpnpClient();
        if (upnpClient == null) {
            Log.d(getClass().getName(), "Upnp client is null");
            return;
        }
        // add ourself as listener
        upnpClient.addUpnpClientListener(this);


        if (savedInstanceState != null) {
            setCurrentTab(BrowserTabs.valueOf(savedInstanceState.getInt(CURRENT_TAB_KEY, BrowserTabs.CONTENT.ordinal())));
        } else if (upnpClient.getProviderDevice() != null) {
            setCurrentTab(BrowserTabs.CONTENT);

        }
        Log.d(this.getClass().getName(), "on create took: " + (System.currentTimeMillis() - start));
    }

    public void setCurrentTab(final BrowserTabs content) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tabLayout.getTabAt(content.ordinal()).select();
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
        long start = System.currentTimeMillis();
        boolean serverOn = getPreferences().getBoolean(
                getString(R.string.settings_local_server_chkbx), false);
        if (serverOn) {
            // (Re)Start upnpserver service for avtransport
            if (leftSettings) {
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
        Log.d(this.getClass().getName(), "on on resume took: " + (System.currentTimeMillis() - start));
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
        Fragment fragment = getSupportFragmentManager().getFragments().get(tabLayout.getSelectedTabPosition());
        if (!(fragment instanceof OnBackPressedListener) || !((OnBackPressedListener) fragment).onBackPressed()) {
            super.onBackPressed();
        }

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
                ((Yaacc) getApplicationContext()).exit();
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
