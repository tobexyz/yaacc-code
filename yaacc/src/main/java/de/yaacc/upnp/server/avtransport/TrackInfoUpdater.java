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

import org.fourthline.cling.support.model.PositionInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.yaacc.player.Player;

/**
 * Copies the duration and the elapsed time of the player the renderer plays on into the position info of the
 * transport (what {@code GetPositionInfo} answers), once a second while the transport plays.
 * <p>
 * <b>One task, owned by the Playing state.</b> It runs from {@link #start} until {@link #stop} (the state's
 * {@code onExit}) or until no player is active any more, whichever comes first, and a second {@code start}
 * replaces the first. Before, every tick scheduled the next one on a new {@code Timer} and nothing ever stopped
 * the chain when the state was left in another way than Stop or Pause (a new {@code SetAVTransportURI} while
 * playing, a player that was closed in the app): the chains piled up with every Play, ran for ever and wrote a
 * line to the log, several times a second, with no player at all.
 * <p>
 * Nothing is done, and nothing is logged, when there is nothing to update: no active player (the updater stops
 * itself), no duration yet, or the same values as before. The position info is a plain value of the transport;
 * the relative and the absolute time position are not evented variables and never go into the
 * {@code LastChange}, so an update never causes an event.
 */
final class TrackInfoUpdater {

    static final long PERIOD_MILLIS = 1000;

    private static ScheduledExecutorService sharedScheduler;

    private final AvTransport transport;
    private final Supplier<Collection<Player>> activePlayers;
    private final ScheduledExecutorService scheduler;
    private final Executor playerThread;
    private final Consumer<String> verboseLog;
    // guarded by this
    private ScheduledFuture<?> task;
    private List<Player> players = new ArrayList<>();
    private boolean running;

    /**
     * @param activePlayers the players that exist now ({@code UpnpClient.getCurrentPlayers()})
     * @param scheduler     runs the ticks
     * @param playerThread  the thread the players must be called on (the main thread: Media3 players insist on it)
     * @param verboseLog    where the update is logged (verbose level)
     */
    TrackInfoUpdater(AvTransport transport, Supplier<Collection<Player>> activePlayers,
                     ScheduledExecutorService scheduler, Executor playerThread, Consumer<String> verboseLog) {
        this.transport = transport;
        this.activePlayers = activePlayers;
        this.scheduler = scheduler;
        this.playerThread = playerThread;
        this.verboseLog = verboseLog;
    }

    /** One idle thread at most, and not even that when nothing plays (the thread ends after 10 s). */
    static synchronized ScheduledExecutorService sharedScheduler() {
        if (sharedScheduler == null) {
            ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "avtransport-track-info");
                thread.setDaemon(true);
                return thread;
            });
            pool.setKeepAliveTime(10, TimeUnit.SECONDS);
            pool.allowCoreThreadTimeOut(true);
            pool.setRemoveOnCancelPolicy(true);
            sharedScheduler = pool;
        }
        return sharedScheduler;
    }

    /** Starts updating from these players now and every second; replaces a running update. */
    synchronized void start(List<Player> players) {
        cancelTask();
        this.players = new ArrayList<>(players);
        running = true;
        task = scheduler.scheduleWithFixedDelay(this::tick, 0, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        verboseLog.accept("track info updates started");
    }

    /** Stops updating; an update that is on its way does nothing. */
    synchronized void stop() {
        if (running) {
            verboseLog.accept("track info updates stopped");
        }
        running = false;
        cancelTask();
    }

    synchronized boolean isRunning() {
        return running;
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private void tick() {
        if (isRunning()) {
            playerThread.execute(this::update);
        }
    }

    /** One update, on the player thread. */
    void update() {
        List<Player> own;
        synchronized (this) {
            if (!running) {
                return;
            }
            own = players;
        }
        Player player = firstActive(own);
        if (player == null) {
            stop(); // the players are gone: nothing left to do
            return;
        }
        String duration = player.getDuration();
        String elapsedTime = player.getElapsedTime();
        if (duration == null || duration.isEmpty() || elapsedTime == null) {
            return;
        }
        PositionInfo positionInfo = transport.getPositionInfo();
        if (duration.equals(positionInfo.getTrackDuration()) && elapsedTime.equals(positionInfo.getRelTime())) {
            return;
        }
        positionInfo.setTrackDuration(duration);
        positionInfo.setRelTime(elapsedTime);
        verboseLog.accept("track info: " + positionInfo);
    }

    private Player firstActive(List<Player> candidates) {
        Collection<Player> active = activePlayers.get();
        if (active == null) {
            return null;
        }
        for (Player player : candidates) {
            if (player != null && active.contains(player)) {
                return player;
            }
        }
        return null;
    }
}
