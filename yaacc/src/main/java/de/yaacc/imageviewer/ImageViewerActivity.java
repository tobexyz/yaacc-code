/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

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
import de.yaacc.util.AboutActivity;
import de.yaacc.util.ActivitySwipeDetector;
import de.yaacc.util.SwipeReceiver;
import de.yaacc.util.YaaccLogActivity;

/**
 * a simple ImageViewer based on the android ImageView component;
 * <p>
 * you are able to start the activity either by using intnet.setData(anUri) or
 * by intent.putExtra(ImageViewerActivity.URIS, aList<Uri>); in the later case
 * the activity needed to be started with Intent.ACTION_SEND_MULTIPLE
 * <p>
 * <p>
 * The image viewer retrieves all images in a background task
 * (RetrieveImageTask). The images are written in a memory cache. The picture
 * show is processed by the ImageViewerActivity using the images in the cache.
 *
 * @author Tobias Schoene (openbit)
 */
public class ImageViewerActivity extends AppCompatActivity implements SwipeReceiver, ServiceConnection {
    public static final String URIS = "URIS_PARAM";
    public static final String AUTO_START_SHOW = "AUTO_START_SHOW";
    private ImageView imageView;
    private RetrieveImageTask retrieveImageTask;
    private List<Uri> imageUris; // playlist
    private int currentImageIndex = 0;
    private boolean pictureShowActive = false;
    private boolean isProcessingCommand = false; // indicates an command
    private Timer pictureShowTimer;
    private ImageViewerBroadcastReceiver imageViewerBroadcastReceiver;
    private PlayerService playerService;

    public void onServiceConnected(ComponentName className, IBinder binder) {
        if (binder instanceof PlayerService.PlayerServiceBinder) {
            Log.d(getClass().getName(), "PlayerService connected");
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            initialize();
        }
    }

    public void onServiceDisconnected(ComponentName className) {
        Log.d(getClass().getName(), "PlayerService disconnected");
        playerService = null;
    }

    protected void initialize() {

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(this.getClass().getName(), "OnCreate");
        super.onCreate(savedInstanceState);
        init(savedInstanceState, getIntent());
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onNewIntent(android.content.Intent)
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        init(null, intent);
    }

