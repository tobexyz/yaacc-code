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

import java.beans.PropertyChangeListener;
import java.net.URI;

import de.yaacc.upnp.SynchronizationInfo;


/**
 * A Player is able to play stop a couple of MediaObjects
 *
 * @author Tobias Schoene (openbit)
 */
public interface Player {

    /**
     * play the next item
     */
    void next();

    /**
     * play the previous item
     */
    void previous();

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
     * @param listener
     */
    void addPropertyChangeListener(PropertyChangeListener listener);

    /**
     * remove a property change listener
     *
     * @param listener
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

    /**
     * Get the synchronization information
     *
     * @return the info object
     */
    SynchronizationInfo getSyncInfo();

    /**
     * Set the synchronization information
     *
     * @param syncInfo the info object
     */
    void setSyncInfo(SynchronizationInfo syncInfo);

    boolean getMute();


    void setMute(boolean mute);

    public int getVolume();

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
}
