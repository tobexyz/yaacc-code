/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.browser;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import java.util.Collection;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.browser.BrowserLayoutChoice.Layout;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.share.PendingPick;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.LocalServerLifecycle;
import de.yaacc.util.FormatHelper;
import de.yaacc.util.YaaccLogger;

/**
 * The new browser layout: no tabs. The top level is the server list; tapping a server shows its
 * content on top of it. The default action bar is the toolbar (settings, exit and the shutdown
 * timer). The receiver bar and the mini-player sit below the fragment container, so they show at
 * every level: the mini-player list ({@link MiniPlayerBar}, one row per running player) directly above
 * the receiver bar
 * ({@link ReceiverBarController}). Both follow their events only while the activity is started.
 * <p>
 * Not exported and without intent filters: {@link de.yaacc.LauncherActivity} starts it with a fresh
 * intent through {@link BrowserLauncher}. The only part of {@code getIntent()} that is read is the
 * forwarded share text, and only by {@link ShareIntentHandler}.
 */
public class BrowserActivity extends AppCompatActivity implements BrowserHost {

    private static final String TAG = BrowserActivity.class.getName();
    private static final String TAG_SERVERS = "browser_servers";
    private static final String TAG_CONTENT = "browser_content";
    private static final String BACK_STACK_CONTENT = "browser_content";

    private ShareIntentHandler shareIntentHandler = null;
    private ShutdownTimerController shutdownTimerController;
    // the receiver picker ("Play on..."): play, play-all, add-to-playlist and a pending share open it.
    // Its pending action lives here and is lost with the activity (accepted).
    private ReceiverPickController pickController;
    private UpnpClient upnpClient;
    // created once the UpnpClient exists; bound between onStart and onStop
    private ReceiverBarController receiverBarController;
    private MiniPlayerBar miniPlayerBar;

    // last timer state, applied to the menu item whenever it exists (it is created lazily)
    private MenuItem shutdownTimerItem;
    private long shutdownTimerMillis;
    private boolean shutdownTimerRunning;

    private final OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            onBack();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The layout check comes first, before anything reads the intent. The launcher starts this
        // activity only for the effective NEW layout, so this fires only when the setting changed
        // in between or a stale explicit launch arrived (e.g. an old notification). The forwarded
        // share text is then dropped on purpose: the intent is not copied to the other browser,
        // and a share is never replayed.
        if (BrowserLauncher.relaunchIfOtherLayout(this, Layout.NEW)) {
            return;
        }
        setContentView(R.layout.activity_browser);
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
        pickController = new ReceiverPickController(this);

        BrowserActivitySupport.requestMissingPermissions(this);
        BrowserActivitySupport.checkBatteryOptimizationEnabled(this);

