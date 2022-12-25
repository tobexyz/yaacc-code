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
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/
package de.yaacc.upnp.server.avtransport;

import android.util.Log;

import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.PausedPlay;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;

import java.net.URI;
import java.util.List;

import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
/**
 * State Paused.
 * @author Tobias Schoene (openbit)
 *
 */
public class AvTransportMediaRendererPaused extends PausedPlay<AvTransport> implements YaaccState{
    private UpnpClient upnpClient;
    /**
     * Constructor.
     *
     * @param transport
     * the state holder
     * @param upnpClient
     * the upnpclient to use
     */
    public AvTransportMediaRendererPaused(AvTransport transport,
                                          UpnpClient upnpClient) {
        super(transport);
        this.upnpClient = upnpClient;
    }
    /* (non-Javadoc)
    * @see org.fourthline.cling.support.avtransport.impl.state.PausedPlay#play(java.lang.String)
    */
    @Override
    public Class<? extends AbstractState<?>> play(String arg0) {
        Log.d(this.getClass().getName(), "play");
        return AvTransportMediaRendererPlaying.class;
    }
    /* (non-Javadoc)
    * @see org.fourthline.cling.support.avtransport.impl.state.PausedPlay#setTransportURI(java.net.URI, java.lang.String)
    */
    @Override
    public Class<? extends AbstractState<?>> setTransportURI(URI uri, String metaData) {
        Log.d(this.getClass().getName(), "setTransportURI");
        Log.d(this.getClass().getName(), "uri: " + uri);
        Log.d(this.getClass().getName(), "metaData: " + metaData);
        getTransport().setMediaInfo(new MediaInfo(uri.toString(), metaData));
// If you can, you should find and set the duration of the track here!
        getTransport().setPositionInfo(
                new PositionInfo(1, metaData, uri.toString()));
// It's up to you what "last changes" you want to announce to event
// listeners
        getTransport().getLastChange().setEventedValue(
                getTransport().getInstanceId(),
                new AVTransportVariable.AVTransportURI(uri),
                new AVTransportVariable.CurrentTrackURI(uri));
// This operation can be triggered in any state, you should think
// about how you'd want your player to react. If we are in Stopped
// state nothing much will happen, except that you have to set
// the media and position info, just like in MyRendererNoMediaPresent.
// However, if this would be the MyRendererPlaying state, would you
// prefer stopping first?
        return AvTransportMediaRendererStopped.class;
    }
    /* (non-Javadoc)
    * @see org.fourthline.cling.support.avtransport.impl.state.PausedPlay#stop()
    */
    @Override
    public Class<? extends AbstractState<?>> stop() {
        Log.d(this.getClass().getName(), "stop");
        return AvTransportMediaRendererStopped.class;
    }
    /*
    * (non-Javadoc)
    * @see org.fourthline.cling.support.avtransport.impl.state.Playing#onEntry()
    */
    @Override
    public void onEntry() {
        Log.d(this.getClass().getName(), "On Entry");
        super.onEntry();
        List<Player> players = upnpClient.getCurrentPlayers((AvTransport)getTransport());
        for (Player player : players) {
            if(player != null ){
                player.pause();
            }
        }
    }

    @Override
    public Class<? extends AbstractState<?>>  syncPlay(String speed, String referencedPositionUnits, String referencedPosition, String referencedPresentationTime, String referencedClockId) {
        ((AvTransport)getTransport()).getSynchronizationInfo().setSpeed(speed);
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedPositionUnits(referencedPositionUnits);
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedPosition(referencedPosition);
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedPresentationTime(referencedPresentationTime);
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedClockId(referencedClockId);
        return AvTransportMediaRendererPlaying.class;
    }

    @Override
    public Class<? extends AbstractState<?>>  syncPause(String referencedPresentationTime, String referencedClockId) {
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedPresentationTime(referencedPresentationTime);
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedClockId(referencedClockId);
        return AvTransportMediaRendererPaused.class;
    }

    @Override
    public Class<? extends AbstractState<?>>  syncStop(String referencedPresentationTime, String referencedClockId) {
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedPresentationTime(referencedPresentationTime);
        ((AvTransport)getTransport()).getSynchronizationInfo().setReferencedClockId(referencedClockId);
        return AvTransportMediaRendererStopped.class;
    }
    @Override
    public TransportAction[] getPossibleTransportActions(){
        return new TransportAction[] {
                TransportAction.Stop,
                TransportAction.Play,
                TransportAction.Next,
                TransportAction.Previous,
                TransportAction.Seek,
                TransportAction.SyncPause,
                TransportAction.SyncPlay,
                TransportAction.SyncStop
        };
    }
} 