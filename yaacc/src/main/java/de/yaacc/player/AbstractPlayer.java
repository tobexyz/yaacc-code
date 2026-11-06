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

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.share.QueueShareWindow;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;

/**
 * @author Tobias Schoene (openbit)
 */
public abstract class AbstractPlayer implements Player, ServiceConnection {

    public static final String PLAYER_ID = "PlayerId";
    public static final String PROPERTY_ITEM = "item";
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    // copy on write: a player whose list lives on a device replaces it from its own thread while the screen reads it
    private volatile List<PlayableItem> items = new CopyOnWriteArrayList<>();
    private final UpnpClient upnpClient;
    private volatile int previousIndex = 0;
    private volatile int currentIndex = 0;
    private Timer execTimer;
    private PendingIntent alarmIntent;
    private volatile boolean isPlaying = false;
    private boolean isProcessingCommand = false;
    private PlayerService playerService;
    private String name;
    private String shortName;
    private volatile boolean paused;
    private Object loadedItem = null;
    private int currentLoadedIndex = -1;
    /**
     * The shares of a picked queue (current and next item) this player holds; null for every other
     * player. Set by the PlayerService before the items are set.
     */
    private QueueShareWindow.Lease shareLease;
    private boolean shareBindFailed;
    private int consecutiveBindFailures;
    /** After this many items in a row that cannot be shared the player stops (and says so once). */
    static final int MAX_CONSECUTIVE_BIND_FAILURES = 3;
    private Bitmap icon = null;
    private MediaSessionCompat mediaSession;
    // The repeat and shuffle mode of this player: starts with the settings, then belongs to the player.
    private volatile PlayMode playMode;
    private final PlayOrder playOrder = new PlayOrder(new Random());
    private final Object playModeLock = new Object();

    /**
     * @param upnpClient the upnpclient
     */
    public AbstractPlayer(UpnpClient upnpClient) {
        super();
        this.upnpClient = upnpClient;
        startService();
        // Initialize MediaSession on main thread
        new Handler(Looper.getMainLooper()).post(this::initMediaSession);
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(getContext(), "YaaccPlayer_" + getId());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                YaaccLogger.d(getClass().getName(), "MediaSession callback: onPlay() - isPlaying=" + isPlaying());
                AbstractPlayer.this.play();
            }

            @Override
            public void onPause() {
                YaaccLogger.d(getClass().getName(), "MediaSession callback: onPause() - isPlaying=" + isPlaying());
                AbstractPlayer.this.pause();
            }

            @Override
            public void onStop() {
                YaaccLogger.d(getClass().getName(), "MediaSession callback: onStop()");
                AbstractPlayer.this.stop();
            }

            @Override
            public void onSkipToNext() {
                YaaccLogger.d(getClass().getName(), "MediaSession callback: onSkipToNext()");
                AbstractPlayer.this.next();
            }

