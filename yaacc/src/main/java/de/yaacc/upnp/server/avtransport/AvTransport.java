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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayModeSettings;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;


/**
 * The state of one instance of the media renderer's AVTransport service. Besides the cling state it keeps
 * the play mode (UPnP {@code CurrentPlayMode}: normal, repeat one, repeat all, shuffle, random) in line
 * with the players the renderer plays on: {@code SetPlayMode} of a control point such as Home Assistant
 * reaches the players, and a change at a player (its repeat or shuffle button) reaches the control point
 * as a {@code LastChange} event and the answer of {@code GetTransportSettings}.
 * <p>
 * <b>Memory of the mode.</b> A mode set by a control point or at a player is given to every player the
 * transport starts later, so it survives the player that is replaced by the next item (Home Assistant plays one
 * item at a time, each one on a new player). Once <em>all</em> the players of the transport are gone (closed,
 * ended, removed) the transport forgets the mode: the next playback starts with the settings again, and the
 * control point is told the mode of the settings by an event.
 * <p>
 * <b>The end of the playback.</b> A player that has played its playlist to the end (repeat off), a player that is
 * stopped in the app and a player that is closed make the transport leave PLAYING / PAUSED the way a {@code Stop} of a
 * control point does: the state machine goes to the Stopped state, which events {@code TransportState} and
 * {@code CurrentTransportActions} and stops the position updates. A control point such as Home Assistant keeps the last
 * evented {@code TransportState} and skips its {@code Play} when that still says PLAYING (see {@link #playbackEnded}).
 *
 * @author Tobias Schoene (TheOpenBit)
 */
public class AvTransport extends AVTransport {

    private static UpnpClient upnpClient;

    // guards the fields below that are written together; never held while a player is called
    private final Object modeLock = new Object();
    // true once a play mode was set (by a control point or at a player): it is then given to every new
    // player of this transport; before, a new player keeps the one the settings give it
    private volatile boolean playModeChosen;
    // true once the stored settings tell the real play mode; before they tell the one a new player gets
    private volatile boolean playModeKnown;
    // the players this transport plays on (that were attached to it) as long as one of them is active
    private final List<Player> attachedPlayers = new CopyOnWriteArrayList<>();
    // > 0 while a player is being started: the one that is replaced is removed, the new one not attached yet
    private final AtomicInteger startingPlayers = new AtomicInteger();
    private UpnpClient listeningClient;
    // asks the state machine of this transport to look whether the playback has ended (set by the service that owns it)
    private volatile Consumer<Player> stopRequester;
    // set by the state that leaves PLAYING / PAUSED because the players have stopped (or are gone), read by the Stopped
    // state that follows at once under the same lock: the players need no Stop of their own then
    private volatile boolean playersAlreadyStopped;
    private final PlayerListChangeNotifier.Listener playerListListener = new PlayerListChangeNotifier.Listener() {
        @Override
        public void onPlayerAdded(Player player) {
            // nothing: players are attached by the state that starts them
        }

        @Override
        public void onPlayerRemoved(Player player) {
            checkPlayersGone();
        }
    };
    private final PropertyChangeListener playerListener = event -> {
        if (Player.PROPERTY_PLAY_MODE.equals(event.getPropertyName()) && event.getNewValue() instanceof PlayMode) {
            synchronized (modeLock) {
                playModeChosen = true;
                storePlayMode(upnpName((PlayMode) event.getNewValue()));
            }
        } else if (Player.PROPERTY_STOPPED.equals(event.getPropertyName()) && event.getSource() instanceof Player) {
            // on the thread of the player (a timer thread): only queue the look, nothing else
            requestStop((Player) event.getSource());
        }
    };

