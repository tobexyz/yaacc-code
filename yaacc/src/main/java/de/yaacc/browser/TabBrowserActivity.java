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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogActivity;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schoene (the openbit)
 */
public class TabBrowserActivity extends AppCompatActivity implements OnClickListener {
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
    private Toast volumeToast = null;

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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (savedInstanceState != null) {
            setCurrentTab(BrowserTabs.valueOf(savedInstanceState.getInt(CURRENT_TAB_KEY, BrowserTabs.CONTENT.ordinal())));
        } else if (upnpClient.getProviderDevice() != null) {
            setCurrentTab(BrowserTabs.CONTENT);

        }

        checkIfReceivedShareIntent(null);
        Log.d(this.getClass().getName(), "on create took: " + (System.currentTimeMillis() - start));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkIfReceivedShareIntent(intent);
    }

    private void checkIfReceivedShareIntent(Intent receivedIntent) {
        // Get intent, action and MIME type
        Intent intent = receivedIntent != null ? receivedIntent : getIntent();
        String action = intent.getAction();
        Uri contentUri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            ArrayList<PlayableItem> items = new ArrayList<>();
            if (intent.getExtras() != null && intent.getExtras().getString("android.intent.extra.TEXT") != null) {
                contentUri = Uri.parse(intent.getExtras().getString("android.intent.extra.TEXT"));
            } else if (intent.getClipData() != null) {
                for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                    if (intent.getClipData().getItemAt(i) != null) {
                        if (intent.getClipData().getItemAt(i).getText() != null) {
                            contentUri = Uri.parse(intent.getClipData().getItemAt(i).getText().toString());
                        } else if (intent.getClipData().getItemAt(i).getUri() != null) {
                            contentUri = intent.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
            }
            //clear intent
            if (intent.getExtras() != null && intent.getExtras().getString("android.intent.extra.TEXT") != null) {
                intent.removeExtra("android.intent.extra.TEXT");
            }
            if (intent.getClipData() != null) {
                intent.setClipData(null);
            }
            if (contentUri != null) {
                long delayedExecution = 0;
                if (upnpClient.getReceiverDevicesReadyCount() == 0) {
                    //schedule execution to be sure the receiver devices come up
                    delayedExecution = 3000L;
                }
                if (!upnpClient.isPlayerServiceInitialized()) {
                    //schedule execution to be sure the player devices come up
                    upnpClient.startService();
                    delayedExecution += 3000L;
                }


                ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
                Handler handler = new Handler(Looper.getMainLooper());
                final Uri uri = contentUri;
                Runnable execution = new Runnable() {
                    @Override
                    public void run() {
                        if (upnpClient.getReceiverDevicesReadyCount() == 0) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(getApplicationContext(), "no receiver found using local device", Toast.LENGTH_LONG).show();
                                }
                            });

                            upnpClient.setReceiverDeviceIds(Set.of(UpnpClient.LOCAL_UID));
                        }
                        items.add(upnpClient.createPlayableItem(uri));

                        handler.post(new Runnable() {
                            @Override
                            public void run() {

                                List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
                                for (Player player : players) {
                                    player.play();
                                }
                                setCurrentTab(BrowserTabs.PLAYER);
                            }
                        });
                    }
                };

                if (delayedExecution > 0) {
                    executor.schedule(execution, delayedExecution, TimeUnit.MILLISECONDS);
                    Toast.makeText(getApplicationContext(), String.format("start in %s seconds", delayedExecution / 1000), Toast.LENGTH_LONG).show();
                } else {
                    executor.execute(execution);
                }
            }

        }
    }


    public void setCurrentTab(final BrowserTabs content) {
        runOnUiThread(() -> {
            if (tabLayout.getTabAt(content.ordinal()) != null) {
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
        super.onResume();
        setVolumeControlStream(-1000); //use an invalid audio stream to block controlling default streams
        boolean serverOn = getPreferences().getBoolean(
                getString(R.string.settings_local_server_chkbx), false);
        if (serverOn) {
            // (Re)Start upnpserver service for avtransport
            if (leftSettings) {
                getApplicationContext().stopService(getYaaccUpnpServerService());
            }
            getApplicationContext().startForegroundService(getYaaccUpnpServerService());
            Log.d(this.getClass().getName(), "Starting local service");
        } else {
            getApplicationContext().stopService(getYaaccUpnpServerService());
            Log.d(this.getClass().getName(), "Stopping local service");
        }
        leftSettings = false;
        Log.d(this.getClass().getName(), "on on resume took: " + (System.currentTimeMillis() - start));
    }


    /**
     * Singleton to avoid multiple instances when switch
     *
     * @return the intent
     */
    public Intent getYaaccUpnpServerService() {
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
    public void onClick(View view) {

    }

    private Toast createVolumeToast(Drawable icon) {
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.custom_toast, (ViewGroup) findViewById(R.id.toast_custom));
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        layout.setBackgroundColor(typedValue.data);
        ImageView imageView = (ImageView) layout.findViewById(R.id.customToastImageView);
        imageView.setImageDrawable(icon);
        TextView text = (TextView) layout.findViewById(R.id.customToastTextView);
        text.setText("");
        Toast toast = new Toast(getApplicationContext());
        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setView(layout);
        return toast;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        upnpClient.getReceiverDevices().forEach(d -> {
            if (upnpClient.hasActionGetVolume(d))
                switch (keyCode) {
                    case KeyEvent.KEYCODE_VOLUME_UP:
                        if (upnpClient.getVolume(d) < 100) {
                            upnpClient.setVolume(d, upnpClient.getVolume(d) + 1);
                        }
                        break;
                    case KeyEvent.KEYCODE_VOLUME_DOWN:
                        if (upnpClient.getVolume(d) > 0) {
                            upnpClient.setVolume(d, upnpClient.getVolume(d) - 1);
                        }
                        break;
                }
        });
        if (!upnpClient.getReceiverDevices().isEmpty()) {
            if (KeyEvent.KEYCODE_VOLUME_UP == keyCode || KeyEvent.KEYCODE_VOLUME_DOWN == keyCode) {
                Drawable icon = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_volume_up_96, getTheme()), getTheme()) : ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_volume_down_96, getTheme()), getTheme());
                if (volumeToast != null) {
                    volumeToast.cancel();
                }
                volumeToast = createVolumeToast(icon);
                volumeToast.show();
            }
            if (viewPager != null && tabLayout != null && tabLayout.getSelectedTabPosition() == BrowserTabs.RECEIVER.ordinal() && tabLayout.getTabAt(tabLayout.getSelectedTabPosition()).view != null) {
                List<Fragment> fragments = getSupportFragmentManager().getFragments();
                if (fragments.size() > viewPager.getCurrentItem()) {
                    RecyclerView view = ((RecyclerView) fragments.get(viewPager.getCurrentItem()).getView().findViewById(R.id.receiverList));
                    if (view != null && view.getAdapter() != null) {
                        view.getAdapter().notifyDataSetChanged();
                    }
                }
            }
        }


        return super.onKeyDown(keyCode, event);
    }


}