            @Override
            public void onSkipToPrevious() {
                YaaccLogger.d(getClass().getName(), "MediaSession callback: onSkipToPrevious()");
                AbstractPlayer.this.previous();
            }
        });
        mediaSession.setActive(true);

        // Allow subclasses to configure MediaSession (e.g., for remote volume)
        configureMediaSession(mediaSession);
    }

    /**
     * Override this to configure MediaSession (e.g., set volume provider for remote playback).
     */
    protected void configureMediaSession(MediaSessionCompat mediaSession) {
        // Default: local playback, no special configuration
    }

    /**
     * Get the MediaSession for this player.
     */
    public MediaSessionCompat getMediaSession() {
        return mediaSession;
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
            Intent intent = new Intent(upnpClient.getContext(), PlayerService.class);
            upnpClient.getContext().startForegroundService(intent);
            upnpClient.getContext().bindService(intent, this, Context.BIND_AUTO_CREATE);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#next()
     */
    @Override
    public void next() {
        advance(false);
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#onItemEnded()
     */
    @Override
    public void onItemEnded() {
        advance(true);
    }

    private void advance(boolean itemEnded) {
        if (isProcessingCommand()) {
            return;
        }
        setProcessingCommand(true);


        paused = false;
        previousIndex = currentIndex;
        cancelTimer();
        if (!moveToNext(itemEnded)) {
            // The command flag has to be free again, stop() ignores a call while a command runs
            setProcessingCommand(false);
            stop();
            return;
        }
        Context context = getUpnpClient().getContext();
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(() -> {
                Toast toast = Toast.makeText(getContext(), getContext()
                        .getResources().getString(R.string.next)
                        + getPositionString(), Toast.LENGTH_SHORT);

                toast.show();
            });
        }
        setProcessingCommand(false);
        play();

    }

    /**
     * Moves the current index to the item that follows as the play mode says.
     *
     * @param itemEnded true if the current item ended by itself (repeat one repeats it), false if somebody
     *                  skipped to the next one
     * @return false at the end of a list that is not repeated: the index is unchanged, the player has to stop
     */
    boolean moveToNext(boolean itemEnded) {
        playOrder.fit(items.size(), currentIndex);
        int target = playOrder.next(currentIndex, getPlayMode().getRepeat(), itemEnded);
        if (target == PlayOrder.NONE) {
            return false;
        }
        currentIndex = target;
        return true;
    }

    /**
     * Moves the current index to the previous item of the order; before the first one it goes to the
     * last (an empty list stays at index 0).
     */
    void moveToPrevious() {
        playOrder.fit(items.size(), currentIndex);
        int target = playOrder.previous(currentIndex);
        currentIndex = target == PlayOrder.NONE ? 0 : target;
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
        moveToPrevious();
        Context context = getUpnpClient().getContext();
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(() -> {
                Toast toast = Toast.makeText(getContext(), getContext()
                        .getResources().getString(R.string.previous)
                        + getPositionString(), Toast.LENGTH_SHORT);
                toast.show();
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
                    ((Activity) context).runOnUiThread(() -> {
                        Toast toast = Toast.makeText(getContext(), getContext()
                                .getResources().getString(R.string.pause)
                                + getPositionString(), Toast.LENGTH_SHORT);
                        toast.show();
                    });
                }
                setPlaying(false);
                paused = true;
                doPause();
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                updateMetadata();
                showNotification();
                setProcessingCommand(false);
            }
        }, new Date(System.currentTimeMillis()));
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

        executeCommand(new TimerTask() {
            @Override
            public void run() {
                // Load item asynchronously in background thread
                if (possibleNextIndex >= 0 && possibleNextIndex < items.size()) {
                    Object item = loadItem(possibleNextIndex);
                    if (item == null) {
                        // Item not ready yet, stop processing
                        setProcessingCommand(false);
                        if (shareBindFailed) {
                            skipUnsharedItem();
                        }
                        return;
                    }
                }

                if (currentIndex < items.size()) {
                    Context context = getUpnpClient().getContext();
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            Toast toast = Toast.makeText(getContext(), getContext()
                                    .getResources().getString(R.string.play)
                                    + getPositionString(), Toast.LENGTH_SHORT);
                            toast.show();
                        });
                    }
                    setPlaying(true);
                    if (paused) {
                        paused = false;  // Clear paused flag when resuming
                        doResume();
                    } else {
                        loadItem(previousIndex, currentIndex);
                    }
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    updateMetadata();
                    showNotification();
                    setProcessingCommand(false);
                }
            }
        }, new Date(System.currentTimeMillis()));
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
                currentIndex = restart();
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(() -> {
                        Toast toast = Toast.makeText(getContext(), getContext()
                                .getResources().getString(R.string.stop)
                                + getPositionString(), Toast.LENGTH_SHORT);
                        toast.show();
                    });
                }
                if (!items.isEmpty()) {
                    stopItem(items.get(currentIndex));
                }
                dropShares();
                setPlaying(false);
                paused = false;
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
                setProcessingCommand(false);
                // Whoever stopped it (the end of the playlist, the stop button, the media renderer): the media
                // renderer's transport leaves PLAYING when it hears this
                firePropertyChange(PROPERTY_STOPPED, null, null);
            }
        }, new Date(System.currentTimeMillis()));
    }

    /**
     * is shuffle play enabled.
     *
     * @return true, if shuffle play is enabled
     */

    protected boolean isShufflePlay() {
        return getPlayMode().isShuffle();
    }

    /**
     * The mode a new player starts with: the two settings "replay playlist" and "shuffle music". Only
     * read once per player (the first time the mode is asked).
     */
    protected PlayMode readDefaultPlayMode() {
        return PlayModeSettings.defaultPlayMode(getContext());
    }

    @Override
    public PlayMode getPlayMode() {
        PlayMode mode = playMode;
        if (mode == null) {
            synchronized (playModeLock) {
                if (playMode == null) {
                    playMode = readDefaultPlayMode();
                    YaaccLogger.d(getClass().getName(), "New player starts with the play mode of the settings: " + playMode);
                }
                mode = playMode;
            }
        }
        return mode;
    }

    @Override
    public void setPlayMode(PlayMode mode) {
        if (mode == null) {
            return;
        }
        PlayMode old = getPlayMode();
        if (old.equals(mode)) {
            return;
        }
        playMode = mode;
        playOrder.fit(items.size(), currentIndex);
        playOrder.setShuffle(mode.isShuffle(), currentIndex);
        YaaccLogger.d(getClass().getName(), "Play mode " + old + " -> " + mode);
        onPlayModeChanged(old, mode);
        firePropertyChange(PROPERTY_PLAY_MODE, old, mode);
    }

    /**
     * The engine of a player applies the new play mode here (the order of the items is already adapted:
     * see {@link #getPlayOrder()}). Called before the property change is fired, possibly on any thread.
     */
    protected void onPlayModeChanged(PlayMode oldMode, PlayMode newMode) {
        //default: the next item is decided by this class when the current one ended
    }

    /**
     * @return the order in which the items are played: the index of the item at each position, adapted to
     * the current playlist. The list order without shuffle.
     */
    @Override
    public int[] getPlayOrder() {
        playOrder.fit(items.size(), currentIndex);
        return playOrder.toArray();
    }

    /**
     * An item is part of what has been played, or plays, when the player is playing or paused; a stopped
     * player starts at the first item of the order, so everything can be edited.
     */
    private boolean isActive() {
        return isPlaying() || isPaused();
    }

    @Override
    public boolean isPlaylistPositionLocked(int position) {
        playOrder.fit(items.size(), currentIndex);
        return isActive() && playOrder.isAtOrBeforeCurrent(position, currentIndex);
    }

    /**
     * The index of the item a stopped player starts with: the first of the play order (0 without shuffle).
     */
    private int startIndex() {
        return items.isEmpty() ? 0 : Math.max(0, playOrder.first());
    }

    @Override
    public boolean moveInPlayOrder(int fromPosition, int toPosition) {
        int size = items.size();
        if (fromPosition == toPosition || fromPosition < 0 || toPosition < 0
                || fromPosition >= size || toPosition >= size) {
            return false;
        }
        if (isPlaylistPositionLocked(fromPosition) || isPlaylistPositionLocked(toPosition)) {
            return false;
        }
        if (playOrder.isShuffle()) {
            // The list keeps its order, only the order the items play in changes
            if (!playOrder.move(fromPosition, toPosition)) {
                return false;
            }
            if (!isActive()) {
                currentIndex = startIndex();
            }
            onPlayOrderMoved();
        } else {
            // Without shuffle the position is the index. Only items behind the current one move while the
            // player is active, so the current index stays; a stopped player starts with the first one
            items.add(toPosition, items.remove(fromPosition));
            currentLoadedIndex = -1;
            loadedItem = null;
            if (!isActive()) {
                currentIndex = startIndex();
            }
            onItemMoved(fromPosition, toPosition);
        }
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
        return true;
    }

    @Override
    public boolean removeItem(int itemIndex) {
        return removeItem(itemIndex, false);
    }

    /**
     * Removes an item. The playlist screen only removes what still lies ahead; the OpenHome queue may drop what has been played
     * as well ({@code alsoPlayed}), but never the item that plays now.
     *
     * @return false (and nothing changed) if the item is not in the list or is locked
     */
    public boolean removeItem(int itemIndex, boolean alsoPlayed) {
        if (itemIndex < 0 || itemIndex >= items.size()) {
            return false;
        }
        playOrder.fit(items.size(), currentIndex);
        int position = playOrder.positionOf(itemIndex);
        if (position < 0) {
            return false;
        }
        boolean locked = alsoPlayed ? isActive() && itemIndex == currentIndex : isPlaylistPositionLocked(position);
        if (locked) {
            return false;
        }
        boolean active = isActive();
        items.remove(itemIndex);
        playOrder.remove(itemIndex);
        // The indexes behind the removed item moved down by one, so the cached loaded item is not the one
        // of its index any more
        currentLoadedIndex = -1;
        loadedItem = null;
        if (previousIndex > itemIndex) {
            previousIndex--;
        }
        previousIndex = Math.max(0, Math.min(previousIndex, items.size() - 1));
        if (!active) {
            currentIndex = startIndex();
        } else if (itemIndex < currentIndex) {
            // With shuffle an item can come after the current one in the order and before it in the list
            currentIndex--;
        }
        onItemRemoved(itemIndex);
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
        return true;
    }

    /**
     * The list order changed: the item at {@code fromIndex} moved to {@code toIndex} (shuffle is off).
     * For an engine that holds the list too. Called on the caller's thread, before the property change.
     */
    protected void onItemMoved(int fromIndex, int toIndex) {
        //default: the engine plays one item at a time
    }

    /**
     * The item at {@code index} was removed from the list and the play order. Called on the caller's
     * thread, before the property change.
     */
    protected void onItemRemoved(int index) {
        //default: the engine plays one item at a time
    }

    /**
     * The play order changed by a move (shuffle is on, the list is unchanged); read it with {@link
     * #getPlayOrder()}. Called on the caller's thread, before the property change.
     */
    protected void onPlayOrderMoved() {
        //default: the next item is decided by this class when the current one ended
    }

    /**
     * Creates the play order of the playlist again with the given item first (with shuffle a new random
     * order, otherwise the list order). For an engine that follows {@link #getPlayOrder()} and starts
     * playing at the item.
     */
    protected void resetPlayOrder(int firstIndex) {
        playOrder.reset(items.size(), firstIndex, getPlayMode().isShuffle());
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
    }

    /**
     * Creates the play order again for playing from the beginning.
     *
     * @return the index of the item that starts: the first of the order, which is 0 without shuffle and a
     * random item with shuffle
     */
    private int restart() {
        boolean shuffle = getPlayMode().isShuffle();
        playOrder.reset(items.size(), PlayOrder.NONE, shuffle);
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
        return shuffle && !items.isEmpty() ? playOrder.first() : 0;
    }

    /**
     * @return the index of the item that plays after the current one in the play order, {@link
     * PlayOrder#NONE} at the end of the list (also with repeat all: the wrap around is not shown)
     */
    public int getNextItemIndex() {
        playOrder.fit(items.size(), currentIndex);
        return playOrder.following(currentIndex);
    }

    /**
     * @return the index of the item before the current one in the play order, {@link PlayOrder#NONE}
     * before the first one
     */
    public int getPreviousItemIndex() {
        playOrder.fit(items.size(), currentIndex);
        return playOrder.preceding(currentIndex);
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#clear()
     */
    @Override
    public void clear() {
        items.clear();
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
    }

    protected void cancelTimer() {

        if (alarmIntent != null) {
            AlarmManager alarmManager =
                    (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(alarmIntent);
            }
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isPaused() {
        return paused;
    }

    protected void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isStopped() {
        return !(isPlaying || paused);
    }

    public void setPlaying(boolean isPlaying) {
        boolean wasPlaying = this.isPlaying;
        this.isPlaying = isPlaying;

        // Notify service of state change for foreground management
        if (wasPlaying != isPlaying) {
            firePropertyChange("playing", wasPlaying, isPlaying);
        }
    }


    protected void setCurrentIndex(int currentIndex) {
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
        boolean wasPlaying = isPlaying();
        cancelTimer(); // Cancel any pending auto-advance timer
        items.clear(); // Clear existing items before adding new ones
        items.addAll(Arrays.asList(playableItems));
        if (isShufflePlay() && !wasPlaying) {
            // A new shuffled playlist starts with a random item, the others follow in random order
            playOrder.reset(items.size(), PlayOrder.NONE, true);
            if (!items.isEmpty()) {
                currentIndex = playOrder.first();
            }
        } else {
            playOrder.reset(items.size(), Math.min(currentIndex, items.size() - 1), isShufflePlay());
        }
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
        showNotification();

        // Restart timer if we were playing
        if (wasPlaying && items.size() > 1) {
            updateTimer();
        }
    }

    @Override
    public void addItems(List<PlayableItem> playableItems) {
        items.addAll(playableItems);
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
    }

    /**
     * For a player whose playlist and playback live on a device (OpenHome): takes over what the device reports. The list, the index of
     * the item that plays and the state are replaced as they are, the screen and the notification are told what changed. Nothing is
     * started or stopped. Call it from one thread only (the player's worker); the state it sets is volatile for the screen.
     *
     * @param deviceItems the playlist of the device; the same objects for the same tracks as before keep the list "unchanged"
     * @param index       the index of the current item (clamped to the list)
     */
    protected final void adoptDeviceState(List<PlayableItem> deviceItems, int index, boolean nowPlaying, boolean nowPaused) {
        boolean listChanged = deviceItems.size() != items.size();
        for (int i = 0; !listChanged && i < deviceItems.size(); i++) {
            listChanged = deviceItems.get(i) != items.get(i);
        }
        int newIndex = deviceItems.isEmpty() ? 0 : Math.max(0, Math.min(index, deviceItems.size() - 1));
        boolean indexChanged = newIndex != currentIndex;
        boolean stateChanged = nowPlaying != isPlaying || nowPaused != paused;
        PlayableItem oldItem = currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
        if (listChanged) {
            items = new CopyOnWriteArrayList<>(deviceItems);
            playOrder.reset(items.size(), newIndex, false);
        }
        previousIndex = currentIndex;
        currentIndex = newIndex;
        paused = nowPaused;
        setPlaying(nowPlaying);
        if (listChanged) {
            firePropertyChange(PROPERTY_PLAYLIST, null, null);
        }
        if (indexChanged && !items.isEmpty()) {
            firePropertyChange(PROPERTY_ITEM, oldItem, items.get(newIndex));
        }
        if (listChanged || indexChanged || stateChanged) {
            updatePlaybackState(nowPlaying ? PlaybackStateCompat.STATE_PLAYING
                    : nowPaused ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_STOPPED);
            updateMetadata();
            showNotification();
        }
    }

    /**
     * Puts items into the middle of the playlist (the OpenHome renderer's queue): the item that plays keeps playing, the play
     * order places the new items (behind the current one with shuffle), an engine that holds the list too is told.
     *
     * @param index         where the first new item goes; clamped to the list
     * @param playableItems the new items
     */
    public void insertItems(int index, List<PlayableItem> playableItems) {
        if (playableItems == null || playableItems.isEmpty()) {
            return;
        }
        int at = Math.max(0, Math.min(index, items.size()));
        int count = playableItems.size();
        boolean hadItems = !items.isEmpty();
        playOrder.fit(items.size(), currentIndex);
        playOrder.insert(at, count, currentIndex);
        items.addAll(at, playableItems);
        if (hadItems && at <= currentIndex) {
            currentIndex += count;
        }
        if (hadItems && at <= previousIndex) {
            previousIndex += count;
        }
        // the indexes behind the insert point moved, so the cached loaded item is not the one of its index any more
        currentLoadedIndex = -1;
        loadedItem = null;
        onItemsInserted(at, new ArrayList<>(playableItems));
        firePropertyChange(PROPERTY_PLAYLIST, null, null);
    }

    /**
     * Items were put into the list at {@code index}. For an engine that holds the list too. Called on the caller's thread, after
     * the list and the play order changed and before the property change.
     */
    protected void onItemsInserted(int index, List<PlayableItem> inserted) {
        // default: the engine plays one item at a time
    }

    /**
     * Plays the item at the index (a playing, paused or stopped player starts it). Nothing happens for an index outside the list.
     */
    public void playItemAt(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        if (isProcessingCommand()) {
            return;
        }
        paused = false;
        previousIndex = currentIndex;
        cancelTimer();
        playOrder.fit(items.size(), currentIndex);
        currentIndex = index;
        play();
    }

    /**
     * returns the current item position in the playlist
     *
     * @return the position string
     */
    public String getPositionString() {
        return " (" + (currentIndex + 1) + "/" + items.size() + ")";
    }

    public int getCurrentItemIndex() {
        return currentIndex;
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
        int next = getNextItemIndex();
        if (next >= 0 && next < items.size()) {

            result = items.get(next).getTitle();
        }
        return result;
    }


    /**
     * Makes this player hold the shares of a picked queue just in time: from now on loading an item
     * binds the share of that item and of the next one and releases what the player left behind.
     * Call before {@link #setItems}; null (the default) leaves the items as they are.
     */
    public void setShareLease(QueueShareWindow.Lease lease) {
        this.shareLease = lease;
    }

    /** Releases the shares this player holds (the queue ended or was stopped); it can start again. */
    private void dropShares() {
        QueueShareWindow.Lease lease = shareLease;
        consecutiveBindFailures = 0;
        if (lease != null) {
            lease.drop();
        }
    }

    /** The player is gone: releases the shares for good. Never waits for a share that is being created. */
    void closeShares() {
        QueueShareWindow.Lease lease = shareLease;
        if (lease != null) {
            lease.close();
        }
    }

    /**
     * The share of the current item could not be created (file gone, registry full): go on with the
     * next item; when every item of the list failed one after the other, stop.
     */
    private void skipUnsharedItem() {
        consecutiveBindFailures++;
        if (consecutiveBindFailures >= MAX_CONSECUTIVE_BIND_FAILURES || consecutiveBindFailures >= items.size()) {
            // a registry that is full or a provider that is gone: do not run through the whole list
            consecutiveBindFailures = 0;
            toastOnMainThread(R.string.pick_play_failed);
            stop();
        } else {
            onItemEnded();
        }
    }

    private void toastOnMainThread(int textRes) {
        try {
            Context context = getContext();
            if (context != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> Toast.makeText(context.getApplicationContext(), textRes, Toast.LENGTH_LONG).show());
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Could not show a message (" + e.getClass().getSimpleName() + ")");
        }
    }

    protected Object loadItem(int toLoadIndex) {
        if (toLoadIndex == currentLoadedIndex && loadedItem != null) {
            YaaccLogger.d(getClass().getName(), "returning already loaded item");
            return loadedItem;
        }
        if (toLoadIndex >= 0 && toLoadIndex <= items.size()) {
            if (shareLease != null && toLoadIndex < items.size()) {
                // a picked queue: the file of this item is shared only now, found by the item itself
                // (the playlist may have been edited: positions say nothing about the queue)
                boolean shared = !shareLease.isClosed() && shareLease.moveToItems(items.get(toLoadIndex), null);
                if (!shared) {
                    // a player that was destroyed meanwhile is no failure of the item: nothing to skip or report
                    shareBindFailed = !shareLease.isClosed();
                    if (shareBindFailed) {
                        YaaccLogger.w(getClass().getName(), "The share of an item could not be created");
                    }
                    currentLoadedIndex = -1;
                    loadedItem = null;
                    return null;
                }
                shareBindFailed = false;
                consecutiveBindFailures = 0;
            }
            YaaccLogger.d(getClass().getName(), "loaded item");
            currentLoadedIndex = toLoadIndex;

            PlayableItem playableItem = items.get(toLoadIndex);

            loadedItem = loadItem(playableItem);
            return loadedItem;
        }
        return null;
    }

    protected void loadItem(int previousIndex, int nextIndex) {
        if (items.isEmpty())
            return;
        PlayableItem playableItem = items.get(nextIndex);
        Object loadedItem = loadItem(nextIndex);
        firePropertyChange(PROPERTY_ITEM, items.get(previousIndex),
                items.get(nextIndex));
        startItem(playableItem, loadedItem, nextIndex);
        doPostLoadItem(playableItem);
    }

    protected void doPostLoadItem(PlayableItem playableItem) {
        // Repeat one needs the timer for a single item too: it plays that item again
        if (isPlaying() && (items.size() > 1 || getPlayMode().getRepeat() == PlayMode.RepeatMode.ONE)) {
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

    protected void updateTimer() {
        long remainingTime = getRemainingTime();
        remainingTime += getSilenceDuration();
        if (remainingTime > -1) {
            startTimer(remainingTime);
        }
    }

    protected long parseTimeStringToMillis(String timeString) {
        //HH:MM:SS
        long millis = -1;
        if (timeString != null) {
            try {
                String[] tokens = timeString.split(":");
                if (tokens.length > 0) {
                    millis = Long.parseLong(tokens[0]) * 3600;
                }
                if (tokens.length > 1) {
                    millis += Long.parseLong(tokens[1]) * 60;
                }
                if (tokens.length > 2) {
                    String seconds = tokens[2];
                    if (tokens[2].contains(".")) {
                        seconds = tokens[2].split("\\.")[0];
                    }
                    millis += Long.parseLong(seconds);
                }
                millis = millis * 1000;
            } catch (Exception e) {
                YaaccLogger.d(getClass().getName(), "ignoring error on parsing to millis of:" + timeString, e);
            }
        }
        YaaccLogger.v(getClass().getName(), "parsing time string" + timeString + " result millis:" + millis);
        return millis;
    }


    public long getRemainingTime() {
        return parseTimeStringToMillis(getDuration()) - parseTimeStringToMillis(getElapsedTime());
    }

    /**
     * Start a timer for the next item change
     *
     * @param duration in millis
     */
    public void startTimer(final long duration) {
        YaaccLogger.d(getClass().getName(), "Start timer duration: " + duration);
        cancelTimer();
        Intent intent = new Intent();
        intent.setAction(PlayerServiceBroadcastReceiver.ACTION_NEXT);
        intent.putExtra(PLAYER_ID, getId());
        ContextCompat.getMainExecutor(getContext()).execute(() -> {
            AlarmManager alarmManager =
                    (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            alarmIntent = PendingIntent.getBroadcast(getContext(), intent.hashCode(), intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + duration,
                            alarmIntent
                    );
                    YaaccLogger.d(getClass().getName(), "ExactAndAllowWhileIdle alarm event in: " + (System.currentTimeMillis() + duration));
                } else {
                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + duration,
                            alarmIntent
                    );
                    YaaccLogger.d(getClass().getName(), "AndAllowWhileIdle alarm event in: " + (System.currentTimeMillis() + duration));
                }
            } else {
                alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + duration,
                        alarmIntent
                );
                YaaccLogger.d(getClass().getName(), "exact alarm event in: " + (System.currentTimeMillis() + duration));
            }
        });

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
        YaaccLogger.d(getClass().getName(), "Player.exit() called for player: " + getId());
        if (isPlaying()) {
            stop();
        }
        playerService.shutdown(this);
    }

    /**
     * Displays the notification.
     */
    private void showNotification() {
        showNotificationInternal();
    }

    protected void showNotificationInternal() {
        // Run on background thread to avoid blocking UI
        new Thread(() -> showNotificationWithRetry(3)).start();
    }

    private void showNotificationWithRetry(int retryCount) {
        // If MediaSession not ready yet, retry after delay (max 10 times = 2 seconds)
        if (mediaSession == null && retryCount < 10) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // Ignore
            }
            showNotificationWithRetry(retryCount + 1);
            return;
        }

        if (mediaSession == null) {
            YaaccLogger.w(getClass().getName(), "MediaSession not ready after retries, skipping notification");
            return;
        }

        ((Yaacc) getContext().getApplicationContext()).createYaaccGroupNotification();

        // Create media style notification with controls
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                getContext(), Yaacc.NOTIFICATION_CHANNEL_ID).setOngoing(false)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setSilent(true)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setLargeIcon(getIcon())
                .setContentTitle("Yaacc player")
                .setContentText(getShortName() == null ? "" : getShortName());

        // Add progress bar if duration is available
        try {
            String durationStr = getDuration();
            if (durationStr != null && !durationStr.isEmpty()) {
                // Parse duration string (format: "HH:MM:SS" or "MM:SS")
                String[] parts = durationStr.split(":");
                long durationMs = 0;
                if (parts.length == 3) {
                    durationMs = (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
                } else if (parts.length == 2) {
                    durationMs = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
                }
                if (durationMs > 0) {
                    long position = getCurrentPosition();
                    mBuilder.setProgress((int) durationMs, (int) position, false);
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors, just don't show progress
        }

        // Add media controls if MediaSession is ready
        if (mediaSession != null) {
            mBuilder.setStyle(new MediaStyle()
                            .setMediaSession(mediaSession.getSessionToken())
                            .setShowActionsInCompactView(0, 1, 2))
                    .addAction(createNotificationAction(R.drawable.ic_baseline_skip_previous_24, "Previous",
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
                    .addAction(createNotificationAction(
                            isPlaying() ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24,
                            isPlaying() ? "Pause" : "Play",
                            isPlaying() ? PlaybackStateCompat.ACTION_PAUSE : PlaybackStateCompat.ACTION_PLAY))
                    .addAction(createNotificationAction(R.drawable.ic_baseline_skip_next_24, "Next",
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
        }

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
    private void cancelNotification() {
        NotificationManager mNotificationManager = (NotificationManager) getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        YaaccLogger.d(getClass().getName(), "Cancel Notification with ID: " + getNotificationId());
        mNotificationManager.cancel(getNotificationId());
        ((Yaacc) getContext().getApplicationContext()).cancelYaaccGroupNotification();

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }

    private NotificationCompat.Action createNotificationAction(int iconRes, String title, long action) {
        Drawable drawable = ContextCompat.getDrawable(getContext(), iconRes);
        IconCompat icon;

        if (drawable != null) {
            drawable = ThemeHelper.tintDrawable(drawable, getContext().getTheme());
            Bitmap bitmap = Bitmap.createBitmap(
                    drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            icon = IconCompat.createWithBitmap(bitmap);
        } else {
            icon = IconCompat.createWithResource(getContext(), iconRes);
        }

        return new NotificationCompat.Action.Builder(icon, title, createMediaAction(action)).build();
    }

    private PendingIntent createMediaAction(long action) {
        Intent intent = new Intent(getContext(), MediaButtonReceiver.class);
        intent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getBroadcast(getContext(), (int) action, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updatePlaybackState(int state) {
        new Handler(Looper.getMainLooper()).post(() -> this.updatePlaybackStateInternal(state));
    }

    protected void updatePlaybackStateInternal(int state) {
        if (mediaSession == null) return;

        long position = getCurrentPosition();

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_STOP |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, position, 1.0f);

        if (mediaSession != null) {
            mediaSession.setPlaybackState(stateBuilder.build());
        }
    }

    private void updateMetadata() {
        updateMetadataInternal();
    }

    protected void updateMetadataInternal() {
        if (mediaSession == null) return;

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getCurrentItemTitle() != null ? getCurrentItemTitle() : "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getName() != null ? getName() : "");

        // Add duration if available
        try {
            String durationStr = getDuration();
            if (durationStr != null && !durationStr.isEmpty()) {
                String[] parts = durationStr.split(":");
                long durationMs = 0;
                if (parts.length == 3) {
                    durationMs = (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
                } else if (parts.length == 2) {
                    durationMs = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
                }
                if (durationMs > 0) {
                    metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }

        if (getIcon() != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, getIcon());
        }

        mediaSession.setMetadata(metadataBuilder.build());
    }

    /**
     * Returns the notification id of the player
     *
     * @return the notification id
     */
    abstract protected int getNotificationId();

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
                                      Object loadedItem, int index);

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#onDestroy()
     */
    @Override
    public void onDestroy() {
        stop();
        closeShares();
        cancelNotification();
        items.clear();

        // Remove player from service
        if (playerService != null) {
            playerService.removePlayer(this);
            try {
                playerService.unbindService(this);
            } catch (IllegalArgumentException iex) {
                YaaccLogger.d(getClass().getName(), "Exception while unbind service");
            }
        }

        // Release MediaSession
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
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


    public abstract long getCurrentPosition();

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

        return R.drawable.yaacc48_24_png;
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

    public boolean hasActionGetVolume() {
        return true;
    }

    public boolean hasActionGetMute() {
        return true;
    }

    @Override
    public void fastForward(int i) {

        seekTo(getCurrentPosition() + (i * 1000L));
    }

    @Override
    public void fastRewind(int i) {
        seekTo(getCurrentPosition() - (i * 1000L));
    }

}
