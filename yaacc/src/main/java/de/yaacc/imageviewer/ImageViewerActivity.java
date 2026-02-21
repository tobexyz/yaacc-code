/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
 * Copyright (C) 2026 Modernization
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
package de.yaacc.imageviewer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.LocalImagePlayer;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerService;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.ActivitySwipeDetector;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.SwipeReceiver;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogActivity;
import de.yaacc.util.YaaccLogger;

/**
 * Modern ImageViewer with ViewPager2 slideshow.
 */
public class ImageViewerActivity extends AppCompatActivity implements SwipeReceiver, ServiceConnection {
    public static final String URIS = "URIS_PARAM";
    public static final String AUTO_START_SHOW = "AUTO_START_SHOW";

    private ImageViewerViewModel viewModel;
    private ImagePagerAdapter pagerAdapter;
    private ViewPager2 viewPager;
    private PlayerService playerService;
    private ImageViewerBroadcastReceiver broadcastReceiver;
    private Timer controlHideTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        YaaccLogger.d(this.getClass().getName(), "OnCreate");
        super.onCreate(savedInstanceState);

        setupEdgeToEdge();
        setContentView(R.layout.activity_image_viewer);

        initViewModel();
        initViews();
        initViewPager();
        loadSettingsDuration();
        loadIntentData(savedInstanceState, getIntent());
        restoreStateFromPrefs();

