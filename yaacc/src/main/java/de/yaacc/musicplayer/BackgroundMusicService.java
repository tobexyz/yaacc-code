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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.musicplayer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.browser.TabBrowserActivity;
import de.yaacc.util.NotificationId;

/**
 * A simple service for playing music in background.
 *
 * @author Tobias Schoene (openbit)
 */
public class BackgroundMusicService extends Service {
    public static final String URIS = "URIS_PARAM"; // String Intent parameter
    private MediaPlayer player;
    private IBinder binder = new BackgroundMusicServiceBinder();
    private BackgroundMusicBroadcastReceiver backgroundMusicBroadcastReceiver;
    private int duration = 0;
    //private boolean prepared  = false;

    public BackgroundMusicService() {
        super();
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onCreate()
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(this.getClass().getName(), "On Create");
        Intent notificationIntent = new Intent(this, TabBrowserActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Background Music Service")
                .setContentText("running")
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentIntent(pendingIntent)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .build();
        startForeground(NotificationId.BACKGROUND_MUSIC_SERVICE.getId(), notification);

    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onDestroy()
     */
    @Override
    public void onDestroy() {
        Log.d(this.getClass().getName(), "On Destroy");
        if (player != null) {
            player.stop();
            player.release();
        }
        unregisterReceiver(backgroundMusicBroadcastReceiver);
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this.getClass().getName(), "On Bind");
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        initialize(intent);
        return START_STICKY;
    }

    private void initialize(Intent intent) {
        backgroundMusicBroadcastReceiver = new BackgroundMusicBroadcastReceiver(this);
        backgroundMusicBroadcastReceiver.registerReceiver();
        if (player != null) {
            player.stop();
            player.release();
        }
        try {
            if (intent != null && intent.getData() != null) {
                setMusicUri(intent.getData());
            }
        } catch (Exception e) {
            Log.e(this.getClass().getName(), "Exception while changing datasource uri", e);


        }

    }

    /**
     * stop current music play
     */
    public void stop() {
        if (player != null) {
            player.stop();

        }
    }

    /**
     * start current music play
     */
    public void play() {
        //final ActionState actionState = new ActionState();
        if (player != null && !player.isPlaying()) {

            player.start();

        }
    }

    /**
     * pause current music play
     */
    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * Seeks to position
     *
     * @param pos
     */
    public void seekTo(long pos) {
        if (player != null) {
            player.seekTo(Long.valueOf(pos).intValue());
        }

    }

    /**
     * change music uri
     *
     * @param uri
     */
    public void setMusicUri(Uri uri) {
        Log.d(this.getClass().getName(), "changing datasource uri to:" + uri.toString());
        if (player != null) {
            player.release();
        }
        player = new MediaPlayer();
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                Log.e(getClass().getName(), "Error in State  " + what + " extra: " + extra);
                return true;
            }
        });
        player.setVolume(100, 100);
        //prepared= false;
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(this, uri);


            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    duration = player.getDuration();
                }
            });
            //player.prepareAsync();

            player.prepare();
        } catch (Exception e) {
            Log.e(this.getClass().getName(), "Exception while changing datasource uri", e);


        }

    }

    /**
     * returns the duration of the current track
     *
     * @return the duration
     */
    public int getDuration() {

        return duration;
    }

    /**
     * return the current position in the playing track
     *
     * @return the position
     */
    public int getCurrentPosition() {
        int currentPosition = 0;
        if (player != null) {
            try {
                currentPosition = player.getCurrentPosition();
            } catch (Exception ex) {
                Log.d(getClass().getName(), "Caught player exception", ex);
            }
        }

        return currentPosition;
    }

    public class BackgroundMusicServiceBinder extends Binder {
        public BackgroundMusicService getService() {
            return BackgroundMusicService.this;
        }
    }


}