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
import android.support.v4.media.session.MediaSessionCompat;

import de.yaacc.util.YaaccLogger;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.imageviewer.ImageViewerActivity;
import de.yaacc.imageviewer.ImageViewerBroadcastReceiver;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;

/**
 * Player for local image viewing activity.
 * Simplified - no notification code, just Player interface + remote control.
 *
 * @author Tobias Schoene (openbit)
 */
public class LocalImagePlayer implements Player, ServiceConnection {

    private final UpnpClient upnpClient;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private Timer commandExecutionTimer;
    private String name;
    private String shortName;
    private PlayerService playerService;
    private boolean isPlaying;
    private ArrayList<Uri> imageUris;  // Store URIs for reopening activity
    private int currentIndex;  // Store current position for resuming slideshow

    public LocalImagePlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
        startService();
    }

    public LocalImagePlayer(UpnpClient upnpClient) {
        this.upnpClient = upnpClient;
    }

    public void startService() {
        if (playerService == null) {
            upnpClient.getContext().startForegroundService(
                new Intent(upnpClient.getContext(), PlayerService.class));
            upnpClient.getContext().bindService(
                new Intent(upnpClient.getContext(), PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
        }
    }

    public void onServiceConnected(ComponentName className, IBinder binder) {
        if (binder instanceof PlayerService.PlayerServiceBinder) {
            YaaccLogger.d("ServiceConnection", "connected");
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            playerService.addPlayer(this);
        }
    }

    public void onServiceDisconnected(ComponentName className) {
        YaaccLogger.d("ServiceConnection", "disconnected");
        if (playerService != null) {
            playerService.removePlayer(this);
        }
        playerService = null;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean isPlaying) {
        this.isPlaying = isPlaying;
    }

    @Override
    public void next() {
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

    @Override
    public void previous() {
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

    @Override
    public void pause() {
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_PAUSE);
                upnpClient.getContext().sendBroadcast(intent);
                setPlaying(false);
            }
        }, new Date());
    }

    @Override
    public void play() {
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                YaaccLogger.d(this.getClass().getName(), "send play");
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_PLAY);
                upnpClient.getContext().sendBroadcast(intent);
                setPlaying(true);
            }
        }, new Date());
    }

    @Override
    public void stop() {
        commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setAction(ImageViewerBroadcastReceiver.ACTION_STOP);
                upnpClient.getContext().sendBroadcast(intent);
                setPlaying(false);
            }
        }, new Date());
    }

    @Override
    public void setItems(PlayableItem... items) {
        Intent intent = new Intent(upnpClient.getContext(), ImageViewerActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ArrayList<Uri> uris = new ArrayList<>();
        for (PlayableItem item : items) {
            uris.add(item.getUri());
        }
        intent.putParcelableArrayListExtra(ImageViewerActivity.URIS, uris);
        
        // Store URIs for reopening activity
        this.imageUris = uris;
        
        upnpClient.getContext().startActivity(intent);
    }

    @Override
    public String getName() {
        return name;
    }

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
        this.shortName = name;
    }

    @Override
    public void exit() {
        if (isPlaying()) {
            stop();
        }
        if (playerService != null) {
            playerService.shutdown(this);
        }
    }

    @Override
    public void clear() {
        // Not implemented
    }

    @Override
    public void onDestroy() {
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

    @Override
    public String getPositionString() {
        return "";
    }

    @Override
    public String getCurrentItemTitle() {
        return "";
    }

    @Override
    public int getCurrentItemIndex() {
        return 0;
    }

    @Override
    public String getNextItemTitle() {
        return "";
    }

    @Override
    public String getDuration() {
        return "";
    }

    @Override
    public long getCurrentPosition() {
        return 0;
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

    public boolean getMute() {
        return upnpClient.isMute();
    }

    public void setMute(boolean mute) {
        upnpClient.setMute(mute);
    }

    public int getVolume() {
        return upnpClient.getVolume();
    }

    @Override
    public boolean hasActionGetVolume() {
        return false;
    }

    @Override
    public boolean hasActionGetMute() {
        return false;
    }

    public void setVolume(int volume) {
        upnpClient.setVolume(volume);
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_image_32;
    }

    @Override
    public String getDeviceId() {
        return UpnpClient.LOCAL_UID;
    }

    @Override
    public int getId() {
        return NotificationId.LOCAL_IMAGE_PLAYER.getId();
    }

    @Override
    public PendingIntent getNotificationIntent() {
        // Create intent to open ImageViewerActivity
        Intent intent = new Intent(upnpClient.getContext(), ImageViewerActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        // Include stored URIs so activity can reopen with images
        if (imageUris != null && !imageUris.isEmpty()) {
            intent.putParcelableArrayListExtra(ImageViewerActivity.URIS, imageUris);
            intent.putExtra("currentIndex", currentIndex);
        }
        
        return PendingIntent.getActivity(
            upnpClient.getContext(), 
            getId(), 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public ArrayList<Uri> getImageUris() {
        return imageUris;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int index) {
        this.currentIndex = index;
    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        // Do nothing
    }

    @Override
    public void addItems(List<PlayableItem> playableItemList) {
        // Not yet implemented
    }

    @Override
    public List<PlayableItem> getItems() {
        return new ArrayList<>();
    }

    @Override
    public void fastForward(int i) {
        // Not implemented
    }

    @Override
    public void fastRewind(int i) {
        // Not implemented
    }

    @Override
    public MediaSessionCompat getMediaSession() {
        // Image player doesn't use MediaSession
        return null;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener);
    }
}