        // Ensure ViewPager is at correct position after all data is loaded
        ImageViewerState state = viewModel.getState().getValue();
        if (state != null && state.getCurrentIndex() >= 0) {
            viewPager.setCurrentItem(state.getCurrentIndex(), false);
        }
    }

    private void loadSettingsDuration() {
        android.content.SharedPreferences prefs = 
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        int durationMs = Integer.parseInt(prefs.getString(
            getString(R.string.image_viewer_settings_duration_key), "5000"));
        viewModel.setDurationMs(durationMs);
    }

    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Remove title from action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(ImageViewerViewModel.class);

        viewModel.getState().observe(this, state -> {
            if (state == null) return;

            // Update ViewPager adapter data (don't trigger notifyDataSetChanged)
            if (pagerAdapter != null && state.getImageUris() != null && !state.getImageUris().isEmpty()) {
                pagerAdapter.setImageUris(state.getImageUris());
                // Manually set current item after adapter data is updated
                if (state.getCurrentIndex() < state.getTotalImages()) {
                    viewPager.post(() -> viewPager.setCurrentItem(state.getCurrentIndex(), false));
                }
            }

            // Update action bar visibility (no bottom controls anymore)
            if (getSupportActionBar() != null) {
                if (state.isControlsVisible()) {
                    getSupportActionBar().show();
                } else {
                    getSupportActionBar().hide();
                }
            }

            // Handle playback state changes
            if (state.isPlaying() && !viewPager.isUserInputEnabled()) {
                viewPager.setUserInputEnabled(true);
            }
        });
    }

    private void initViews() {
        viewPager = findViewById(R.id.viewPager);
        View rootView = findViewById(R.id.rootLayout);

        // Add touch listener directly to ViewPager to toggle controls
        viewPager.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                viewModel.toggleControls();
                ImageViewerState state = viewModel.getState().getValue();
                if (state != null && state.isControlsVisible()) {
                    resetControlHideTimer();
                }
            }
            return false; // Don't consume, let ViewPager handle swipes
        });

        // Setup window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void initViewPager() {
        pagerAdapter = new ImagePagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                ImageViewerState state = viewModel.getState().getValue();
                if (state != null && position != state.getCurrentIndex()) {
                    viewModel.setCurrentIndex(position);
                    // Also update LocalImagePlayer's current index for reopening
                    if (playerService != null) {
                        Player player = playerService.getFirstCurrentPlayerOfType(LocalImagePlayer.class);
                        if (player instanceof LocalImagePlayer) {
                            ((LocalImagePlayer) player).setCurrentIndex(position);
                        }
                    }
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void loadIntentData(Bundle savedInstanceState, Intent intent) {
        if (savedInstanceState != null) {
            // Restore from saved state
            boolean wasPlaying = savedInstanceState.getBoolean("pictureShowActive", false);
            int currentIndex = savedInstanceState.getInt("currentImageIndex", 0);
            ArrayList<Uri> savedUris = savedInstanceState.getParcelableArrayList("imageUris");
            
            viewModel.setImageUris(savedUris);
            viewModel.setCurrentIndex(currentIndex);
            
            if (wasPlaying) {
                viewModel.play();
            }
        } else {
            // Load from intent
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(URIS);
            if (uris != null) {
                // Only set if we don't already have data from prefs
                ImageViewerState existingState = viewModel.getState().getValue();
                if (existingState == null || existingState.getTotalImages() == 0) {
                    viewModel.setImageUris(uris);
                }
            } else if (intent.getData() != null) {
                List<Uri> singleUri = new ArrayList<>();
                singleUri.add(intent.getData());
                viewModel.setImageUris(singleUri);
            }
            
            // Restore current index from intent if provided
            int intentIndex = intent.getIntExtra("currentIndex", -1);
            if (intentIndex >= 0) {
                viewModel.setCurrentIndex(intentIndex);
            }
            
            if (intent.getBooleanExtra(AUTO_START_SHOW, false)) {
                viewModel.play();
            }
        }
    }

    private void showNoValidUriError() {
        Toast.makeText(this, R.string.no_valid_uri_data_found_to_display, Toast.LENGTH_LONG).show();
        finish();
    }

    private void resetControlHideTimer() {
        if (controlHideTimer != null) {
            controlHideTimer.cancel();
        }
        controlHideTimer = new Timer();
        controlHideTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> viewModel.hideControls());
            }
        }, 10000);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        broadcastReceiver = new ImageViewerBroadcastReceiver(this);
        broadcastReceiver.registerReceiver();
        // Restore state when coming back from background
        restoreStateFromPrefs();
    }

    @Override
    public void finish() {
        // Clear saved state when activity is closed
        android.content.SharedPreferences prefs = getSharedPreferences("imageviewer_state", MODE_PRIVATE);
        prefs.edit().clear().apply();
        super.finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unbindService(this);
        } catch (IllegalArgumentException e) {
            YaaccLogger.d(getClass().getName(), "Ignore exception on unbind service");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadIntentData(null, intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ImageViewerState state = viewModel.getState().getValue();
        if (state != null) {
            outState.putBoolean("pictureShowActive", state.isPlaying());
            outState.putInt("currentImageIndex", state.getCurrentIndex());
            outState.putParcelableArrayList("imageUris", new ArrayList<>(state.getImageUris()));
            
            // Also save to SharedPreferences for persistence across activity restarts
            saveStateToPrefs(state);
        }
    }

    private void saveStateToPrefs(ImageViewerState state) {
        android.content.SharedPreferences prefs = getSharedPreferences("imageviewer_state", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("currentImageIndex", state.getCurrentIndex());
        editor.putBoolean("isPlaying", state.isPlaying());
        editor.putInt("imageCount", state.getTotalImages());
        // Save URIs as string set
        if (state.getImageUris() != null && !state.getImageUris().isEmpty()) {
            String[] uriStrings = new String[state.getImageUris().size()];
            for (int i = 0; i < state.getImageUris().size(); i++) {
                uriStrings[i] = state.getImageUris().get(i).toString();
            }
            editor.putStringSet("imageUris", new java.util.HashSet<>(java.util.Arrays.asList(uriStrings)));
        }
        editor.apply();
    }

    private void restoreStateFromPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences("imageviewer_state", MODE_PRIVATE);
        int index = prefs.getInt("currentImageIndex", -1);
        java.util.Set<String> uriSet = prefs.getStringSet("imageUris", null);
        
        if (uriSet != null && !uriSet.isEmpty()) {
            // Restore URIs if not already loaded from intent
            ImageViewerState state = viewModel.getState().getValue();
            if (state == null || state.getTotalImages() == 0) {
                ArrayList<Uri> uris = new ArrayList<>();
                for (String uriString : uriSet) {
                    uris.add(Uri.parse(uriString));
                }
                viewModel.setImageUris(uris);
            }
        }
        
        if (index >= 0) {
            viewModel.setCurrentIndex(index);
            viewPager.setCurrentItem(index, false);
        }
    }

    // ServiceConnection
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (binder instanceof PlayerService.PlayerServiceBinder) {
            YaaccLogger.d(getClass().getName(), "PlayerService connected");
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        YaaccLogger.d(getClass().getName(), "PlayerService disconnected");
        playerService = null;
    }

    // SwipeReceiver
    @Override
    public void onRightToLeftSwipe() {
        ImageViewerState state = viewModel.getState().getValue();
        if (state != null && state.hasMultipleImages()) {
            viewModel.next();
        }
    }

    @Override
    public void onLeftToRightSwipe() {
        ImageViewerState state = viewModel.getState().getValue();
        if (state != null && state.hasMultipleImages()) {
            viewModel.previous();
        }
    }

    @Override
    public void onTopToBottomSwipe() {}

    @Override
    public void onBottomToTopSwipe() {}

    @Override
    public void beginOnTouchProcessing(View v, MotionEvent event) {
        viewModel.showControls();
        resetControlHideTimer();
    }

    @Override
    public void endOnTouchProcessing(View v, MotionEvent event) {
        resetControlHideTimer();
    }

    // Called from ImageFragment when image is clicked
    public void toggleControlsFromFragment() {
        viewModel.toggleControls();
        ImageViewerState state = viewModel.getState().getValue();
        if (state != null && state.isControlsVisible()) {
            resetControlHideTimer();
        }
    }

    // Public methods called by BroadcastReceiver
    public void play() {
        viewModel.play();
        Toast.makeText(this, R.string.play, Toast.LENGTH_SHORT).show();
    }

    public void pause() {
        viewModel.pause();
        Toast.makeText(this, R.string.pause, Toast.LENGTH_SHORT).show();
    }

    public void stop() {
        viewModel.stop();
        Toast.makeText(this, R.string.stop, Toast.LENGTH_SHORT).show();
    }

    public void next() {
        viewModel.next();
    }

    public void previous() {
        viewModel.previous();
    }

    public void exit() {
        viewModel.stop();
        Player player = playerService != null ?
            playerService.getFirstCurrentPlayerOfType(LocalImagePlayer.class) : null;
        if (player != null) {
            player.exit();
        }
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.activity_image_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        return handleMenuItem(item.getItemId()) || super.onOptionsItemSelected(item);
    }

    // Menu handling
    public boolean handleMenuItem(int itemId) {
        if (itemId == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (itemId == R.id.menu_next) {
            next();
            return true;
        }
        if (itemId == R.id.menu_pause) {
            pause();
            return true;
        }
        if (itemId == R.id.menu_play) {
            play();
            return true;
        }
        if (itemId == R.id.menu_previous) {
            previous();
            return true;
        }
        if (itemId == R.id.menu_stop) {
            stop();
            return true;
        }
        if (itemId == R.id.yaacc_log) {
            YaaccLogActivity.showLog(this);
            return true;
        }
        if (itemId == R.id.yaacc_about) {
            AboutActivity.showAbout(this);
            return true;
        }
        if (itemId == R.id.menu_exit) {
            exit();
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        exit();
    }
}
