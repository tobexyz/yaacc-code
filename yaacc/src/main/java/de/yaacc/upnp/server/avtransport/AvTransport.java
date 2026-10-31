/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.StorageMedium;
import org.fourthline.cling.support.model.TransportSettings;

import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.List;

import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayModeSettings;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;


/**
 * The state of one instance of the media renderer's AVTransport service. Besides the cling state it keeps
 * the play mode (UPnP {@code CurrentPlayMode}: normal, repeat one, repeat all, shuffle, random) in line
 * with the players the renderer plays on: {@code SetPlayMode} of a control point such as Home Assistant
 * reaches the players, and a change at a player (its repeat or shuffle button) reaches the control point
 * as a {@code LastChange} event and the answer of {@code GetTransportSettings}.
 *
 * @author Tobias Schoene (TheOpenBit)
 */
public class AvTransport extends AVTransport {

    private static UpnpClient upnpClient;

    // true once a play mode was set (by a control point or at a player): it is then given to every new
    // player of this transport; before, a new player keeps the one the settings give it
    private volatile boolean playModeChosen;
    // true once the stored settings tell the real play mode; before they tell the one a new player gets
    private volatile boolean playModeKnown;
    private final PropertyChangeListener playerPlayModeListener = event -> {
        if (Player.PROPERTY_PLAY_MODE.equals(event.getPropertyName()) && event.getNewValue() instanceof PlayMode) {
            playModeChosen = true;
            storePlayMode(upnpName((PlayMode) event.getNewValue()));
        }
    };

    public AvTransport(UnsignedIntegerFourBytes instanceID, LastChange lastChange, StorageMedium possiblePlayMedium) {
        super(instanceID, lastChange, possiblePlayMedium);
    }

    public static void setUpnpClient(UpnpClient client) {
        upnpClient = client;
    }

    public static UpnpClient getUpnpClient() {
        return upnpClient;
    }

    /**
     * @return the settings of the transport; as long as no play mode is known the play mode is the one a
     * new player starts with (the settings "replay playlist" and "shuffle music"), not UPnP's NORMAL
     */
    @Override
    public TransportSettings getTransportSettings() {
        TransportSettings stored = super.getTransportSettings();
        if (!playModeKnown && upnpClient != null && upnpClient.getContext() != null) {
            try {
                return new TransportSettings(
                        org.fourthline.cling.support.model.PlayMode.valueOf(upnpName(PlayModeSettings.defaultPlayMode(upnpClient.getContext()))),
                        stored.getRecQualityMode());
            } catch (RuntimeException e) {
                YaaccLogger.d(getClass().getName(), "Default play mode not readable, using the stored one", e);
            }
        }
        return stored;
    }

    /**
     * {@code SetPlayMode}: remembers the mode, tells the control points (LastChange) and gives it to the
     * players that play now; a player created later gets it as well.
     *
     * @param mode the UPnP play mode; {@code DIRECT_1} and {@code INTRO} are stored as they are but
     *             played as normal
     */
    public void setPlayMode(org.fourthline.cling.support.model.PlayMode mode) {
        playModeChosen = true;
        storePlayMode(mode.name());
        applyPlayMode(currentPlayers());
    }

    /**
     * A renderer starts or resumes to play on these players: the chosen play mode goes to them, or when
     * none was chosen yet their own mode (from the settings) is what the transport reports. From now on
     * a change at one of them is reported too.
     */
    public void attachPlayers(List<Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        for (Player player : players) {
            if (player != null) {
                player.removePropertyChangeListener(playerPlayModeListener);
                player.addPropertyChangeListener(playerPlayModeListener);
            }
        }
        if (playModeChosen) {
            applyPlayMode(players);
        } else if (players.get(0) != null) {
            storePlayMode(upnpName(players.get(0).getPlayMode()));
        }
    }

    private void applyPlayMode(List<Player> players) {
        PlayMode mode = PlayMode.fromUpnpPlayMode(super.getTransportSettings().getPlayMode().name());
        for (Player player : players) {
            if (player != null) {
                player.setPlayMode(mode);
            }
        }
    }

    private List<Player> currentPlayers() {
        UpnpClient client = upnpClient;
        if (client == null) {
            return Collections.emptyList();
        }
        List<Player> players = client.getCurrentPlayers(this);
        return players == null ? Collections.emptyList() : players;
    }

    private void storePlayMode(String upnpPlayMode) {
        org.fourthline.cling.support.model.PlayMode mode;
        try {
            mode = org.fourthline.cling.support.model.PlayMode.valueOf(upnpPlayMode);
        } catch (IllegalArgumentException e) {
            return;
        }
        boolean changed = !playModeKnown || super.getTransportSettings().getPlayMode() != mode;
        playModeKnown = true;
        if (!changed) {
            return;
        }
        super.setTransportSettings(new TransportSettings(mode, super.getTransportSettings().getRecQualityMode()));
        getLastChange().setEventedValue(getInstanceId(), new AVTransportVariable.CurrentPlayMode(mode));
    }

    private static String upnpName(PlayMode mode) {
        return mode.toUpnpPlayMode();
    }

}
