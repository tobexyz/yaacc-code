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
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
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
    private static final String[] permissions = new String[]{
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
    private static final String CURRENT_TAB_KEY = "currentTab";
    //FIXME dirty
    public static boolean leftSettings = false;
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private FragmentStateAdapter pagerAdapter;


    private UpnpClient upnpClient = null;


    private Intent serverService = null;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
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
                Objects.requireNonNull(tabLayout.getTabAt(position)).select();
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

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && intent.getClipData() != null) {
            ArrayList<PlayableItem> items = new ArrayList<>();
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                if (intent.getClipData().getItemAt(i) != null) {
                    Uri contentUri = Uri.parse(intent.getClipData().getItemAt(i).getText().toString());
                    items.add(creatPlayableItem(contentUri));
                }
            }
            List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
            for (Player player : players) {
                player.play();
            }
            intent.setClipData(null);
        }
        Log.d(this.getClass().getName(), "on create took: " + (System.currentTimeMillis() - start));
    }

    private PlayableItem creatPlayableItem(Uri uri) {
        PlayableItem item = new PlayableItem();
        if (uri == null) {
            return item;
        }
        String uriString = uri.toString();
        final String title = "shared with yaacc with ♥";
        try (MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever()) {

            try {
                metaRetriever.setDataSource(uriString);
                String duration = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                item.setDuration(Long.parseLong(duration));
                item.setMimeType(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
                Res res = new Res(MimeType.valueOf(item.getMimeType()), 1L, duration);
                res.setValue(uriString);
                if (item.getMimeType().startsWith("audio/")) {
                    item.setItem(new MusicTrack("1", "2", title, "", "", "", res));
                } else if (item.getMimeType().startsWith("video/")) {
                    item.setItem(new VideoItem("1", "2", title, "", res));
                } else if (item.getMimeType().startsWith("image/")) {
                    item.setItem(new ImageItem("1", "2", title, "", res));
                }
            } catch (RuntimeException e) {
                //no media file with duration
                Log.d(getClass().getName(), "Can't retrieve duration of media url assume shared image", e);
                Res res = new Res(MimeType.valueOf("image/*"), 1L, "");
                res.setValue(uriString);
                item.setMimeType("image/*");
                item.setItem(new ImageItem("1", "2", title, "", res));
            }
            item.setUri(uri);
            item.setTitle(title);
        }
        return item;
    }

    public void setCurrentTab(final BrowserTabs content) {
        runOnUiThread(() -> Objects.requireNonNull(tabLayout.getTabAt(content.ordinal())).select());

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
     * @return the intent
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
        if (getSupportFragmentManager().getFragments().size() > tabLayout.getSelectedTabPosition()) {
            Fragment fragment = getSupportFragmentManager().getFragments().get(tabLayout.getSelectedTabPosition());
            if (!(fragment instanceof OnBackPressedListener) || !((OnBackPressedListener) fragment).onBackPressed()) {
                super.onBackPressed();
            }
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
        if (item.getItemId() == R.id.menu_exit) {

            ((Yaacc) getApplicationContext()).exit();
            return true;
        }
        if (item.getItemId() == R.id.menu_settings) {
            Intent i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_about) {
            AboutActivity.showAbout(this);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_log) {
            YaaccLogActivity.showLog(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
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
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public void onClick(View view) {

    }
}
