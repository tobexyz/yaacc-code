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


import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.Playing;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.SeekMode;

import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;

/**
 * State Playing.
 *
 * @author Tobias Schoene (openbit)
 */
public class AvTransportMediaRendererPlaying extends Playing<AvTransport> implements YaaccState {
    private final UpnpClient upnpClient;
    private boolean updateTime;
    private List<Player> players = null;

    /**
     * Constructor.
     *
     * @param transport  the state holder
     */
    public AvTransportMediaRendererPlaying(AvTransport transport) {
        super(transport);
        this.upnpClient = AvTransport.getUpnpClient();
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#onEntry()
     */
    @Override
    public void onEntry() {
        YaaccLogger.d(this.getClass().getName(), "On Entry");
        super.onEntry();
        if (getTransport() == null
                || getTransport().getPositionInfo() == null
                || getTransport().getPositionInfo().getTrackURI() == null
                || getTransport().getPositionInfo().getTrackURI().equals("")) {
            return;
        }
        players = upnpClient.initializePlayers(getTransport());
        for (Player player : players) {
            player.play();
        }
        if (players != null && players.size() > 0) {
            // Start playing now!
            updateTime = true;
            setTrackInfo();
        }

    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#setTransportURI(java.net.URI, java.lang.String)
     */
    @Override
    public Class<? extends AbstractState<?>> setTransportURI(URI uri,
                                                             String metaData) {
        YaaccLogger.d(this.getClass().getName(), "Set TransportURI");
        YaaccLogger.d(this.getClass().getName(), "uri: " + uri);
        YaaccLogger.d(this.getClass().getName(), "metaData: " + metaData);
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

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#stop()
     */
    @Override
    public Class<? extends AbstractState<?>> stop() {
        YaaccLogger.d(this.getClass().getName(), "Stop");
        updateTime = false;
// Stop playing!
        return AvTransportMediaRendererStopped.class;
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#play(java.lang.String)
     */
    @Override
    public Class<? extends AbstractState<?>> play(String speed) {
        YaaccLogger.d(this.getClass().getName(), "play");
        updateTime = true;
        return AvTransportMediaRendererPlaying.class;
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#pause()
     */
    @Override
    public Class<? extends AbstractState<?>> pause() {
        YaaccLogger.d(this.getClass().getName(), "pause");
        updateTime = false;
        return AvTransportMediaRendererPaused.class;
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#next()
     */
    @Override
    public Class<? extends AbstractState<?>> next() {
        YaaccLogger.d(this.getClass().getName(), "next");
        updateTime = false;
        return null;
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#previous()
     */
    @Override
    public Class<? extends AbstractState<?>> previous() {
        YaaccLogger.d(this.getClass().getName(), "previous");
        updateTime = false;
        return null;
    }

    /*
     * (non-Javadoc)
     * @see org.fourthline.cling.support.avtransport.impl.state.Playing#seek(org.fourthline.cling.support.model.SeekMode, java.lang.String)
     */
    @Override
    public Class<? extends AbstractState<?>> seek(SeekMode unit, String target) {
        YaaccLogger.d(this.getClass().getName(), "seek");
        if (SeekMode.REL_TIME.equals(unit)) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            try {
                Long millisecondsFromStart = (target != null && dateFormat.parse(target) != null) ? dateFormat.parse(target).getTime() : 0L;
                for (Player player : players) {
                    if (player != null) {
                        player.seekTo(millisecondsFromStart);
                    }
                }
            } catch (ParseException pex) {
                YaaccLogger.d(getClass().getName(), "unable to parse target time string", pex);
            }
        }
        updateTime = true;
        return null;
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

    private void setTrackInfo() {
        doSetTrackInfo();
        updateTime();
    }

    private void doSetTrackInfo() {
        for (Player player : players) {
            if (player != null) {
                // Get duration on main thread for Media3 compatibility
                final String[] duration = {""};
                final String[] elapsedTime = {""};
                
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    duration[0] = player.getDuration();
                    elapsedTime[0] = player.getElapsedTime();
                    
                    if (!duration[0].equals("")) {
                        getTransport().getPositionInfo().setTrackDuration(duration[0]);
                        getTransport().getPositionInfo().setRelTime(elapsedTime[0]);
                        YaaccLogger.d(getClass().getName(), "doSetTrackInfo: " + getTransport() + "Posinfo:" + getTransport().getPositionInfo() + " RelTime: " + getTransport().getPositionInfo().getRelTime());
                    }
                });
                break;
            }
        }
    }

    private void updateTime() {
        Timer commandExecutionTimer = new Timer();
        commandExecutionTimer.schedule(new TimerTask() {

            @Override
            public void run() {

                doSetTrackInfo();
                if (updateTime) {
                    updateTime();
                }
            }


        }, 1000L);

    }


} 