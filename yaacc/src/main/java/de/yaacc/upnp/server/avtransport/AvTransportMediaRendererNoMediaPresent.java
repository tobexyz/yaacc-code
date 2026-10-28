/*
 *
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
package de.yaacc.upnp.server.avtransport;

import de.yaacc.share.ShareLogRedactor;
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.NoMediaPresent;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;

import java.net.URI;

import de.yaacc.upnp.UpnpClient;

/**
 * @author Tobias Schöne (openbit)
 */
public class AvTransportMediaRendererNoMediaPresent extends
        NoMediaPresent<AvTransport> implements YaaccState {


    /**
     * Constructor.
     *
     * @param transport  the state holder
     */
    public AvTransportMediaRendererNoMediaPresent(AvTransport transport) {
        super(transport);
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.NoMediaPresent#setTransportURI(java.net.URI, java.lang.String)
     */
    @Override
    public Class<? extends AbstractState<?>> setTransportURI(URI uri,
                                                             String metaData) {
        YaaccLogger.d(this.getClass().getName(), "set Transport: " + ShareLogRedactor.redact(String.valueOf(uri)) + " metaData: " + ShareLogRedactor.redact(String.valueOf(metaData)));
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

        return AvTransportMediaRendererPlaying.class;
    }

    /**
     * Control points such as Home Assistant send {@code Stop} when they switch a player off, also when
     * nothing is loaded. There is nothing to stop, so the renderer stays where it is instead of answering
     * with UPnP error 701 (this state lists {@code Stop} as a possible action as well). Cling's
     * {@code NoMediaPresent} does not declare {@code stop()}: the state machine looks a signal up by its
     * name on the current state and answers 701 when there is none, so this method is what makes it work.
     */
    public Class<? extends AbstractState<?>> stop() {
        YaaccLogger.d(this.getClass().getName(), "stop: nothing to stop");
        return AvTransportMediaRendererNoMediaPresent.class;
    }

    @Override
    public Class<? extends AbstractState<?>> play(String speed) {

        return AvTransportMediaRendererStopped.class;
    }

    @Override
    public TransportAction[] getPossibleTransportActions() {
        return new TransportAction[]{
                TransportAction.Stop,
                TransportAction.Play,
                TransportAction.Next,
                TransportAction.Previous,
                TransportAction.Seek
        };
    }

}
