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

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.beans.PropertyChangeListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.imageviewer.ImageViewerActivity;
import de.yaacc.imageviewer.ImageViewerBroadcastReceiver;
import de.yaacc.upnp.SynchronizationInfo;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;

/**
 * Player for local image viewing activity
 *
 * @author Tobias Schoene (openbit)
 */
public class LocalImagePlayer implements Player, ServiceConnection {


    private Timer commandExecutionTimer;
    private String name;
    private String shortName;
    private UpnpClient upnpClient;
    private SynchronizationInfo syncInfo;
    private PendingIntent notificationIntent;
    private PlayerService playerService;


    /**
     * @param upnpClient
     * @param name       playerName
     */
    public LocalImagePlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
        startService();

    }

    /**
     * @param upnpClient
     */
    public LocalImagePlayer(UpnpClient upnpClient) {
        this.upnpClient = upnpClient;
    }

    public void startService() {
        if (playerService == null) {
            upnpClient.getContext().startForegroundService(new Intent(upnpClient.getContext(), PlayerService.class));
            upnpClient.getContext().bindService(new Intent(upnpClient.getContext(), PlayerService.class),
                    this, Context.BIND_AUTO_CREATE);
        }
    }

    public void onServiceConnected(ComponentName className, IBinder binder) {
        if (binder instanceof PlayerService.PlayerServiceBinder) {
            Log.d("ServiceConnection", "connected");

            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            playerService.addPlayer(this);
        }
    }


    public void onServiceDisconnected(ComponentName className) {
        Log.d("ServiceConnection", "disconnected");
        playerService = null;
        playerService.removePlayer(this);
    }


    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#next()
     */
    @Override
    public void next() {
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // in order there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_NEXT);
                upnpClient.getContext().sendBroadcast(intent);

            }
        }, 500L);

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#previous()
     */
    @Override
    public void previous() {
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // in order there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_PREVIOUS);
                upnpClient.getContext().sendBroadcast(intent);

            }
        }, 500L);

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#pause()
     */
    @Override
    public void pause() {
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // in order there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_PAUSE);
                upnpClient.getContext().sendBroadcast(intent);

            }
        }, getExecutionTime());

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#play()
     */
    @Override
    public void play() {
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // in order there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Log.d(this.getClass().getName(), "send play");
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_PLAY);
                upnpClient.getContext().sendBroadcast(intent);

            }
        }, getExecutionTime());

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#stop()
     */
    @Override
    public void stop() {
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // in order there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_STOP);
                upnpClient.getContext().sendBroadcast(intent);

            }
        }, getExecutionTime());

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#setItems(de.yaacc.player.PlayableItem[])
     */
    @Override
    public void setItems(PlayableItem... items) {
        Intent intent = new Intent(upnpClient.getContext(), ImageViewerActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (int i = 0; i < items.length; i++) {
            uris.add(items[i].getUri());
        }
        intent.putExtra(ImageViewerActivity.URIS, uris);
        upnpClient.getContext().startActivity(intent);
        showNotification(uris);
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#getName()
     */
    @Override
    public String getName() {

        return name;
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#setName(java.lang.String)
     */
    @Override
    public void setName(String name) {
        this.name = name;

    }

    @Override
    public String getShortName() {
        return shortName;
    }

    @Override
    public void setShortName(String name) {
        shortName = name;

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#exit()
     */
    @Override
    public void exit() {
        playerService.shutdown(this);

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#clear()
     */
    @Override
    public void clear() {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#onDestroy()
     */
    @Override
    public void onDestroy() {
        cancleNotification();
        // Communicating with the activity is only possible after the activity
        // is started
        // if we send an broadcast event to early the activity won't be up
        // in order there is no known way to query the activity state
        // we are sending the command delayed
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_EXIT);
                upnpClient.getContext().sendBroadcast(intent);

            }
        }, 500L);

    }

    /**
     * Displays the notification.
     *
     * @param uris
     */
    private void showNotification(ArrayList<Uri> uris) {

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                upnpClient.getContext(), Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setOngoing(false)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setLargeIcon(getIcon())
                .setContentTitle(
                        "Yaacc player " + (getName() == null ? "" : getName()));
        // .setContentText("Current Title");
        PendingIntent contentIntent = getNotificationIntent(uris);
        if (contentIntent != null) {
            mBuilder.setContentIntent(contentIntent);
        }
        NotificationManager mNotificationManager = (NotificationManager) upnpClient.getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify(getNotificationId(), mBuilder.build());
    }

    /**
     * Cancels the notification.
     */
    private void cancleNotification() {
        NotificationManager mNotificationManager = (NotificationManager) upnpClient.getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.cancel(getNotificationId());

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
     */
    private PendingIntent getNotificationIntent(ArrayList<Uri> uris) {
        Intent intent = new Intent(upnpClient.getContext(),
                ImageViewerActivity.class);
        intent.setData(Uri.parse("http://0.0.0.0/" + Arrays.hashCode(uris.toArray()) + "")); //just for making the intents different http://stackoverflow.com/questions/10561419/scheduling-more-than-one-pendingintent-to-same-activity-using-alarmmanager
        intent.putExtra(ImageViewerActivity.URIS, uris);
        notificationIntent = PendingIntent.getActivity(upnpClient.getContext(), 0,
                intent, PendingIntent.FLAG_IMMUTABLE);
        return notificationIntent;
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#getNotificationId()
     */
    private int getNotificationId() {

        return NotificationId.LOCAL_IMAGE_PLAYER.getId();
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.Player#getId()
     */
    @Override
    public int getId() {
        return getNotificationId();
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        throw new UnsupportedOperationException();

    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        throw new UnsupportedOperationException();

    }


    /**
     * returns the current item position in the playlist
     *
     * @return the position string
     */
    public String getPositionString() {
        return "";
    }

    /**
     * returns the title of the current item
     *
     * @return the title
     */
    public String getCurrentItemTitle() {
        return "";
    }


    /**
     * returns the title of the next current item
     *
     * @return the title
     */
    public String getNextItemTitle() {
        return "";
    }

    @Override
    public String getDuration() {
        return "";
    }

    @Override
    public String getElapsedTime() {
        return "";
    }

    @Override
    public URI getAlbumArt() {
        return null;
    }

    @Override
    public Bitmap getIcon() {
        return null;
    }

    @Override
    public void setIcon(Bitmap icon) {

    }

    @Override
    public SynchronizationInfo getSyncInfo() {
        return syncInfo;
    }

    @Override
    public void setSyncInfo(SynchronizationInfo syncInfo) {
        if (syncInfo == null) {
            syncInfo = new SynchronizationInfo();
        }
        this.syncInfo = syncInfo;
    }

    private long getExecutionTime() {
        return getSyncInfo().getOffset().toNanos() / 1000000 + 600L;
    }

    //TODO Refactor not every player has a volume control
    public boolean getMute() {
        return upnpClient.isMute();
    }


    public void setMute(boolean mute) {
        upnpClient.setMute(mute);
    }

    public int getVolume() {
        return upnpClient.getVolume();
    }

    public void setVolume(int volume) {
        upnpClient.setVolume(volume);
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.image;
    }

    @Override
    public String getDeviceId() {
        return UpnpClient.LOCAL_UID;
    }

    @Override
    public PendingIntent getNotificationIntent() {
        return notificationIntent;
    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        // Do nothing
    }

}