    /**
     * The looks at the state machine, one at a time in the order they came, on a daemon thread that ends when idle. They
     * wait for the lock of the state machine, which a transition holds while it creates and starts players, so they
     * must not run on the thread of a player or on the main thread.
     */
    private static final ThreadPoolExecutor STATE_REQUESTS = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "avtransport-player-end");
        thread.setDaemon(true);
        return thread;
    });

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
     * @param requester what asks the state machine of this transport whether the playback has ended
     *                  ({@code AvTransportStateMachine.playerStopped}); called on the request thread of the transports,
     *                  never while a lock of the transport is held. The argument is the player that stopped, or null
     *                  when the players of the transport are gone.
     */
    void setStopRequester(Consumer<Player> requester) {
        this.stopRequester = requester;
    }

    private void requestStop(Player stopped) {
        Consumer<Player> requester = stopRequester;
        if (requester == null) {
            return;
        }
        try {
            STATE_REQUESTS.execute(() -> {
                try {
                    requester.accept(stopped);
                } catch (RuntimeException e) {
                    YaaccLogger.w(getClass().getName(), "AVT end of the playback not handled", e);
                }
            });
        } catch (RejectedExecutionException e) {
            YaaccLogger.w(getClass().getName(), "AVT end of the playback not handled", e);
        }
    }

    /**
     * Decides, inside the state machine (the Playing and Paused states, under its lock), whether the playback of this
     * transport has ended. It has when the player that stopped is one this transport plays on and none of the others
     * is playing or paused, or, for {@code null}, when none of the players this transport started is active any more.
     * <p>
     * A player that is not (or not any more) among the active players of the transport says nothing: the player that was
     * replaced by the next item stops late, after the new one is attached, and must not take the new playback down.
     * Only getters of the players and the player list are called here, nothing that waits.
     *
     * @param stopped the player that stopped, null if the players are gone
     * @return true if the transport is to leave PLAYING / PAUSED; the Stopped state that follows then leaves the players
     * alone (they have stopped or are gone)
     */
    boolean playbackEnded(Player stopped) {
        UpnpClient client = upnpClient;
        if (client == null) {
            return false;
        }
        Collection<Player> active = client.getCurrentPlayers();
        if (active == null) {
            active = Collections.emptyList();
        }
        if (stopped != null && (!attachedPlayers.contains(stopped) || !active.contains(stopped))) {
            return false;
        }
        for (Player player : attachedPlayers) {
            if (player != stopped && active.contains(player)
                    && (stopped == null || player.isPlaying() || player.isPaused())) {
                return false;
            }
        }
        playersAlreadyStopped = true;
        return true;
    }

    /** @return true (once) if the playback ended and the players need no Stop of the transport */
    boolean takePlayersAlreadyStopped() {
        boolean stopped = playersAlreadyStopped;
        playersAlreadyStopped = false;
        return stopped;
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
     * @return the number of players that were given the mode
     */
    public int setPlayMode(org.fourthline.cling.support.model.PlayMode mode) {
        synchronized (modeLock) {
            playModeChosen = true;
            storePlayMode(mode.name());
        }
        return applyPlayMode(playersOfTheTransport());
    }

    /**
     * A renderer starts or resumes to play on these players: the chosen play mode goes to them, or when
     * none was chosen yet their own mode (from the settings) is what the transport reports. From now on
     * a change at one of them is reported too, and the transport watches for the end of its players
     * (see {@link #playersStarted()}).
     */
    public void attachPlayers(List<Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        for (Player player : players) {
            if (player != null) {
                player.removePropertyChangeListener(playerListener);
                player.addPropertyChangeListener(playerListener);
            }
        }
        // the players are asked before the lock is taken: nothing calls into a player while it is held
        Player first = players.get(0);
        PlayMode firstMode = first == null ? null : first.getPlayMode();
        List<Player> dropped;
        boolean chosen;
        synchronized (modeLock) {
            dropped = dropInactivePlayers();
            for (Player player : players) {
                if (player != null && !attachedPlayers.contains(player)) {
                    attachedPlayers.add(player);
                }
            }
            listenForPlayerEnd();
            chosen = playModeChosen;
            if (!chosen && firstMode != null) {
                storePlayMode(upnpName(firstMode));
            }
        }
        for (Player player : dropped) {
            player.removePropertyChangeListener(playerListener);
        }
        if (chosen) {
            applyPlayMode(players);
        }
    }

    /**
     * A state is about to create the players of this transport (it may replace the player that plays now
     * by a new one): until {@link #playersStarted()} the removal of the old player does not mean that the
     * transport has no player left.
     */
    public void playersStarting() {
        startingPlayers.incrementAndGet();
    }

    /**
     * The players are created and attached (or could not be created): from now on the end of the last player
     * makes the transport forget the play mode.
     */
    public void playersStarted() {
        startingPlayers.updateAndGet(count -> Math.max(0, count - 1));
        checkPlayersGone();
    }

    /**
     * Called when a player was removed (closed in the app, ended, replaced). When none of the players of this
     * transport is active any more, the play mode that was set or chosen is forgotten.
     */
    void checkPlayersGone() {
        if (startingPlayers.get() > 0) {
            return; // playersStarted() looks again
        }
        UpnpClient client = upnpClient;
        if (client == null || attachedPlayers.isEmpty()) {
            return; // nothing was played on this transport: a mode set before the first player stays
        }
        Collection<Player> active = client.getCurrentPlayers();
        for (Player player : attachedPlayers) {
            if (active != null && active.contains(player)) {
                return;
            }
        }
        playersGone();
    }

    private void playersGone() {
        List<Player> gone;
        synchronized (modeLock) {
            gone = new ArrayList<>(attachedPlayers);
            attachedPlayers.clear();
            stopListeningForPlayerEnd();
        }
        for (Player player : gone) {
            player.removePropertyChangeListener(playerListener);
        }
        forgetPlayMode();
        // nothing plays any more, so a transport that says PLAYING or PAUSED is wrong; queued, because this may run inside
        // a transition of the state machine (playersStarted) or on the main thread
        requestStop(null);
    }

    /**
     * The mode is back to what the settings give a new player; the control point is told if that is not what it
     * last saw.
     */
    private void forgetPlayMode() {
        synchronized (modeLock) {
            if (!playModeChosen && !playModeKnown) {
                return; // the control point only ever saw the mode of the settings
            }
            org.fourthline.cling.support.model.PlayMode reported = super.getTransportSettings().getPlayMode();
            playModeChosen = false;
            playModeKnown = false;
            org.fourthline.cling.support.model.PlayMode settingsMode = getTransportSettings().getPlayMode();
            YaaccLogger.d(getClass().getName(), "AVT players gone, play mode " + reported + " forgotten, settings give "
                    + settingsMode);
            super.setTransportSettings(new TransportSettings(settingsMode, super.getTransportSettings().getRecQualityMode()));
            if (settingsMode != reported) {
                getLastChange().setEventedValue(getInstanceId(), new AVTransportVariable.CurrentPlayMode(settingsMode));
            }
        }
    }

    /** @return the attached players that are not active any more, now not attached */
    private List<Player> dropInactivePlayers() {
        UpnpClient client = upnpClient;
        Collection<Player> active = client == null ? null : client.getCurrentPlayers();
        List<Player> dropped = new ArrayList<>();
        for (Player player : attachedPlayers) {
            if (active == null || !active.contains(player)) {
                attachedPlayers.remove(player);
                dropped.add(player);
            }
        }
        return dropped;
    }

    private void listenForPlayerEnd() {
        UpnpClient client = upnpClient;
        if (client != null && listeningClient != client) {
            stopListeningForPlayerEnd();
            client.addPlayerListChangeListener(playerListListener);
            listeningClient = client;
        }
    }

    private void stopListeningForPlayerEnd() {
        if (listeningClient != null) {
            listeningClient.removePlayerListChangeListener(playerListListener);
            listeningClient = null;
        }
    }

    private int applyPlayMode(List<Player> players) {
        PlayMode mode = PlayMode.fromUpnpPlayMode(super.getTransportSettings().getPlayMode().name());
        int applied = 0;
        for (Player player : players) {
            if (player != null) {
                player.setPlayMode(mode);
                applied++;
            }
        }
        return applied;
    }

    /**
     * The players that get a mode set from outside: those this transport started that are still active, and the
     * ones {@code UpnpClient.getCurrentPlayers(transport)} finds. That lookup goes by the <em>kind</em> of player the
     * current track's mime type needs, not by the transport, and guesses the mime type differently from the code that
     * created the player (DIDL {@code protocolInfo} there, the file extension as the fallback here), so a player this
     * transport started can be missing from it.
     */
    private List<Player> playersOfTheTransport() {
        LinkedHashSet<Player> players = new LinkedHashSet<>();
        UpnpClient client = upnpClient;
        Collection<Player> active = client == null ? null : client.getCurrentPlayers();
        if (active != null) {
            for (Player player : attachedPlayers) {
                if (active.contains(player)) {
                    players.add(player);
                }
            }
        }
        players.addAll(currentPlayers());
        return new ArrayList<>(players);
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
        synchronized (modeLock) {
            boolean changed = !playModeKnown || super.getTransportSettings().getPlayMode() != mode;
            playModeKnown = true;
            if (!changed) {
                return;
            }
            super.setTransportSettings(new TransportSettings(mode, super.getTransportSettings().getRecQualityMode()));
            getLastChange().setEventedValue(getInstanceId(), new AVTransportVariable.CurrentPlayMode(mode));
        }
    }

    private static String upnpName(PlayMode mode) {
        return mode.toUpnpPlayMode();
    }

}
