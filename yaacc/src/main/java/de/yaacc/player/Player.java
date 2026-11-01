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
import android.graphics.Bitmap;
import android.support.v4.media.session.MediaSessionCompat;

import java.beans.PropertyChangeListener;
import java.net.URI;
import java.util.List;


/**
 * A Player is able to play stop a couple of MediaObjects
 *
 * @author Tobias Schoene (openbit)
 */
public interface Player {

    /**
     * Name of the property change fired (see {@link #addPropertyChangeListener}) when the play mode
     * changed; old and new value are {@link PlayMode}s.
     */
    String PROPERTY_PLAY_MODE = "playMode";

    /**
     * Name of the property change fired when the playlist changed: items set, added, removed or moved, or
     * the play order created again (a stop, a start). Old and new value are null; read the playlist again.
     */
    String PROPERTY_PLAYLIST = "playlist";

    /**
     * play the next item
     */
    void next();

    /**
     * play the previous item
     */
    void previous();

    /**
     * The current item has ended by itself (a timer, or the renderer reported it): go on as the play
     * mode says, which unlike {@link #next()} repeats the item with repeat one. The default is
     * {@link #next()}.
     */
    default void onItemEnded() {
        next();
    }

    /**
     * Pause the current item
     */
    void pause();

    /**
     * start playing the current item
     */
    void play();

    /**
     * stops playing. And returns to the beginning of the item list.
     */
    void stop();

    /**
     * Set a List of Items
     *
     * @param items the items to be played
     */
    void setItems(PlayableItem... items);

    /**
     * adds additional item to the end of the playlist
     *
     * @param items the items to be added
     */
    void addItems(List<PlayableItem> items);

    /**
     * Drops all Items.
     */
    void clear();

    /**
     * Kill the  player.
     */
    void onDestroy();

    /**
     * Get the player name.
     *
     * @return the name
     */
    String getName();

    /**
     * Set the name of the player.
     *
     * @param name the name
     */
    void setName(String name);

    /**
     * Get the player short name.
     *
     * @return the name
     */
    String getShortName();

    /**
     * Set the short name of the player.
     *
     * @param name the name
     */
    void setShortName(String name);

    /**
     * Exit the player.
     */
    void exit();

    /**
     * Returns the id of the Player.
     *
     * @return the id
     */
    int getId();


    /**
     * add a property change listener
     *
     * @param listener the listener
     */
    void addPropertyChangeListener(PropertyChangeListener listener);

    /**
     * remove a property change listener
     *
     * @param listener the listener
     */
    void removePropertyChangeListener(PropertyChangeListener listener);

    /**
     * returns the current item position in the playlist
     *
     * @return the position string
     */
    String getPositionString();

    /**
     * returns the title of the current item
     *
     * @return the title
     */
    String getCurrentItemTitle();

    int getCurrentItemIndex();

    boolean isPlaying();


    /**
     * returns the title of the next item
     *
     * @return the title
     */
    String getNextItemTitle();

    /**
     * returns the duration of the current item
     *
     * @return the duration
     */
    String getDuration();

    /**
     * returns the current position of the current item in millis
     *
     * @return the position
     */
    long getCurrentPosition();

    /**
     * returns the elapsed time of the current item
     *
     * @return the elapsed time
     */
    String getElapsedTime();

    /**
     *
     */
    URI getAlbumArt();

    Bitmap getIcon();

    void setIcon(Bitmap icon);


    boolean getMute();


    void setMute(boolean mute);

    int getVolume();

    boolean hasActionGetVolume();

    boolean hasActionGetMute();

    void setVolume(int volume);

    int getIconResourceId();

    String getDeviceId();

    /**
     * Returns the intent which is to be started by pushing the notification
     * entry
     *
     * @return the peneding intent
     */
    PendingIntent getNotificationIntent();

    void seekTo(long millisecondsFromStart);

    List<PlayableItem> getItems();

    /**
     * fast forward seconds
     *
     * @param i seconds
     */
    void fastForward(int i);

    /**
     * fast rewind seconds
     *
     * @param i seconds
     */
    void fastRewind(int i);

    /**
     * Get the MediaSession for this player (for volume control integration)
     */
    MediaSessionCompat getMediaSession();

    boolean isPaused();

    boolean isStopped();

    /**
     * The repeat and shuffle mode of this player. It starts with the mode the settings "replay playlist"
     * and "shuffle music" give a new player and then belongs to the player only: it is never written back
     * to the settings and ends with the player.
     *
     * @return the mode, never null
     */
    PlayMode getPlayMode();

    /**
     * Changes the repeat and shuffle mode of this player; the current item goes on playing. Fires
     * {@link #PROPERTY_PLAY_MODE} if the mode differs.
     *
     * @param mode the new mode, null is ignored
     */
    void setPlayMode(PlayMode mode);

    /**
     * The order in which the items of the playlist play (with shuffle the shuffled order, otherwise the
     * list order), as the index into {@link #getItems()} of the item at each position. The playlist
     * screen shows the items in this order.
     *
     * @return a copy, one entry per item
     */
    default int[] getPlayOrder() {
        int[] order = new int[getItems().size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        return order;
    }

    /**
     * Moves an item in the playlist as the screen shows it: with shuffle the item moves in the play order
     * (the list is not touched), otherwise in the list. The positions are positions of
     * {@link #getPlayOrder()}. Fires {@link #PROPERTY_PLAYLIST}.
     *
     * @return false if nothing moved: the positions are invalid or locked
     */
    default boolean moveInPlayOrder(int fromPosition, int toPosition) {
        return false;
    }

    /**
     * Removes an item from the playlist (and from the play order).
     *
     * @param itemIndex the index into {@link #getItems()}
     * @return false if nothing was removed: the index is invalid or the item is locked
     */
    default boolean removeItem(int itemIndex) {
        return false;
    }

    /**
     * @param position a position of {@link #getPlayOrder()}
     * @return true if the item at the position cannot be edited: it plays (or is paused) or came before
     * the one that plays
     */
    default boolean isPlaylistPositionLocked(int position) {
        return false;
    }
}
