/*
*
* Copyright (C) 2013 www.yaacc.de
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

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask.Status;
import android.os.Bundle;
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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.player.LocalImagePlayer;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerFactory;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.ActivitySwipeDetector;
import de.yaacc.util.SwipeReceiver;
import de.yaacc.util.YaaccLogActivity;

/**
 * a simple ImageViewer based on the android ImageView component;
 *
 * you are able to start the activity either by using intnet.setData(anUri) or
 * by intent.putExtra(ImageViewerActivity.URIS, aList<Uri>); in the later case
 * the activity needed to be started with Intent.ACTION_SEND_MULTIPLE
 *
 *
 * The image viewer retrieves all images in a background task
 * (RetrieveImageTask). The images are written in a memory cache. The picture
 * show is processed by the ImageViewerActivity using the images in the cache.
 *
 * @author Tobias Schoene (openbit)
 *
 */
public class ImageViewerActivity extends Activity implements SwipeReceiver {
    public static final String URIS = "URIS_PARAM"; // String Intent parameter
    public static final String AUTO_START_SHOW = "AUTO_START_SHOW"; // Boolean
    // Intent
// parameter
// default
// false
    private ImageView imageView;
    private RetrieveImageTask retrieveImageTask;
    private List<Uri> imageUris; // playlist
    private int currentImageIndex = 0;
    private boolean pictureShowActive = false;
    private boolean isProcessingCommand = false; // indicates an command
    private Timer pictureShowTimer;
    private ImageViewerBroadcastReceiver imageViewerBroadcastReceiver;
    @SuppressWarnings("unchecked")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(this.getClass().getName(), "OnCreate");
        super.onCreate(savedInstanceState);
        init(savedInstanceState, getIntent());
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
        imageView = (ImageView) findViewById(R.id.imageView);
        ActivitySwipeDetector activitySwipeDetector = new ActivitySwipeDetector(
                this);
        RelativeLayout layout = (RelativeLayout) this.findViewById(R.id.layout);
        layout.setOnTouchListener(activitySwipeDetector);
        currentImageIndex = 0;
        imageUris = new ArrayList<Uri>();
        if (savedInstanceState != null) {
            pictureShowActive = savedInstanceState
                    .getBoolean("pictureShowActive");
            currentImageIndex = savedInstanceState.getInt("currentImageIndex");
            imageUris = (List<Uri>) savedInstanceState
                    .getSerializable("imageUris");
        }
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
        if (imageUris.size() > 0) {
            loadImage();
        } else {
            runOnUiThread(new Runnable() {
                public void run() {
                    Toast toast = Toast.makeText(ImageViewerActivity.this,
                            R.string.no_valid_uri_data_found_to_display,
                            Toast.LENGTH_LONG);
                    toast.show();
                    menuBarsHide();
                }
            });
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
    }
    /*
    * (non-Javadoc)
    *
    * @see android.app.Activity#onPause()
    */
    @Override
    protected void onPause() {
        cancleTimer();
        unregisterReceiver(imageViewerBroadcastReceiver);
        imageViewerBroadcastReceiver = null;
        super.onPause();
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
// Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_image_viewer, menu);

        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i = null;
        switch (item.getItemId()) {
            case R.id.menu_settings:
                i = new Intent(this, SettingsActivity.class);
                startActivity(i);
                return true;
            case R.id.menu_next:
                next();
                return true;
            case R.id.menu_pause:
                pause();
                return true;
            case R.id.menu_play:
                play();
                return true;
            case R.id.menu_previous:
                previous();
                return true;
            case R.id.menu_stop:
                stop();
                return true;
            case R.id.yaacc_log:
                YaaccLogActivity.showLog(this);
                return true;
            case R.id.yaacc_about:
                AboutActivity.showAbout(this);
                return true;
            case R.id.menu_exit:
                exit();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void exit() {
        Player player = PlayerFactory.getFirstCurrentPlayerOfType(LocalImagePlayer.class);
        if(player != null){
        player.exit();
        }else{
            finish();
        }
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
            imageUris = new ArrayList<Uri>(imageUris);
        }
        savedInstanceState.putSerializable("imageUris",
                (ArrayList<Uri>) imageUris);
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
            runOnUiThread(new Runnable() {
                public void run() {
                    Toast toast = Toast.makeText(ImageViewerActivity.this,
                            getResources().getString(R.string.play)
                                    + getPositionString(), Toast.LENGTH_SHORT);
                    toast.show();
                }
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
        retrieveImageTask.execute(imageUris.get(currentImageIndex));
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
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.stop)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            }
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
        imageView.setImageDrawable(getResources().getDrawable(
                R.drawable.ic_launcher));
    }
    /**
     * Stop the timer.
     */
    public void pause() {
        if (isProcessingCommand)
            return;
        isProcessingCommand = true;
        cancleTimer();
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.pause)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            }
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
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.previous)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            }
        });
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
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(ImageViewerActivity.this,
                        getResources().getString(R.string.next)
                                + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
            }
        });
        loadImage();
        isProcessingCommand = false;
    }
    /**
     * Displays an image and start the picture show timer.
     *
     * @param image
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
                        "2000"));
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
        runOnUiThread(new Runnable() {
            public void run() {
                menuBarsShow();
            }
        });
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
                runOnUiThread(new Runnable() {
                    public void run() {
                        menuBarsHide();
                    }
                });
            }
        }, 5000);
    }
    public boolean isPictureShowActive() {
        return pictureShowActive && imageUris != null && imageUris.size() > 1;
    }
    private String getPositionString() {
        return " (" + (currentImageIndex + 1) + "/" + imageUris.size() + ")";
    }
    private void menuBarsHide() {
        Log.d(getClass().getName(), "menuBarsHide");
        ActionBar actionBar = getActionBar();
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
        ActionBar actionBar = getActionBar();
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