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
package de.yaacc.upnp.server.avtransport;

import java.net.URI;

import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.NoMediaPresent;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;

import android.util.Log;
import de.yaacc.upnp.UpnpClient;

/**
 * @author Tobias Schöne (openbit)
 * 
 */
public class AvTransportMediaRendererNoMediaPresent extends
		NoMediaPresent<AvTransport> implements YaaccState {

	private UpnpClient upnpClient;

	/**
	 * Constructor.
	 * 
	 * @param transport
	 *            the state holder
	 * @param upnpClient
	 *            the upnpClient to use
	 */
	public AvTransportMediaRendererNoMediaPresent(AvTransport transport,
			UpnpClient upnpClient) {
		super(transport);
		this.upnpClient = upnpClient;
	}

	/*
	 * (non-Javadoc)
	 * @see org.fourthline.cling.support.avtransport.impl.state.NoMediaPresent#setTransportURI(java.net.URI, java.lang.String)
	 */
	@Override
	public Class<? extends AbstractState<?>> setTransportURI(URI uri,
			String metaData) {
		Log.d(this.getClass().getName(), "set Transport: " + uri + " metaData: " + metaData);
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

		return AvTransportMediaRendererStopped.class;
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