    private void init(Bundle savedInstanceState, Intent intent) {
        menuBarsHide();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
        setContentView(R.layout.activity_image_viewer);
        imageView = findViewById(R.id.imageView);
        ActivitySwipeDetector activitySwipeDetector = new ActivitySwipeDetector(
                this);
        RelativeLayout layout = findViewById(R.id.layout);
        layout.setOnTouchListener(activitySwipeDetector);
        currentImageIndex = 0;
        imageUris = new ArrayList<>();
        if (savedInstanceState != null) {
            pictureShowActive = savedInstanceState
                    .getBoolean("pictureShowActive");
            currentImageIndex = savedInstanceState.getInt("currentImageIndex");
            imageUris = (List<Uri>) savedInstanceState
                    .getSerializable("imageUris");
        } else {
            Log.d(this.getClass().getName(),
                    "Received Action View! now setting items ");
            Serializable urisData = intent.getSerializableExtra(URIS);
            if (urisData != null) {
                if (urisData instanceof List) {
                    currentImageIndex = 0;
                    imageUris = (List<Uri>) urisData;
                    Log.d(this.getClass().getName(),
                            "imageUris" + imageUris.toString());
                }
            } else {
                if (intent.getData() != null) {
                    currentImageIndex = 0;
                    imageUris.add(intent.getData());
                    Log.d(this.getClass().getName(), "imageUris.add(i.getData)"
                            + imageUris.toString());
                }
            }
            pictureShowActive = intent.getBooleanExtra(AUTO_START_SHOW, false);
        }
        if (imageUris.size() > 0) {
            loadImage();
        } else {
            runOnUiThread(() -> {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        R.string.no_valid_uri_data_found_to_display,
                        Toast.LENGTH_LONG);
                toast.show();
                menuBarsHide();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unbindService(this);
        } catch (IllegalArgumentException iae) {
            Log.d(getClass().getName(), "Ignore exception on unbind service while activity destroy");
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onResume()
     */
    @Override
    protected void onResume() {

        imageViewerBroadcastReceiver = new ImageViewerBroadcastReceiver(this);
        imageViewerBroadcastReceiver.registerReceiver();
        super.onResume();
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onPause()
     */
    @Override
    protected void onPause() {
        cancleTimer();
        if (retrieveImageTask != null) {
            retrieveImageTask.cancel(true);
            retrieveImageTask = null;
        }
        unregisterReceiver(imageViewerBroadcastReceiver);
        imageViewerBroadcastReceiver = null;
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_image_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;
        if (item.getItemId() == R.id.menu_settings) {

            i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        }
        if (item.getItemId() == R.id.menu_next) {
            next();
            return true;
        }
        if (item.getItemId() == R.id.menu_pause) {
            pause();
            return true;
        }
        if (item.getItemId() == R.id.menu_play) {
            play();
            return true;
        }
        if (item.getItemId() == R.id.menu_previous) {
            previous();
            return true;
        }
        if (item.getItemId() == R.id.menu_stop) {
            stop();
            return true;
        }
        if (item.getItemId() == R.id.yaacc_log) {
            YaaccLogActivity.showLog(this);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_about) {
            AboutActivity.showAbout(this);
            return true;
        }
        if (item.getItemId() == R.id.menu_exit) {
            exit();
            return true;
        }
        return super.onOptionsItemSelected(item);

    }

    private void exit() {
        Player player = playerService.getFirstCurrentPlayerOfType(LocalImagePlayer.class);
        if (player != null) {

            player.exit();
        }
        finish();
    }

    /**
     * In case of device rotation the activity will be restarted. In this case
     * the original intent which where used to start the activity won't change.
     * So we only need to store the state of the activity.
     */
    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean("pictureShowActive", pictureShowActive);
        savedInstanceState.putInt("currentImageIndex", currentImageIndex);
        if (!(imageUris instanceof ArrayList)) {
            imageUris = new ArrayList<>(imageUris);
        }
        savedInstanceState.putSerializable("imageUris", (ArrayList<Uri>) imageUris);
    }

    /**
     * Create and start a timer for the next picture change. The timer runs only
     * once.
     */
    public void startTimer() {
        pictureShowTimer = new Timer();
        pictureShowTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(getClass().getName(), "TimerEvent" + this);
                ImageViewerActivity.this.next();
            }
        }, getDuration());
    }

