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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;

import java.net.URI;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;

/**
 * @author Tobias Schoene (openbit)
 */

public class MultiContentPlayer extends AbstractPlayer {


    /**
     * @param upnpClient the upnpclient
     * @param name       playerName
     */
    public MultiContentPlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
    }

    /**
     * @param upnpClient the upnpclient
     */
    public MultiContentPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        // TODO Auto-generated constructor stub
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * de.yaacc.player.AbstractPlayer#stopItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected void stopItem(PlayableItem playableItem) {
        Log.d(getClass().getName(), "Stop not implemented for multi player");

    }

    /*
     * (non-Javadoc)
     *
     * @see
     * de.yaacc.player.AbstractPlayer#loadItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected Object loadItem(PlayableItem playableItem) {
        // DO nothing special
        return null;
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
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.setDataAndType(playableItem.getUri(), playableItem.getMimeType());
        try {
            getContext().startActivity(intent);
        } catch (final ActivityNotFoundException anfe) {
            Log.e(getClass().getName(), R.string.can_not_start_activity
                    + anfe.getMessage(), anfe);
        }

    }


    @Override
    public URI getAlbumArt() {
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
     */
    @Override
    public PendingIntent getNotificationIntent() {
        Intent notificationIntent = new Intent(getContext(),
                MultiContentPlayerActivity.class);
        return PendingIntent.getActivity(getContext(),
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.AbstractPlayer#getNotificationId()
     */
    @Override
    protected int getNotificationId() {

        return NotificationId.MULTI_CONTENT_PLAYER.getId();
    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        Log.d(getClass().getName(), "SeekTo not implemented");
    }


}
