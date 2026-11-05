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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.upnp.server.avtransport;

import de.yaacc.share.ShareLogRedactor;
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.PausedPlay;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.SeekMode;

import java.net.URI;
import java.util.List;

import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;

/**
 * State Paused.
 *
 * @author Tobias Schoene (openbit)
 */
public class AvTransportMediaRendererPaused extends PausedPlay<AvTransport> implements YaaccState {
    private final UpnpClient upnpClient;

    /**
     * Constructor.
     *
     * @param transport  the state holder
     */
    public AvTransportMediaRendererPaused(AvTransport transport) {
        super(transport);
        this.upnpClient = AvTransport.getUpnpClient();
    }

    /* (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.PausedPlay#play(java.lang.String)
     */
    @Override
    public Class<? extends AbstractState<?>> play(String arg0) {
        YaaccLogger.d(this.getClass().getName(), "play");
        return AvTransportMediaRendererPlaying.class;
    }

    /* (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.PausedPlay#setTransportURI(java.net.URI, java.lang.String)
     */
    @Override
    public Class<? extends AbstractState<?>> setTransportURI(URI uri, String metaData) {
        YaaccLogger.d(this.getClass().getName(), "setTransportURI");
        YaaccLogger.d(this.getClass().getName(), "uri: " + ShareLogRedactor.redact(String.valueOf(uri)));
        YaaccLogger.d(this.getClass().getName(), "metaData: " + ShareLogRedactor.redact(String.valueOf(metaData)));
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

    /** The player was stopped in the app or closed while paused: the transport goes to Stopped like for a Stop. */
    public Class<? extends AbstractState<?>> playerStopped(Player player) {
        if (!getTransport().playbackEnded(player)) {
            return null;
        }
        YaaccLogger.d(this.getClass().getName(), "AVT player ended: transport -> STOPPED");
        return AvTransportMediaRendererStopped.class;
    }

    /**
     * A second Pause (Home Assistant has one play / pause button; clicked fast, the second click meets the paused renderer):
     * the renderer is paused already, so it stays where it is instead of answering UPnP error 701. Cling's {@code PausedPlay}
     * does not declare {@code pause()}: the state machine looks a signal up by its name on the current state.
     */
    public Class<? extends AbstractState<?>> pause() {
        YaaccLogger.d(this.getClass().getName(), "pause: paused already");
        return null;
    }

    /** Listed as a possible action: the players move to the position and the pause stays. */
    public Class<? extends AbstractState<?>> seek(SeekMode unit, String target) {
        YaaccLogger.d(this.getClass().getName(), "seek");
        if (SeekMode.REL_TIME.equals(unit)) {
            Long millisecondsFromStart = SeekTarget.millis(target);
            if (millisecondsFromStart == null) {
                YaaccLogger.d(getClass().getName(), "unable to parse target time string");
            } else {
                for (Player player : upnpClient.getCurrentPlayers(getTransport())) {
                    if (player != null) {
                        player.seekTo(millisecondsFromStart);
                    }
                }
            }
        }
        return null;
    }

    /** Listed as a possible action; as while playing, the renderer does not change the item by itself. */
    public Class<? extends AbstractState<?>> next() {
        YaaccLogger.d(this.getClass().getName(), "next");
        return null;
    }

    /** Listed as a possible action; as while playing, the renderer does not change the item by itself. */
    public Class<? extends AbstractState<?>> previous() {
        YaaccLogger.d(this.getClass().getName(), "previous");
        return null;
    }

    /* (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.PausedPlay#stop()
     */
    @Override
    public Class<? extends AbstractState<?>> stop() {
        YaaccLogger.d(this.getClass().getName(), "stop");
        return AvTransportMediaRendererStopped.class;
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#onEntry()
     */
    @Override
    public void onEntry() {
        YaaccLogger.d(this.getClass().getName(), "On Entry");
        super.onEntry();
        List<Player> players = upnpClient.getCurrentPlayers(getTransport());
        for (Player player : players) {
            if (player != null) {
                player.pause();
            }
        }
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