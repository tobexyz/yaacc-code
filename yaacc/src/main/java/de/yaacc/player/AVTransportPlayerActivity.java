/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
package de.yaacc.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.fourthline.cling.model.meta.Device;

import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogActivity;
import de.yaacc.util.image.ImageDownloadTask;

/**
 * A avtransport player activity controlling the {@link AVTransportPlayer}.
 *
 * @author Tobias Schoene (openbit)
 */
public class AVTransportPlayerActivity extends AppCompatActivity implements ServiceConnection {

    protected boolean updateTime = false;
    protected SeekBar seekBar = null;
    private PlayerService playerService;
    private int playerId;

    private AVTransportController player;

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


    private PlayerService getPlayerService() {
        return playerService;
    }


    @Override
    protected void onPause() {
        super.onPause();
        updateTime = false;
        if (getPlayerService() != null) {
            try {
                getPlayerService().unbindService(this);
            } catch (IllegalArgumentException iae) {
                Log.d(getClass().getName(), "Ignore exception on unbind service while activity pause");
            }
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
        updateTime = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        setVolumeControlStream(-1000); //use an invalid audio stream to block controlling default streams
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
        updateTime = true;
        setTrackInfo();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (getPlayer() != null && getPlayer().hasActionGetVolume() && (KeyEvent.KEYCODE_VOLUME_UP == keyCode || KeyEvent.KEYCODE_VOLUME_DOWN == keyCode)) {
            Drawable icon = null;
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    if (getPlayer().getVolume() < 100) {
                        getPlayer().setVolume(getPlayer().getVolume() + 1);
                    }
                    icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_volume_up_96, getTheme()), getTheme());
                    break;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    if (getPlayer().getVolume() > 0) {
                        getPlayer().setVolume(getPlayer().getVolume() - 1);
                    }
                    icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_volume_down_96, getTheme()), getTheme());
                    break;
            }
            SeekBar volumeSeekBar = (SeekBar) findViewById(R.id.avtransportPlayerActivityControlVolumeSeekBar);
            volumeSeekBar.setProgress(getPlayer().getVolume());

            LayoutInflater inflater = getLayoutInflater();
            View layout = inflater.inflate(R.layout.custom_toast, (ViewGroup) findViewById(R.id.toast_custom));
            TypedValue typedValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
            layout.setBackgroundColor(typedValue.data);
            ImageView imageView = (ImageView) layout.findViewById(R.id.customToastImageView);
            imageView.setImageDrawable(icon);
            TextView text = (TextView) layout.findViewById(R.id.customToastTextView);
            text.setText("" + getPlayer().getVolume());
            Toast toast = new Toast(getApplicationContext());
            toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(layout);
            toast.show();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        updateTime = false;
        try {
            unbindService(this);
        } catch (IllegalArgumentException iae) {
            Log.d(getClass().getName(), "Ignore exception on unbind service while activity destroy");
        }

    }

    protected void initialize() {
        Player player = getPlayer();
        ImageButton btnPrev = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPrev);
        ImageButton btnNext = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlNext);
        ImageButton btnStop = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlStop);
        ImageButton btnPlay = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPlay);
        ImageButton btnPause = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPause);
        ImageButton btnPlaylist = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPlaylist);
        ImageButton btnExit = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlExit);
        if (player == null) {
            btnPrev.setActivated(false);
            btnNext.setActivated(false);
            btnStop.setActivated(false);
            btnPlay.setActivated(false);
            btnPause.setActivated(false);
            btnExit.setActivated(false);
            btnPlaylist.setActivated(false);
        } else {
            player.addPropertyChangeListener(event -> {
                if (AbstractPlayer.PROPERTY_ITEM.equals(event.getPropertyName())) {
                    runOnUiThread(this::setTrackInfo);

                }

            });
            updateTime = true;
            setTrackInfo();
            btnPrev.setActivated(true);
            btnNext.setActivated(true);
            btnStop.setActivated(true);
            btnPlay.setActivated(true);
            btnPause.setActivated(true);
            btnExit.setActivated(true);
            btnPlaylist.setActivated(true);
        }
        btnPrev.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.previous();
            }

        });
        btnNext.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.next();
            }

        });
        btnPlay.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.play();
            }

        });
        btnPause.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.pause();
            }

        });
        btnStop.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.stop();
            }

        });
        btnExit.setOnClickListener(v -> exit());
        btnPlaylist.setOnClickListener(v -> showPlaylistDialog());
        SwitchMaterial muteSwitch = findViewById(R.id.avtransportPlayerActivityControlMuteSwitch);
        if (getPlayer() != null && getPlayer().hasActionGetMute()) {
            muteSwitch.setEnabled(true);
            muteSwitch.setChecked(getPlayer().getMute());
        } else {
            muteSwitch.setEnabled(true);
            muteSwitch.setChecked(false);
        }

        muteSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (getPlayer() != null && getPlayer().hasActionGetMute()) {
                getPlayer().setMute(isChecked);
            }
        });
        SeekBar volumeSeekBar = (SeekBar) findViewById(R.id.avtransportPlayerActivityControlVolumeSeekBar);
        volumeSeekBar.setMax(100);
        if (getPlayer() != null && getPlayer().hasActionGetVolume()) {
            Log.d(getClass().getName(), "Volume:" + getPlayer().getVolume());
            volumeSeekBar.setEnabled(true);
            volumeSeekBar.setProgress(getPlayer().getVolume());
        } else {
            volumeSeekBar.setEnabled(false);
            volumeSeekBar.setProgress(0);
        }
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (getPlayer() != null && getPlayer().hasActionGetVolume()) {
                    getPlayer().setVolume(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        seekBar = (SeekBar) findViewById(R.id.avtransportPlayerActivityControlSeekBar);
        seekBar.setMax(100);
        seekBar.setProgress(0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                String durationString = getPlayer().getDuration();
                SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                try {
                    long durationTimeMillis = Objects.requireNonNull(dateFormat.parse(durationString)).getTime();

                    int targetPosition = Double.valueOf(durationTimeMillis * ((double) seekBar.getProgress() / 100)).intValue();
                    Log.d(getClass().getName(), "TargetPosition" + targetPosition);
                    getPlayer().seekTo(targetPosition);
                } catch (ParseException pex) {
                    Log.d(getClass().getName(), "Error while parsing time string", pex);
                }

            }

        });
    }

    private void exit() {
        Player player = getPlayer();
        if (player != null) {
            player.exit();
        }
        finish();
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (((Yaacc) getApplicationContext()).isUnplugged()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
        }
        setContentView(R.layout.activity_avtransport_player);
        try {
            this.bindService(new Intent(this, PlayerService.class),
                    this, Context.BIND_AUTO_CREATE);
        } catch (Exception ex) {
            Log.d(getClass().getName(), "ignore exception on service bind during onCreate");
        }
        // initialize buttons
        playerId = getIntent().getIntExtra(AVTransportPlayer.PLAYER_ID, -1);
        String deviceId = getIntent().getStringExtra(AVTransportController.DEVICE_ID);
        if (deviceId != null) {
            UpnpClient upnpClient = ((Yaacc) getApplicationContext()).getUpnpClient();
            Device<?, ?, ?> device = upnpClient.getDevice(deviceId);
            if (device != null) {
                player = new AVTransportController(upnpClient, device);
                findViewById(R.id.avtransportPlayerActivityControlSeekBar).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivityCurrentItem).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivityDuration).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivityElapsedTime).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivityPosition).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivityNextItem).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivityNextLabel).setVisibility(View.INVISIBLE);
                findViewById(R.id.avtransportPlayerActivitySeparator).setVisibility(View.INVISIBLE);
            }
        }
        Log.d(getClass().getName(), "Got id from intent: " + playerId);

    }

    private Player getPlayer() {
        if (player != null) {
            return player;
        }
        if (getPlayerService() == null) {
            return null;
        }
        return getPlayerService().getPlayer(playerId);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_avtransport_player, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_exit) {

            exit();
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

    private void setTrackInfo() {
        doSetTrackInfo();
        updateTime();
    }


    private void doSetTrackInfo() {
        if (getPlayer() == null)
            return;
        if (getPlayer() instanceof AVTransportPlayer && ((AVTransportPlayer) getPlayer()).getDevice() != null) {
            ((TextView) findViewById(R.id.avtransportPlayerActivityDeviceName)).setText(((AVTransportPlayer) getPlayer()).getDevice().getDetails().getFriendlyName());
        }
        TextView current = findViewById(R.id.avtransportPlayerActivityCurrentItem);
        current.setText(getPlayer().getCurrentItemTitle());
        TextView position = findViewById(R.id.avtransportPlayerActivityPosition);
        position.setText(getPlayer().getPositionString());
        TextView next = findViewById(R.id.avtransportPlayerActivityNextItem);
        next.setText(getPlayer().getNextItemTitle());
        ImageView albumArtView = (ImageView) findViewById(R.id.avtransportPlayerActivityImageView);
        URI albumArtUri = getPlayer().getAlbumArt();

        if (null != albumArtUri) {
            ImageDownloadTask imageDownloadTask = new ImageDownloadTask(albumArtView);
            imageDownloadTask.executeOnExecutor(((Yaacc) getApplicationContext()).getContentLoadExecutor(), Uri.parse(albumArtUri.toString()));
        } else if (getPlayer().getIcon() != null) {

            albumArtView.setImageBitmap(getPlayer().getIcon());
        } else {
            albumArtView.setImageDrawable(ThemeHelper.tintDrawable(albumArtView.getDrawable(), getTheme()));
        }
        TextView duration = findViewById(R.id.avtransportPlayerActivityDuration);
        String durationTimeString = getPlayer().getDuration();
        duration.setText(durationTimeString);
        TextView elapsedTime = findViewById(R.id.avtransportPlayerActivityElapsedTime);
        String elapsedTimeString = getPlayer().getElapsedTime();
        elapsedTime.setText(elapsedTimeString);
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            double elapsedTimeMillis = Double.longBitsToDouble(Objects.requireNonNull(dateFormat.parse(elapsedTimeString)).getTime());
            double durationTimeMillis = Double.longBitsToDouble(Objects.requireNonNull(dateFormat.parse(durationTimeString)).getTime());
            int progress;
            progress = Double.valueOf((elapsedTimeMillis / durationTimeMillis) * 100).intValue();
            if (seekBar != null) {
                seekBar.setProgress(progress);
            }
        } catch (ParseException pex) {
            Log.d(getClass().getName(), "Error while parsing time string", pex);
        }
    }

    private void updateTime() {
        Timer commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                runOnUiThread(() -> {
                    doSetTrackInfo();
                    if (updateTime) {
                        updateTime();
                    }
                });
            }
        }, 1000L);

    }

    public void showPlaylistDialog() {
        PlaylistDialogFragment.show(getSupportFragmentManager(), getPlayer());
    }

}
