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

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.SynchronizationInfo;
import de.yaacc.upnp.UpnpClient;

/**
 * @author Tobias Schoene (openbit)
 */
public abstract class AbstractPlayer implements Player, ServiceConnection {

    public static final String PLAYER_ID = "PlayerId";
    public static final String PROPERTY_ITEM = "item";
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private List<PlayableItem> items = new ArrayList<PlayableItem>();
    private int previousIndex = 0;
    private int currentIndex = 0;
    private Handler playerTimer;
    private Timer execTimer;
    private boolean isPlaying = false;
    private boolean isProcessingCommand = false;
    private UpnpClient upnpClient;
    private PlayerService playerService;
    private String name;
    private String shortName;
    private SynchronizationInfo syncInfo;
    private boolean paused;
    private Object loadedItem = null;
    private int currentLoadedIndex = -1;
    private Bitmap icon = null;

    /**
     * @param upnpClient
     */
    public AbstractPlayer(UpnpClient upnpClient) {
        super();
        this.upnpClient = upnpClient;
        startService();
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


    /**
     * @return the context
     */
    public Context getContext() {
        return upnpClient.getContext();
    }

    public Bitmap getIcon() {
        return icon;
    }

    public void setIcon(Bitmap icon) {
        this.icon = icon;
    }

    /**
     * @return the upnpClient
     */
    public UpnpClient getUpnpClient() {
        return upnpClient;
    }

    public void startService() {
        if (playerService == null) {
            if (Build.VERSION.SDK_INT >= 26) {
                upnpClient.getContext().startForegroundService(new Intent(upnpClient.getContext(), PlayerService.class));
            } else {
                upnpClient.getContext().startService(new Intent(upnpClient.getContext(), PlayerService.class));
            }
            upnpClient.getContext().bindService(new Intent(upnpClient.getContext(), PlayerService.class),
                    this, Context.BIND_AUTO_CREATE);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#next()
     */
    @Override
    public void next() {
        if (isProcessingCommand()) {
            return;
        }
        setProcessingCommand(true);


        paused = false;
        previousIndex = currentIndex;
        cancelTimer();
        currentIndex++;
        if (currentIndex > items.size() - 1) {
            currentIndex = 0;
            SharedPreferences preferences = PreferenceManager
                    .getDefaultSharedPreferences(getContext());
            boolean replay = preferences.getBoolean(
                    getContext().getString(
                            R.string.settings_replay_playlist_chkbx), true);
            if (!replay) {
                stop();
                return;
            }

        }
        Context context = getUpnpClient().getContext();
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                public void run() {
                    Toast toast = Toast.makeText(getContext(), getContext()
                            .getResources().getString(R.string.next)
                            + getPositionString(), Toast.LENGTH_SHORT);

                    toast.show();
                }
            });
        }
        setProcessingCommand(false);
        play();

    }

