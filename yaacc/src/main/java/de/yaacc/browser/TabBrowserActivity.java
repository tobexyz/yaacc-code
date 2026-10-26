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

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import de.yaacc.util.YaaccLogger;
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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;

import java.util.Collection;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.share.PickedAction;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.LocalServerLifecycle;
import de.yaacc.util.ThemeHelper;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schoene (the openbit)
 */
public class TabBrowserActivity extends AppCompatActivity implements OnClickListener, BrowserHost {

    private static final String CURRENT_TAB_KEY = "currentTab";
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private FragmentStateAdapter pagerAdapter;


    private UpnpClient upnpClient = null;
    private ShareIntentHandler shareIntentHandler = null;
    // Opens the receiver picker for a shared file only; normal classic browsing never shows the sheet.
    private ReceiverPickController pickController;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_TAB_KEY, tabLayout.getSelectedTabPosition());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        long start = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tab_browse);
        pickController = new ReceiverPickController(this);
        viewPager = findViewById(R.id.browserTabPager);
        tabLayout = findViewById(R.id.browserTabLayout);
        pagerAdapter = new TabBrowserFragmentStateAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        ((RecyclerView) viewPager.getChildAt(0)).setFocusable(false);
        //disable swipe if set in preferences
        viewPager.setUserInputEnabled(getPreferences().getBoolean(getString(R.string.settings_swipe_chkbx), true));
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
                if (tabLayout.getTabAt(position) != null) {
                    tabLayout.getTabAt(position).select();
                }
            }
        });
        BrowserActivitySupport.requestMissingPermissions(this);

        BrowserActivitySupport.checkBatteryOptimizationEnabled(this);

        // local server startup
        upnpClient = ((Yaacc) getApplicationContext()).getUpnpClient();
        if (upnpClient == null) {
            YaaccLogger.d(getClass().getName(), "Upnp client is null");
            return;
        }

        if (savedInstanceState != null) {
            setCurrentTab(BrowserTabs.valueOf(savedInstanceState.getInt(CURRENT_TAB_KEY, BrowserTabs.CONTENT.ordinal())));
        } else if (upnpClient.getProviderDevice() != null) {
            setCurrentTab(BrowserTabs.CONTENT);

        }

        shareIntentHandler = new ShareIntentHandler(getApplicationContext(), upnpClient);
        handOverShare(getIntent());
        YaaccLogger.d(this.getClass().getName(), "on create took: " + (System.currentTimeMillis() - start));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handOverShare(intent);
    }

    /**
     * Hands a shared item to the handler only while the classic layout is the effective one. This
     * activity is exported, so an app can send it a share even when the user chose the new layout;
     * the handler would then play a link on this device without the receiver picker. Otherwise the
     * payload is consumed (the relaunch in {@code onResume} uses a fresh intent).
     */
    private void handOverShare(Intent intent) {
        if (shareIntentHandler == null) {
            return;
        }
        if (ShareIntakeGate.admitOrDiscard(BrowserLayoutChoice.Layout.CLASSIC,
                BrowserLauncher.effectiveLayout(this), intent)) {
            shareIntentHandler.handle(intent, this::showPlayers, this);
        }
    }

    @Override
    protected void onDestroy() {
        // an open pick is cancelled first: that releases a pending share
        if (pickController != null) {
            pickController.release();
            pickController = null;
        }
        if (shareIntentHandler != null) {
            shareIntentHandler.shutdown();
            shareIntentHandler = null;
        }
        super.onDestroy();
    }

    @Override
    public void showServers() {
        setCurrentTab(BrowserTabs.SERVER);
    }

    @Override
    public void showContent() {
        setCurrentTab(BrowserTabs.CONTENT);
    }

    @Override
    public void showReceivers() {
        setCurrentTab(BrowserTabs.RECEIVER);
    }

    @Override
    public void showPlayers() {
        setCurrentTab(BrowserTabs.PLAYER);
    }

    @Override
    public boolean pickReceivers(PickedAction action, String shareName, Collection<String> initialIds) {
        return pickController != null && pickController.start(action, shareName, initialIds);
    }

    @Override
    public boolean isPickOpen(String pickId) {
        return pickController != null && pickController.isOpen(pickId);
    }

    /**
     * A shared file is waiting: the same "Play on..." sheet as in the new layout, showing the cleaned
     * file name, with the classic receiver selection as the initial ticks. The selection is put
     * back once the playback has started ({@link de.yaacc.share.AfterStartSelection}).
     */
    @Override
    public void onPendingShare(String token, String displayName) {
        ShareIntentHandler handler = shareIntentHandler;
        if (handler == null) {
            return;
        }
        if (pickController == null || upnpClient == null) {
            handler.cancel(token);
            return;
        }
        pickController.offerShare(handler, token, displayName, upnpClient, false, upnpClient.getReceiverDeviceIds());
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
        // starts the browser of the effective layout and finishes this one if the "Layout" setting
        // no longer names the classic layout; the share payload was already handed to the share
        // handler in onCreate and is not forwarded again
        if (BrowserLauncher.relaunchIfOtherLayout(this, BrowserLayoutChoice.Layout.CLASSIC)) {
            return;
        }
        viewPager.setUserInputEnabled(getPreferences().getBoolean(getString(R.string.settings_swipe_chkbx), true));
        LocalServerLifecycle.apply(this, SettingsActivity.consumeLeftSettings());
        YaaccLogger.d(this.getClass().getName(), "on on resume took: " + (System.currentTimeMillis() - start));
    }

    @Override
    protected void onStart() {
        super.onStart();
        BackgroundIdleTimer.browserStarted(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        BackgroundIdleTimer.browserStopped(this);
    }

    @Override
    public void onBackPressed() {
        YaaccLogger.d(TabBrowserActivity.class.getName(), "onBackPressed() ");
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
        if (BrowserActivitySupport.handleCommonMenuItem(this, item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onClick(View view) {

    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Let system handle volume keys - shows standard Android volume UI
        return super.onKeyDown(keyCode, event);
    }
}
