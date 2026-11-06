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
package de.yaacc.upnp.server.openhome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.player.AbstractPlayer;
import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.util.YaaccLogger;

/**
 * Plays the OpenHome queue of the renderer on this device and reports what the player does back to the OpenHome services.
 * <p>
 * The queue ({@link OhRendererCore#queue()}) is the truth: the player holds the same tracks in the same order, created from the
 * queue when somebody plays, and kept in step by {@link AbstractPlayer#insertItems} and {@link AbstractPlayer#removeItem}.
 * What the player does on its own (a track ends, the user pauses in the notification) is found by {@link #refresh()}, which
 * runs about once a second while the renderer is started.
 * <p>
 * The part that needs Android (creating the music player, turning a DIDL text into a playable item, the system volume) is
 * behind {@link Host} and {@link Volume}, so the logic runs in plain JVM tests.
 */
public final class OpenHomeRenderer implements OhPlayback {

    /** The app side of the renderer. */
    public interface Host {
        /** Creates a music player on this device that holds the items (in this order) and is not started. */
        AbstractPlayer createPlayer(List<PlayableItem> items);

        /** @return false once the player was shut down by somebody else */
        boolean isAlive(AbstractPlayer player);

        /** Never null: unreadable metadata give an item that plays the address. */
        PlayableItem toPlayableItem(String uri, String metadata);
    }

    /** The system volume of the device. */
    public interface Volume {
        int percent();

        boolean muted();

        void setPercent(int percent);

        void setMuted(boolean muted);
    }

    private static final long POLL_MILLIS = 1000;

    private final Host host;
    private final Volume volume;
    private final OhRendererCore core;
    private final Object lock = new Object();

    private AbstractPlayer player;
    private boolean repeat;
    private boolean shuffle;
    private PlayMode lastKnownMode;
    /** The track the next play starts with when there is no player, 0 for the first one. */
    private long startId;
    private long reportedId = Long.MIN_VALUE;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> poll;

    public OpenHomeRenderer(Host host, Volume volume, OhRendererCore.Delayer delayer, OhRendererCore.Details details,
                            String protocolInfo) {
        this.host = host;
        this.volume = volume;
        this.core = new OhRendererCore(this, delayer, details, protocolInfo);
    }

    public OhRendererCore core() {
        return core;
    }

    // ---- life cycle