    //

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#previous()
     */
    @Override
    public void previous() {
        if (isProcessingCommand()) {
            return;
        }
        setProcessingCommand(true);

        paused = false;
        previousIndex = currentIndex;
        cancelTimer();
        currentIndex--;
        if (currentIndex < 0) {
            if (items.size() > 0) {
                currentIndex = items.size() - 1;
            } else {
                currentIndex = 0;
            }
        }
        Context context = getUpnpClient().getContext();
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                public void run() {
                    Toast toast = Toast.makeText(getContext(), getContext()
                            .getResources().getString(R.string.previous)
                            + getPositionString(), Toast.LENGTH_SHORT);
                    toast.show();
                }
            });
        }
        setProcessingCommand(false);
        play();

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#pause()
     */
    @Override
    public void pause() {
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                cancelTimer();
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(new Runnable() {
                        public void run() {
                            Toast toast = Toast.makeText(getContext(), getContext()
                                    .getResources().getString(R.string.pause)
                                    + getPositionString(), Toast.LENGTH_SHORT);
                            toast.show();
                        }
                    });
                }
                isPlaying = false;
                paused = true;
                doPause();
                setProcessingCommand(false);
            }
        }, getExecutionTime());
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#start()
     */
    @Override
    public void play() {
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);
        int possibleNextIndex = currentIndex;
        if (possibleNextIndex >= 0 && possibleNextIndex < items.size()) {
            loadItem(possibleNextIndex);
        }
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                if (currentIndex < items.size()) {
                    Context context = getUpnpClient().getContext();
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(new Runnable() {
                            public void run() {
                                Toast toast = Toast.makeText(getContext(), getContext()
                                        .getResources().getString(R.string.play)
                                        + getPositionString(), Toast.LENGTH_SHORT);
                                toast.show();
                            }
                        });
                    }
                    isPlaying = true;
                    if (paused) {
                        doResume();
                    } else {
                        paused = false;
                        loadItem(previousIndex, currentIndex);
                    }
                    setProcessingCommand(false);
                }
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
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);
        currentLoadedIndex = -1;
        loadedItem = null;
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                cancelTimer();
                currentIndex = 0;
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(new Runnable() {
                        public void run() {
                            Toast toast = Toast.makeText(getContext(), getContext()
                                    .getResources().getString(R.string.stop)
                                    + getPositionString(), Toast.LENGTH_SHORT);
                            toast.show();
                        }
                    });
                }
                if (items.size() > 0) {
                    stopItem(items.get(currentIndex));
                }
                isPlaying = false;
                paused = false;
                setProcessingCommand(false);
            }
        }, getExecutionTime());
    }

    /**
     * is shuffle play enabled.
     *
     * @return true, if shuffle play is enabled
     */

    protected boolean isShufflePlay() {
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#clear()
     */
    @Override
    public void clear() {
        items.clear();
    }

    protected void cancelTimer() {
        if (playerTimer != null) {
            playerTimer.removeCallbacksAndMessages(null);
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean isPlaying) {
        this.isPlaying = isPlaying;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public List<PlayableItem> getItems() {
        return items;
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#setItems(de.yaacc.player.PlayableItem[])
     */
    @Override
    public void setItems(PlayableItem... playableItems) {
        List<PlayableItem> itemsList = Arrays.asList(playableItems);

        if (isShufflePlay()) {
            Collections.shuffle(itemsList);
        }
        items.addAll(itemsList);
        showNotification();
    }

    /**
     * returns the current item position in the playlist
     *
     * @return the position string
     */
    public String getPositionString() {
        return " (" + (currentIndex + 1) + "/" + items.size() + ")";
    }

    /**
     * returns the title of the current item
     *
     * @return the title
     */
    public String getCurrentItemTitle() {
        String result = "";
        if (currentIndex < items.size()) {

            result = items.get(currentIndex).getTitle();
        }
        return result;
    }

    /**
     * returns the title of the next current item
     *
     * @return the title
     */
    public String getNextItemTitle() {
        String result = "";
        if (currentIndex + 1 < items.size()) {

            result = items.get(currentIndex + 1).getTitle();
        }
        return result;
    }


    protected Object loadItem(int toLoadIndex) {
        if (toLoadIndex == currentLoadedIndex && loadedItem != null) {
            Log.d(getClass().getName(), "returning already loaded item");
            return loadedItem;
        }
        if (toLoadIndex >= 0 && toLoadIndex <= items.size()) {
            Log.d(getClass().getName(), "loaded item");
            currentLoadedIndex = toLoadIndex;
            loadedItem = loadItem(items.get(toLoadIndex));
            return loadedItem;
        }
        return null;
    }

    protected void loadItem(int previousIndex, int nextIndex) {
        if (items == null || items.size() == 0)
            return;
        PlayableItem playableItem = items.get(nextIndex);
        Object loadedItem = loadItem(nextIndex);
        firePropertyChange(PROPERTY_ITEM, items.get(previousIndex),
                items.get(nextIndex));
        startItem(playableItem, loadedItem);
        if (isPlaying() && items.size() > 1) {
            if (playableItem.getDuration() > -1) {
                //Only start timer if automatic track change is active
                startTimer(playableItem.getDuration() + getSilenceDuration());
            }
        }
    }

    protected void doPause() {
        //default do nothing
    }

    protected void doResume() {
        //default replay current item
        paused = false;
        loadItem(currentIndex, currentIndex);
    }

    /**
     * returns the duration between two items
     *
     * @return duration in millis
     */
    protected long getSilenceDuration() {
        return upnpClient.getSilenceDuration();
    }

    /**
     * Start a timer for the next item change
     *
     * @param duration in millis
     */
    public void startTimer(final long duration) {
        Log.d(getClass().getName(), "Start timer duration: " + duration);
        cancelTimer();
        playerTimer = new Handler(playerService.getPlayerHandlerThread().getLooper());
        playerTimer.postDelayed(new Runnable() {

            @Override
            public void run() {
                Log.d(getClass().getName(), "TimerEvent for switching to next item" + this);
                AbstractPlayer.this.next();
            }
        }, duration);

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

    public boolean isProcessingCommand() {
        return isProcessingCommand;
    }

    public void setProcessingCommand(boolean isProcessingCommand) {
        this.isProcessingCommand = isProcessingCommand;
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#exit()
     */
    @Override
    public void exit() {
        if (isPlaying()) {
            stop();
        }
        playerService.shutdown(this);

    }

    /**
     * Displays the notification.
     */
    private void showNotification() {

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                getContext(), Yaacc.NOTIFICATION_CHANNEL_ID).setOngoing(false)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setLargeIcon(getIcon())
                .setContentTitle("Yaacc player")
                .setContentText(getShortName() == null ? "" : getShortName());
        PendingIntent contentIntent = getNotificationIntent();
        if (contentIntent != null) {
            mBuilder.setContentIntent(contentIntent);
        }
        NotificationManager mNotificationManager = (NotificationManager) getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify(getNotificationId(), mBuilder.build());
    }

    /**
     * Cancels the notification.
     */
    private void cancleNotification() {
        NotificationManager mNotificationManager = (NotificationManager) getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        Log.d(getClass().getName(), "Cancle Notification with ID: " + getNotificationId());
        mNotificationManager.cancel(getNotificationId());

    }

    /**
     * Returns the notification id of the player
     *
     * @return
     */
    protected int getNotificationId() {

        return 0;
    }

    /**
     * Returns the intent which is to be started by pushing the notification
     * entry
     *
     * @return the peneding intent
     */
    public PendingIntent getNotificationIntent() {
        return null;
    }

    protected abstract void stopItem(PlayableItem playableItem);

    protected abstract Object loadItem(PlayableItem playableItem);

    protected abstract void startItem(PlayableItem playableItem,
                                      Object loadedItem);

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#onDestroy()
     */
    @Override
    public void onDestroy() {
        stop();
        int i = 0;
        cancleNotification();
        items.clear();
        if (playerService != null) {
            try {
                playerService.unbindService(this);
            } catch (IllegalArgumentException iex) {
                Log.d(getClass().getName(), "Exception while unbind service");
            }

        }

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#getId()
     */
    @Override
    public int getId() {
        return getNotificationId();
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        this.pcs.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        this.pcs.removePropertyChangeListener(listener);
    }

    protected void firePropertyChange(String property, Object oldValue,
                                      Object newValue) {
        this.pcs.firePropertyChange(property, oldValue, newValue);
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

    protected Date getExecutionTime() {
        Calendar execTime = Calendar.getInstance(Locale.getDefault());
        execTime.set(Calendar.HOUR_OF_DAY, getSyncInfo().getReferencedPresentationTimeOffset().getHour());
        execTime.set(Calendar.MINUTE, getSyncInfo().getReferencedPresentationTimeOffset().getMinute());
        execTime.set(Calendar.SECOND, getSyncInfo().getReferencedPresentationTimeOffset().getSecond());
        execTime.set(Calendar.MILLISECOND, getSyncInfo().getReferencedPresentationTimeOffset().getMillis());
        execTime.add(Calendar.HOUR, getSyncInfo().getOffset().getHour());
        execTime.add(Calendar.MINUTE, getSyncInfo().getOffset().getMinute());
        execTime.add(Calendar.SECOND, getSyncInfo().getOffset().getSecond());
        execTime.add(Calendar.MILLISECOND, getSyncInfo().getOffset().getMillis());
        Log.d(getClass().getName(), "ReferencedRepresentationTimeOffset: " + getSyncInfo().getReferencedPresentationTimeOffset());
        Log.d(getClass().getName(), "current time: " + new Date().toString() + " get execution time: " + execTime.getTime().toString());
        if (execTime.getTime().getTime() <= System.currentTimeMillis()) {
            Log.d(getClass().getName(), "ExecutionTime is in past!! We will start immediately");
            return null;

        }
        return execTime.getTime();
    }

    protected void executeCommand(TimerTask command, Date executionTime) {
        if (execTimer != null) {
            execTimer.cancel();
        }
        execTimer = new Timer();
        if (executionTime == null) {
            execTimer.schedule(command, 100);
        } else {
            execTimer.schedule(command, executionTime);
        }
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

    public void setVolume(int volume) {
        upnpClient.setVolume(volume);
    }

    public int getIconResourceId() {

        return R.drawable.yaacc192_32;
    }

    public String getDeviceId() {
        return UpnpClient.LOCAL_UID;
    }

    public abstract void seekTo(long millisecondsFromStart);

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String name) {
        shortName = name;
    }
}
