/*
 * Copyright (C) 2013 www.yaacc.de 
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.YaaccLogActivity;
import de.yaacc.util.image.ImageDownloadTask;

/**
 * A avtransport player activity controlling the {@link AVTransportPlayer}.
 *
 * @author Tobias Schoene (openbit)
 */
public class AVTransportPlayerActivity extends Activity implements ServiceConnection {

    private PlayerService playerService;
    private int playerId;
    protected boolean updateTime = false;
    protected SeekBar seekBar = null;

    public void onServiceConnected(ComponentName className, IBinder binder) {
        if(binder instanceof PlayerService.PlayerServiceBinder) {
            Log.d(getClass().getName(), "PlayerService connected");
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            initialize();
        }
    }
    //binder comes from server to communicate with method's of

    public void onServiceDisconnected(ComponentName className) {
        Log.d(getClass().getName(),"PlayerService disconnected");
        playerService = null;
    }


    private PlayerService getPlayerService(){
        return playerService;
    }
    @Override
    protected void onPause() {
        super.onPause();
        updateTime = false;
        try {
            getPlayerService().unbindService(this);
        }catch (IllegalArgumentException iae){
            Log.d(getClass().getName(), "Ignore exception on unbind service while activity pause");
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
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
        updateTime = true;
        setTrackInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        updateTime = false;
        try {
            getPlayerService().unbindService(this);
        }catch (IllegalArgumentException iae){
            Log.d(getClass().getName(), "Ignore exception on unbind service while activity destroy");
        }

    }

    protected void initialize(){
        Player player = getPlayer();
        ImageButton btnPrev = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPrev);
        ImageButton btnNext = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlNext);
        ImageButton btnStop = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlStop);
        ImageButton btnPlay = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPlay);
        ImageButton btnPause = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlPause);
        ImageButton btnExit = (ImageButton) findViewById(R.id.avtransportPlayerActivityControlExit);
        if (player == null) {
            btnPrev.setActivated(false);
            btnNext.setActivated(false);
            btnStop.setActivated(false);
            btnPlay.setActivated(false);
            btnPause.setActivated(false);
            btnExit.setActivated(false);
        } else {
            player.addPropertyChangeListener(new PropertyChangeListener() {

                @Override
                public void propertyChange(PropertyChangeEvent event) {
                    if (AbstractPlayer.PROPERTY_ITEM.equals(event.getPropertyName())) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                setTrackInfo();
                            }
                        });

                    }

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
        }
        btnPrev.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.previous();
                }

            }
        });
        btnNext.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.next();
                }

            }
        });
        btnPlay.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.play();
                }

            }
        });
        btnPause.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.pause();
                }

            }
        });
        btnStop.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.stop();
                }

            }
        });
        btnExit.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.exit();
                }
                finish();

            }
        });

        Switch muteSwitch = (Switch) findViewById(R.id.avtransportPlayerActivityControlMuteSwitch);
        muteSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (getPlayer() != null) {
                    getPlayer().setMute(isChecked);
                }
            }
        });
        SeekBar volumeSeekBar = (SeekBar) findViewById(R.id.avtransportPlayerActivityControlVolumeSeekBar);
        volumeSeekBar.setMax(100);
        if (getPlayer() != null) {
            Log.d(getClass().getName(),"Volumne:" + getPlayer().getVolume());
            volumeSeekBar.setProgress(getPlayer().getVolume());
        }else{
            volumeSeekBar.setProgress(100);
        }
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (getPlayer() != null) {
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

        seekBar = (SeekBar)findViewById(R.id.avtransportPlayerActivityControlSeekBar);
        seekBar.setMax(100);
        seekBar.setProgress(0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progresValue, boolean fromUser) {
            }

            @Override
            public  void onStartTrackingTouch(android.widget.SeekBar seekBar){

            }

            @Override
            @SuppressLint("SimpleDateFormat")
            public  void onStopTrackingTouch(android.widget.SeekBar seekBar){
                String durationString = getPlayer().getDuration();
                SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                try {
                    Long durationTimeMillis = dateFormat.parse(durationString).getTime();

                    int targetPosition = Double.valueOf(durationTimeMillis * (Double.valueOf(seekBar.getProgress()).doubleValue() / 100)).intValue();
                    Log.d(getClass().getName(),"TargetPosition" + targetPosition);
                    getPlayer().seekTo(targetPosition);
                }catch(ParseException pex){
                    Log.d(getClass().getName(),"Error while parsing time string" , pex);
                }

            }

        });
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_avtransport_player);
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
        // initialize buttons
        playerId = getIntent().getIntExtra(AVTransportPlayer.PLAYER_ID, -1);
        Log.d(getClass().getName(), "Got id from intent: " + playerId);





    }

    private Player getPlayer(){
        if (getPlayerService() == null){
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
        switch (item.getItemId()) {
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

    private void setTrackInfo() {
        doSetTrackInfo();
        updateTime();
    }

    @SuppressLint("SimpleDateFormat")
    private void doSetTrackInfo() {
        if (getPlayer() == null)
            return;
        ImageView albumArtView = (ImageView) findViewById(R.id.avtransportPlayerActivityImageView);
        URI albumArtUri = getPlayer().getAlbumArt();
        if (null != albumArtUri) {
            ImageDownloadTask imageDownloadTask = new ImageDownloadTask(albumArtView);
            imageDownloadTask.execute(Uri.parse(albumArtUri.toString()));
        }
        TextView duration = (TextView) findViewById(R.id.avtransportPlayerActivityDuration);
        String durationTimeString = getPlayer().getDuration();
        duration.setText(durationTimeString);
        TextView elapsedTime = (TextView) findViewById(R.id.avtransportPlayerActivityElapsedTime);
        String elapsedTimeString = getPlayer().getElapsedTime();
        elapsedTime.setText(elapsedTimeString);
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            Long elapsedTimeMillis = dateFormat.parse(elapsedTimeString).getTime();
            Long durationTimeMillis = dateFormat.parse(durationTimeString).getTime();
            int progress;
            progress = Double.valueOf((elapsedTimeMillis.doubleValue()/  durationTimeMillis.doubleValue()) *100).intValue();
            if(seekBar != null) {
                seekBar.setProgress(progress);
            }
        }catch(ParseException pex){
            Log.d(getClass().getName(),"Error while parsing time string" , pex);
        }
    }
    private void updateTime() {
        Timer commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        doSetTrackInfo();
                        if (updateTime) {
                            updateTime();
                        }
                    }
                });
            }
        }, 1000L);

    }

}