    /** Starts the once-a-second look at the player. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openhome-renderer");
            thread.setDaemon(true);
            return thread;
        });
        poll = executor.scheduleWithFixedDelay(this::refreshSafely, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
        refreshSafely();
    }

    /** Stops the look at the player and the player itself. */
    public synchronized void shutdown() {
        if (poll != null) {
            poll.cancel(false);
            poll = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        discardPlayer(true);
    }

    /** A delayer for {@link OhRendererCore} that runs on its own daemon thread. */
    public static OhRendererCore.Delayer newDelayer() {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openhome-delayer");
            thread.setDaemon(true);
            return thread;
        });
        return (runnable, delayMillis) -> timer.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "OpenHome renderer refresh failed", e);
        }
    }

    // ---- what the controller asks for (OhPlayback)

    @Override
    public void play() {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current == null) {
                startFrom(startId);
            } else if (!current.isPlaying() || current.isPaused()) {
                current.play();
            }
        }
        refreshSafely();
    }

    @Override
    public void pause() {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null && current.isPlaying() && !current.isPaused()) {
                current.pause();
            }
        }
        refreshSafely();
    }

    @Override
    public void stop() {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                current.stop();
            }
            startId = 0;
        }
        refreshSafely();
    }

    @Override
    public void next() {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                current.next();
            }
        }
        refreshSafely();
    }

    @Override
    public void previous() {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                current.previous();
            }
        }
        refreshSafely();
    }

    @Override
    public void setRepeat(boolean repeat) {
        synchronized (lock) {
            this.repeat = repeat;
            applyMode();
        }
    }

    @Override
    public void setShuffle(boolean shuffle) {
        synchronized (lock) {
            this.shuffle = shuffle;
            applyMode();
        }
    }

    @Override
    public void seekSecondAbsolute(long second) {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                current.seekTo(Math.max(0, second) * 1000L);
            }
        }
    }

    @Override
    public void seekSecondRelative(int seconds) {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                current.seekTo(Math.max(0, current.getCurrentPosition() + seconds * 1000L));
            }
        }
    }

    @Override
    public void seekToTrack(long id) {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            int index = core.queue().indexOf(id);
            if (index < 0) {
                return;
            }
            if (current == null) {
                startFrom(id);
            } else {
                current.playItemAt(index);
            }
        }
        refreshSafely();
    }

    @Override
    public void trackInserted(int index, OhQueue.Entry entry) {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                List<PlayableItem> items = new ArrayList<>();
                items.add(host.toPlayableItem(entry.getUri(), entry.getMetadata()));
                current.insertItems(index, items);
            }
        }
    }

    @Override
    public void trackDeleted(int index, long id) {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            if (id == startId) {
                startId = index < core.queue().size() ? core.queue().idAt(index) : 0;
            }
            if (current != null && !current.removeItem(index, true)) {
                // the track that plays is gone: the next one is where a new play starts
                long next = index < core.queue().size() ? core.queue().idAt(index) : 0;
                discardPlayer(true);
                startId = next;
            }
        }
        refreshSafely();
    }

    @Override
    public void queueCleared() {
        synchronized (lock) {
            discardPlayer(true);
            startId = 0;
        }
        refreshSafely();
    }

    @Override
    public void setVolume(int percent) {
        volume.setPercent(Math.max(0, Math.min(100, percent)));
        refreshSafely();
    }

    @Override
    public void setMute(boolean mute) {
        volume.setMuted(mute);
        refreshSafely();
    }

    // ---- what the player does

    /** Reports the state of the player to the OpenHome services. Called about once a second, and after every command. */
    public void refresh() {
        synchronized (lock) {
            AbstractPlayer current = alivePlayer();
            reportVolume();
            if (current == null) {
                core.setTransportState(OhTransportState.STOPPED);
                core.setCurrentId(core.queue().size() == 0 ? 0 : startId);
                if (core.queue().size() == 0) {
                    reportedId = Long.MIN_VALUE;
                }
                return;
            }
            adoptPlayMode(current);
            boolean active = current.isPlaying() || current.isPaused();
            if (!active) {
                core.setTransportState(OhTransportState.STOPPED);
                return;
            }
            long id = core.queue().idAt(current.getCurrentItemIndex());
            if (id != reportedId) {
                reportedId = id;
                OhQueue.Entry entry = core.queue().read(id);
                core.setCurrentId(id);
                core.setTrack(entry.getUri(), entry.getMetadata(), durationSeconds(current));
            }
            core.setDuration(durationSeconds(current));
            core.setSeconds(Math.max(0, current.getCurrentPosition()) / 1000L);
            core.setTransportState(current.isPaused() ? OhTransportState.PAUSED : OhTransportState.PLAYING);
        }
    }

    private void reportVolume() {
        core.setVolumePercent(volume.percent());
        core.setMuted(volume.muted());
    }

    private static long durationSeconds(Player player) {
        long fromText = parseSeconds(player.getDuration());
        if (fromText > 0) {
            return fromText;
        }
        List<PlayableItem> items = player.getItems();
        int index = player.getCurrentItemIndex();
        if (index >= 0 && index < items.size() && items.get(index).getDuration() > 0) {
            return items.get(index).getDuration() / 1000L;
        }
        return 0;
    }

    /** "H:MM:SS" or "MM:SS" (the player's text) as seconds, 0 for anything else. */
    static long parseSeconds(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] parts = text.trim().split(":");
        if (parts.length < 2 || parts.length > 3) {
            return 0;
        }
        long seconds = 0;
        try {
            for (String part : parts) {
                long value = (long) Double.parseDouble(part);
                if (value < 0) {
                    return 0;
                }
                seconds = seconds * 60 + value;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        return seconds;
    }

    // ---- the player

    private AbstractPlayer alivePlayer() {
        if (player != null && !host.isAlive(player)) {
            player = null;
            reportedId = Long.MIN_VALUE;
        }
        return player;
    }

    /** Creates the player with the whole queue and starts it with the track (the first one for 0 or an unknown id). */
    private void startFrom(long id) {
        List<OhQueue.Entry> entries = core.queue().snapshot();
        if (entries.isEmpty()) {
            return;
        }
        List<PlayableItem> items = new ArrayList<>(entries.size());
        for (OhQueue.Entry entry : entries) {
            items.add(host.toPlayableItem(entry.getUri(), entry.getMetadata()));
        }
        AbstractPlayer created = host.createPlayer(items);
        if (created == null) {
            YaaccLogger.w(getClass().getName(), "no player for the OpenHome queue");
            return;
        }
        player = created;
        reportedId = Long.MIN_VALUE;
        lastKnownMode = null;
        applyMode();
        int index = id == 0 ? -1 : core.queue().indexOf(id);
        if (index < 0) {
            created.play();
        } else {
            created.playItemAt(index);
        }
        startId = 0;
    }

    private void discardPlayer(boolean stopIt) {
        AbstractPlayer old = player;
        player = null;
        reportedId = Long.MIN_VALUE;
        if (old != null && host.isAlive(old)) {
            try {
                if (stopIt) {
                    old.exit();
                }
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "OpenHome player did not shut down cleanly", e);
            }
        }
    }

    private void applyMode() {
        AbstractPlayer current = player;
        if (current == null) {
            return;
        }
        PlayMode mode = PlayMode.of(repeat ? PlayMode.RepeatMode.ALL : PlayMode.RepeatMode.OFF, shuffle);
        lastKnownMode = mode;
        current.setPlayMode(mode);
    }

    /** The user changed repeat or shuffle at the player: the controllers are told. */
    private void adoptPlayMode(AbstractPlayer current) {
        PlayMode mode = current.getPlayMode();
        if (mode == null || mode.equals(lastKnownMode)) {
            core.setRepeat(repeat);
            core.setShuffle(shuffle);
            return;
        }
        lastKnownMode = mode;
        repeat = mode.getRepeat() != PlayMode.RepeatMode.OFF;
        shuffle = mode.isShuffle();
        core.setRepeat(repeat);
        core.setShuffle(shuffle);
    }

    /** For tests: the player the queue plays on, null if there is none. */
    AbstractPlayer playerForTest() {
        synchronized (lock) {
            return player;
        }
    }
}
