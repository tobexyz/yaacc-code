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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.musicplayer.BackgroundMusicBroadcastReceiver;
import de.yaacc.musicplayer.BackgroundMusicService;
import de.yaacc.musicplayer.BackgroundMusicService.BackgroundMusicServiceBinder;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;

/**
 * A Player for local music playing in background
 *
 * @author Tobias Schoene (openbit)
 */
public class LocalBackgoundMusicPlayer extends AbstractPlayer implements ServiceConnection {

    private BackgroundMusicService backgroundMusicService;
    private Timer commandExecutionTimer;
    private URI albumArtUri;

    /**
     * @param name playerName
     */
    public LocalBackgoundMusicPlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
    }


    public LocalBackgoundMusicPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        Log.d(getClass().getName(), "Starting background music service... ");
        Context context = getUpnpClient().getContext();

        context.startForegroundService(new Intent(context, BackgroundMusicService.class));

        context.bindService(new Intent(context, BackgroundMusicService.class), LocalBackgoundMusicPlayer.this, Context.BIND_AUTO_CREATE);

    }


    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#onDestroy()
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (backgroundMusicService != null) {
            backgroundMusicService.stop();
            try {
                backgroundMusicService.unbindService(this);
            } catch (IllegalArgumentException iex) {
                Log.d(getClass().getName(), "ignoring exception while unbind service");
            }
        }

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#pause()
     */
    @Override
    protected void doPause() {

        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(BackgroundMusicBroadcastReceiver.ACTION_PAUSE);
                getContext().sendBroadcast(intent);

            }
        }, 600L);
    }

    @Override
    protected void doResume() {
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(BackgroundMusicBroadcastReceiver.ACTION_PLAY);
                getContext().sendBroadcast(intent);

            }
        }, 600L);
        int timeLeft = getBackgroundService().getDuration() - getBackgroundService().getCurrentPosition();
        Log.d(this.getClass().getName(), "TimeLeft after resume: " + timeLeft + " duration: " + getBackgroundService().getDuration() + " curPos: " + getBackgroundService().getCurrentPosition());
        startTimer(timeLeft + getSilenceDuration());
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * de.yaacc.player.AbstractPlayer#stopItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected void stopItem(PlayableItem playableItem) {

        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // because there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(BackgroundMusicBroadcastReceiver.ACTION_STOP);
                getContext().sendBroadcast(intent);

            }
        }, 600L);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * de.yaacc.player.AbstractPlayer#loadItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected Object loadItem(PlayableItem playableItem) {
        final Uri uri = playableItem.getUri();
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // because there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(BackgroundMusicBroadcastReceiver.ACTION_SET_DATA);
                intent.putExtra(BackgroundMusicBroadcastReceiver.ACTION_SET_DATA_URI_PARAM, uri);
                getContext().sendBroadcast(intent);
            }
        }, 500L); //Must be the first command
        return uri;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * de.yaacc.player.AbstractPlayer#startItem(de.yaacc.player.PlayableItem,
     * java.lang.Object)
     */
    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem) {
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // because there is no known way to query the activity state
        // we are sending the command delayed
        DIDLObject.Property<URI> albumArtUriProperty = playableItem.getItem() == null ? null : playableItem.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        albumArtUri = (albumArtUriProperty == null) ? null : albumArtUriProperty.getValue();

        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(BackgroundMusicBroadcastReceiver.ACTION_PLAY);
                getContext().sendBroadcast(intent);
            }
        }, 600L);
    }


    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
     */
    @Override
    public PendingIntent getNotificationIntent() {
        Intent notificationIntent = new Intent(getContext(), MusicPlayerActivity.class);
        notificationIntent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getActivity(getContext(), 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#getNotificationId()
     */
    @Override
    protected int getNotificationId() {

        return NotificationId.LOCAL_BACKGROUND_MUSIC_PLAYER.getId();
    }

    /**
     * read the setting for music player shuffle play.
     *
     * @return true, if shuffle play is enabled
     */
    @Override
    protected boolean isShufflePlay() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return preferences.getBoolean(getContext().getString(R.string.settings_music_player_shuffle_chkbx), false);

    }

    /**
     * Returns the duration of the current track
     *
     * @return the duration
     */
    public String getDuration() {
        if (!isMusicServiceBound()) return "";
        return formatMillis(getBackgroundService().getDuration());

    }

    public String getElapsedTime() {
        if (!isMusicServiceBound()) return "";
        return formatMillis(getBackgroundService().getCurrentPosition());
    }


    @Override
    public URI getAlbumArt() {
        return albumArtUri;
    }

    private String formatMillis(long millis) {


        int hours = (int) (millis / (1000 * 60 * 60));
        int minutes = (int) ((millis % (1000 * 60 * 60)) / (1000 * 60));
        int seconds = (int) (((millis % (1000 * 60 * 60)) % (1000 * 60)) / 1000);

        return String.format(Locale.ENGLISH, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
        Log.d(getClass().getName(), "onServiceConnected..." + className);
        if (binder instanceof BackgroundMusicServiceBinder) {
            backgroundMusicService = ((BackgroundMusicServiceBinder) binder).getService();
        } else {
            super.onServiceConnected(className, binder);
        }

    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        Log.d(getClass().getName(), "onServiceDisconnected...");
        backgroundMusicService = null;

    }

    /**
     * True if the player is initialized.
     *
     * @return true or false
     */
    public boolean isMusicServiceBound() {
        return backgroundMusicService != null;
    }

    private BackgroundMusicService getBackgroundService() {
        return backgroundMusicService;
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.cdtrack;
    }

    public void seekTo(long millisecondsFromStart) {
        backgroundMusicService.seekTo(millisecondsFromStart);

    }
}