    /**
     * Start playing the picture show.
     */
    public void play() {
        if (isProcessingCommand)
            return;
        isProcessingCommand = true;
        if (currentImageIndex < imageUris.size()) {
            runOnUiThread(() -> {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.play)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            });
// Start the pictureShow
            pictureShowActive = true;
            loadImage();
            isProcessingCommand = false;
        }
    }

    /**
     *
     */
    private void loadImage() {
        if (retrieveImageTask != null
                && retrieveImageTask.getStatus() == Status.RUNNING) {
            return;
        }
        retrieveImageTask = new RetrieveImageTask(this);
        Log.d(getClass().getName(),
                "showImage(" + imageUris.get(currentImageIndex) + ")");
        retrieveImageTask.executeOnExecutor(((Yaacc) getApplicationContext()).getContentLoadExecutor(), imageUris.get(currentImageIndex));
    }

    /**
     * Stop picture show timer and reset the current playlist index. Display
     * default image;
     */
    public void stop() {
        if (isProcessingCommand)
            return;
        isProcessingCommand = true;
        cancleTimer();
        currentImageIndex = 0;
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(ImageViewerActivity.this,
                    getResources().getString(R.string.stop)
                            + getPositionString(), Toast.LENGTH_SHORT);
            toast.show();
        });
        showDefaultImage();
        pictureShowActive = false;
        isProcessingCommand = false;
    }

    /**
     *
     */
    private void cancleTimer() {
        if (pictureShowTimer != null) {
            pictureShowTimer.cancel();
        }
    }

    /**
     *
     */
    private void showDefaultImage() {
        imageView.setImageDrawable(ResourcesCompat.getDrawable(getResources(),
                R.drawable.yaacc192_32, getTheme()));
    }

    /**
     * Stop the timer.
     */
    public void pause() {
        if (isProcessingCommand)
            return;
        isProcessingCommand = true;
        cancleTimer();
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(ImageViewerActivity.this,
                    getResources().getString(R.string.pause)
                            + getPositionString(), Toast.LENGTH_SHORT);
            toast.show();
        });
        pictureShowActive = false;
        isProcessingCommand = false;
    }

    /**
     * show the previous image
     */
    public void previous() {
        if (isProcessingCommand)
            return;
        isProcessingCommand = true;
        cancleTimer();
        currentImageIndex--;
        if (currentImageIndex < 0) {
            if (imageUris.size() > 0) {
                currentImageIndex = imageUris.size() - 1;
            } else {
                currentImageIndex = 0;
            }
        }
        /*
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.previous)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            }
        });*/
        loadImage();
        isProcessingCommand = false;
    }

    /**
     * show the next image.
     */
    public void next() {
        if (isProcessingCommand)
            return;
        isProcessingCommand = true;
        cancleTimer();
        currentImageIndex++;
        if (currentImageIndex > imageUris.size() - 1) {
            currentImageIndex = 0;
// pictureShowActive = false; restart after last image
        }
        /*
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.next)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            }
        });*/
        loadImage();
        isProcessingCommand = false;
    }

    /**
     * Displays an image and start the picture show timer.
     *
     * @param image image
     */
    public void showImage(final Drawable image) {
        if (image == null) {
            showDefaultImage();
            return;
        }
        Log.d(this.getClass().getName(), "image bounds: " + image.getBounds());
        runOnUiThread(new Runnable() {
            public void run() {
                Log.d(getClass().getName(),
                        "Start set image: " + System.currentTimeMillis());
                imageView.setImageDrawable(image);
                Log.d(getClass().getName(),
                        "End set image: " + System.currentTimeMillis());
            }
        });
    }

    /**
     * Return the configured slide stay duration
     */
    private int getDuration() {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);
        return Integer
                .parseInt(preferences.getString(
                        getString(R.string.image_viewer_settings_duration_key),
                        "5000"));
    }

    // interface SwipeReceiver
    @Override
    public void onRightToLeftSwipe() {
        if (imageUris.size() > 1) {
            next();
        }
    }

    @Override
    public void onLeftToRightSwipe() {
        if (imageUris.size() > 1) {
            previous();
        }
    }

    @Override
    public void onTopToBottomSwipe() {
// do nothing
    }

    @Override
    public void onBottomToTopSwipe() {
// do nothing
    }

    @Override
    public void beginOnTouchProcessing(View v, MotionEvent event) {
        runOnUiThread(this::menuBarsShow);
    }

    @Override
    public void endOnTouchProcessing(View v, MotionEvent event) {
        startMenuHideTimer();
    }

    /**
     *
     */
    private void startMenuHideTimer() {
        Timer menuHideTimer = new Timer();
        menuHideTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> menuBarsHide());
            }
        }, 5000);
    }

    public boolean isPictureShowActive() {
        return pictureShowActive && imageUris != null && imageUris.size() > 1;
    }

    private String getPositionString() {
        return " (" + (currentImageIndex + 1) + "/" + imageUris.size() + ")";
    }

    //FIXME https://stackoverflow.com/questions/26580117/android-how-to-create-overlay-drop-down-menu-similar-to-google-app
    private void menuBarsHide() {
        Log.d(getClass().getName(), "menuBarsHide");
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            Log.d(getClass().getName(), "menuBarsHide ActionBar is null");
            return;
        }

        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE);
        actionBar.hide(); // slides out
    }

    private void menuBarsShow() {
        Log.d(getClass().getName(), "menuBarsShow");
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            Log.d(getClass().getName(), "menuBarsShow ActionBar is null");
            return;
        }
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE);
        actionBar.show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        exit();
    }
}