        shutdownTimerController = new ShutdownTimerController((Yaacc) getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext()),
                getString(R.string.settings_shutdown_timer));

        upnpClient = ((Yaacc) getApplicationContext()).getUpnpClient();
        if (upnpClient == null) {
            YaaccLogger.d(TAG, "Upnp client is null");
            return;
        }
        if (savedInstanceState == null) {
            // after a recreation the fragment manager restores the server list and, if shown, the content
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.browserContainer, new ServerListFragment(), TAG_SERVERS)
                    .commit();
        }
        receiverBarController = new ReceiverBarController(findViewById(R.id.browserReceiverBar),
                upnpClient, this::showReceivers);
        miniPlayerBar = new MiniPlayerBar(findViewById(R.id.miniPlayerBar), upnpClient);
        shareIntentHandler = new ShareIntentHandler(getApplicationContext(), upnpClient);
        shareIntentHandler.handle(getIntent(), null, this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (shareIntentHandler != null) {
            shareIntentHandler.handle(intent, null, this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        BackgroundIdleTimer.browserStarted(this);
        if (shutdownTimerController != null) {
            shutdownTimerController.attach(this::showShutdownTimer);
        }
        // both follow the client's events only while this activity is started
        if (receiverBarController != null) {
            receiverBarController.bind();
        }
        if (miniPlayerBar != null) {
            miniPlayerBar.bind();
        }
    }

    @Override
    protected void onStop() {
        // the display is this activity: the application must not keep it while it is stopped
        if (miniPlayerBar != null) {
            miniPlayerBar.unbind();
        }
        if (receiverBarController != null) {
            receiverBarController.unbind();
        }
        if (shutdownTimerController != null) {
            shutdownTimerController.detach();
        }
        super.onStop();
        BackgroundIdleTimer.browserStopped(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (BrowserLauncher.relaunchIfOtherLayout(this, Layout.NEW)) {
            return;
        }
        LocalServerLifecycle.apply(this, SettingsActivity.consumeLeftSettings());
    }

    @Override
    protected void onDestroy() {
        // an open pick is cancelled first: that releases a pending share
        if (pickController != null) {
            pickController.release();
            pickController = null;
        }
        // backstop: onStop already unbound them
        if (miniPlayerBar != null) {
            miniPlayerBar.unbind();
            miniPlayerBar = null;
        }
        if (receiverBarController != null) {
            receiverBarController.unbind();
            receiverBarController = null;
        }
        if (shareIntentHandler != null) {
            shareIntentHandler.shutdown();
            shareIntentHandler = null;
        }
        shutdownTimerItem = null;
        super.onDestroy();
    }

    // ---- BrowserHost -------------------------------------------------------------------------

    @Override
    public void showServers() {
        runOnUiThread(() -> {
            FragmentManager fragmentManager = getSupportFragmentManager();
            if (fragmentManager.isStateSaved()) {
                YaaccLogger.d(TAG, "showServers ignored: state already saved");
                return;
            }
            // no-op if the server list is already on top: no entry of that name exists
            fragmentManager.popBackStack(BACK_STACK_CONTENT, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        });
    }

    @Override
    public void showContent() {
        runOnUiThread(() -> {
            FragmentManager fragmentManager = getSupportFragmentManager();
            if (isFinishing() || fragmentManager.isStateSaved()) {
                YaaccLogger.d(TAG, "showContent ignored: finishing or state already saved");
                return;
            }
            try {
                // a quick second tap must see the first one's transaction
                fragmentManager.executePendingTransactions();
            } catch (IllegalStateException e) {
                YaaccLogger.w(TAG, "Could not execute pending fragment transactions", e);
            }
            if (fragmentManager.findFragmentById(R.id.browserContainer) instanceof ContentListFragment) {
                return;
            }
            // the content loads the provider stored by UpnpClient#setProviderDevice itself
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.browserContainer, new ContentListFragment(), TAG_CONTENT)
                    .addToBackStack(BACK_STACK_CONTENT)
                    .commit();
        });
    }

    @Override
    public void showReceivers() {
        // the normal sheet: receivers with their controls and the running players, no selection
        runOnUiThread(() -> DeviceSheetFragment.show(getSupportFragmentManager()));
    }

    @Override
    public boolean pickReceivers(PendingPick.Callbacks callbacks, String shareName, Collection<String> initialIds) {
        return pickController != null && pickController.start(callbacks, shareName, initialIds);
    }

    @Override
    public boolean isPickOpen(String pickId) {
        return pickController != null && pickController.isOpen(pickId);
    }

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
        // nothing is ticked: the selection is per action; a confirm clears it again afterwards
        pickController.offerShare(handler, token, displayName, upnpClient, true, null);
    }

    @Override
    public void showPlayers() {
        // the mini-player follows the running players by itself (player list events and a poll)
    }

    @Override
    public boolean providesShutdownTimer() {
        return true;
    }

    @Override
    public boolean providesReceiverBar() {
        return true;
    }

    @Override
    public boolean showsBackAtContentRoot() {
        return true;
    }

    // ---- back --------------------------------------------------------------------------------

    private void onBack() {
        Fragment top = getSupportFragmentManager().findFragmentById(R.id.browserContainer);
        ContentListFragment content = top instanceof ContentListFragment ? (ContentListFragment) top : null;
        switch (BrowserBackNavigation.decide(content == null, content != null && content.isAtRoot())) {
            case POP_FOLDER:
                if (content != null) {
                    content.onBackPressed();
                }
                break;
            case SHOW_SERVERS:
                showServers();
                break;
            case EXIT:
            default:
                // as ServerListFragment#onBackPressed does in the classic layout
                ((Yaacc) getApplicationContext()).exit();
                finish();
                break;
        }
    }

    // ---- toolbar -----------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_browser, menu);
        shutdownTimerItem = menu.findItem(R.id.menu_shutdown_timer);
        applyShutdownTimerState();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_shutdown_timer) {
            toggleShutdownTimer();
            return true;
        }
        if (BrowserActivitySupport.handleCommonMenuItem(this, item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleShutdownTimer() {
        if (shutdownTimerController == null) {
            return;
        }
        if (ShutdownTimerToggle.decide(shutdownTimerController.isRunning()) == ShutdownTimerToggle.Action.STOP) {
            shutdownTimerController.stop();
        } else {
            showShutdownTimerPicker();
        }
    }

    private void showShutdownTimerPicker() {
        if (shutdownTimerController != null && !getSupportFragmentManager().isStateSaved()) {
            // confirming the picker starts the timer; there is no second entry for it
            shutdownTimerController.showPicker(getSupportFragmentManager(), getString(R.string.shutdown_timer), true);
        }
    }

    /** {@link ShutdownTimerController.Display}: called on the main thread. */
    private void showShutdownTimer(long millis, boolean running) {
        shutdownTimerMillis = millis;
        shutdownTimerRunning = running;
        applyShutdownTimerState();
    }

    /**
     * While a timer runs the item is checked, always shown and carries the remaining time as its
     * title; otherwise it is the plain timer icon. The menu item is updated in place, not through
     * {@code invalidateOptionsMenu()}, because the timer ticks every second.
     */
    private void applyShutdownTimerState() {
        MenuItem item = shutdownTimerItem;
        if (item == null) {
            return;
        }
        String name = getString(R.string.shutdown_timer);
        String remaining = FormatHelper.parseMillisToTimeStringTo(shutdownTimerMillis);
        boolean changed = item.isChecked() != shutdownTimerRunning;
        item.setChecked(shutdownTimerRunning);
        item.setTitle(shutdownTimerRunning ? remaining : name);
        item.setContentDescription(shutdownTimerRunning ? name + ": " + remaining : name);
        item.setTooltipText(shutdownTimerRunning ? name + ": " + remaining : name);
        if (changed) {
            item.setShowAsAction(shutdownTimerRunning
                    ? MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT
                    : MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    }
}
