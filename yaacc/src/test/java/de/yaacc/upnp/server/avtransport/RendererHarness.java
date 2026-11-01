/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.ServiceManager;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.player.PlayMode;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.ServiceHarness;

/**
 * The media renderer's real AVTransport service run the way the app receives a control point's requests (see
 * {@link ServiceHarness}). Only the network and the players are replaced: a mocked {@link UpnpClient} with fake
 * {@link Player}s that keep their {@link PlayMode} and tell their property change listeners like
 * {@code AbstractPlayer} does. Plain JVM.
 */
final class RendererHarness extends ServiceHarness<YaaccAVTransportService> {

    static final String SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1";

    final UpnpClient client = mock(UpnpClient.class);
    /** The players the renderer plays on (what {@code UpnpClient.getCurrentPlayers(transport)} answers). */
    final List<Player> players = new CopyOnWriteArrayList<>();

    RendererHarness(ServiceManagerFactory managerFactory) {
        super(YaaccAVTransportService.class, SERVICE_TYPE, "AVTransport", managerFactory::create);
        AvTransport.setUpnpClient(client);
        when(client.getCurrentPlayers(any(AvTransport.class))).thenAnswer(invocation ->
                lookupByKindFindsNothing ? new ArrayList<Player>() : new ArrayList<>(players));
        when(client.getCurrentPlayers()).thenAnswer(invocation -> new ArrayList<>(players));
        doAnswer(invocation -> playerListListeners.addIfAbsent(invocation.getArgument(0)))
                .when(client).addPlayerListChangeListener(any());
        doAnswer(invocation -> playerListListeners.remove(invocation.<Object>getArgument(0)))
                .when(client).removePlayerListChangeListener(any());
        // like PlayerService.createPlayerForLocalDevice: the player of the same kind is shut down, a new one with the
        // mode of the settings is created and added
        when(client.initializePlayers(any(AvTransport.class))).thenAnswer(invocation -> {
            List<Player> replaced = new ArrayList<>(players);
            players.clear();
            if (removalNoticeDuringStart) {
                // the main thread delivers the "removed" notice while the new player is still being created
                replaced.forEach(this::announceRemoved);
            }
            if (!newPlayerCannotBeCreated) {
                players.add(fakePlayer(newPlayerMode));
            }
            return new ArrayList<>(players);
        });
    }

    /** Listeners of {@code UpnpClient.addPlayerListChangeListener} (the transport registers one). */
    final CopyOnWriteArrayList<PlayerListChangeNotifier.Listener> playerListListeners = new CopyOnWriteArrayList<>();
    /** The mode a new player starts with (the settings). */
    PlayMode newPlayerMode = PlayMode.NORMAL;
    boolean removalNoticeDuringStart = true;
    boolean newPlayerCannotBeCreated;
    /** {@code getCurrentPlayers(transport)} goes by the kind of player for the mime type of the track: it can find none. */
    volatile boolean lookupByKindFindsNothing;

    private void announceRemoved(Player player) {
        for (PlayerListChangeNotifier.Listener listener : playerListListeners) {
            listener.onPlayerRemoved(player);
        }
    }

    /** The user closes the player in the app (or it ends): it leaves the active players, the listeners are told. */
    void closePlayer(Player player) {
        players.remove(player);
        announceRemoved(player);
    }

    interface ServiceManagerFactory {
        ServiceManager<YaaccAVTransportService> create(LocalService<YaaccAVTransportService> service);
    }

    AvTransport transport() throws Exception {
        return (AvTransport) service().findStateMachine(YaaccAVTransportService.getDefaultInstanceID())
                .getCurrentState().getTransport();
    }

    StreamResponseMessage setPlayMode(String mode) {
        return soap("SetPlayMode", "InstanceID", "0", "NewPlayMode", mode);
    }

    StreamResponseMessage setAvTransportUri(String uri) {
        return soap("SetAVTransportURI", "InstanceID", "0", "CurrentURI", uri, "CurrentURIMetaData", "");
    }

    StreamResponseMessage play() {
        return soap("Play", "InstanceID", "0", "Speed", "1");
    }

    StreamResponseMessage stop() {
        return soap("Stop", "InstanceID", "0");
    }

    StreamResponseMessage pause() {
        return soap("Pause", "InstanceID", "0");
    }

    // ---- players

    Player addPlayer(PlayMode mode) {
        Player player = fakePlayer(mode);
        players.add(player);
        return player;
    }

    static Player fakePlayer(PlayMode initial) {
        Player player = mock(Player.class);
        AtomicReference<PlayMode> mode = new AtomicReference<>(initial);
        PropertyChangeSupport listeners = new PropertyChangeSupport(player);
        when(player.getPlayMode()).thenAnswer(invocation -> mode.get());
        doAnswer(invocation -> {
            PlayMode wanted = invocation.getArgument(0);
            PlayMode old = mode.getAndSet(wanted);
            if (!old.equals(wanted)) {
                listeners.firePropertyChange(Player.PROPERTY_PLAY_MODE, old, wanted);
            }
            return null;
        }).when(player).setPlayMode(any());
        doAnswer(invocation -> {
            listeners.addPropertyChangeListener((PropertyChangeListener) invocation.getArgument(0));
            return null;
        }).when(player).addPropertyChangeListener(any());
        doAnswer(invocation -> {
            listeners.removePropertyChangeListener((PropertyChangeListener) invocation.getArgument(0));
            return null;
        }).when(player).removePropertyChangeListener(any());
        // like AbstractPlayer: play() and pause() change what isPlaying() / isPaused() say, stop() ends both and then
        // tells the listeners (PROPERTY_STOPPED), whoever called it: the end of the playlist, the app, the renderer
        AtomicBoolean playing = new AtomicBoolean();
        AtomicBoolean paused = new AtomicBoolean();
        when(player.isPlaying()).thenAnswer(invocation -> playing.get());
        when(player.isPaused()).thenAnswer(invocation -> paused.get());
        doAnswer(invocation -> {
            playing.set(true);
            paused.set(false);
            return null;
        }).when(player).play();
        doAnswer(invocation -> {
            playing.set(false);
            paused.set(true);
            return null;
        }).when(player).pause();
        doAnswer(invocation -> {
            playing.set(false);
            paused.set(false);
            listeners.firePropertyChange(Player.PROPERTY_STOPPED, null, null);
            return null;
        }).when(player).stop();
        return player;
    }

    /**
     * The playlist of the player is played to its end with repeat off: what {@code LocalMediaSessionPlayer} does at
     * "End of the playlist reached, repeat is off: stopping" (and {@code AbstractPlayer.advance} at the end of the list)
     * is {@code stop()} of the player itself. The player stays among the active players.
     */
    void playerEnds(Player player) {
        player.stop();
    }

    void settings(boolean replay, boolean shuffle) {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(client.getContext()).thenReturn(context);
        when(context.getPackageName()).thenReturn("de.yaacc");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(context.getString(de.yaacc.R.string.settings_replay_playlist_chkbx)).thenReturn("replay");
        when(context.getString(de.yaacc.R.string.settings_music_player_shuffle_chkbx)).thenReturn("shuffle");
        when(preferences.getBoolean("replay", false)).thenReturn(replay);
        when(preferences.getBoolean("shuffle", false)).thenReturn(shuffle);
        newPlayerMode = PlayMode.fromSettings(replay, shuffle);
    }

    @Override
    public void close() {
        super.close();
        AvTransport.setUpnpClient(null);
    }
}
