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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import de.yaacc.openhome.OhError;
import de.yaacc.openhome.OhIds;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.player.AbstractPlayer;
import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayableItem;
import de.yaacc.util.YaaccLogger;

/**
 * Plays the OpenHome queue of the renderer on this device and reports what the player does back to the OpenHome services.
 * <p>
 * The queue ({@link OhRendererCore#queue()}) is the truth. The player holds the same tracks in the same order, created from the
 * queue when somebody plays. {@link #sync()} compares the two and makes the player's list match (it removes what is gone from the
 * queue and inserts what is new), so it does not matter in which order concurrent edits arrive and an edit that reaches the
 * renderer twice does nothing the second time. What the player does on its own (a track ends, the user pauses in the
 * notification) is found by {@link #refresh()}, which runs about once a second while the renderer is started.
 * <p>
 * Threads: everything the renderer does runs one after the other on one worker thread ({@link Threads}); the commands of the
 * control points only hand their work over and return. The player (ExoPlayer insists on it) is only touched on its own thread, the
 * worker waits for it, never the other way round.
 * <p>
 * The part that needs Android (creating the music player, turning a DIDL text into a playable item, the system volume, the
 * threads) is behind {@link Host}, {@link Volume} and {@link Threads}, so the logic runs in plain JVM tests.
 */
public final class OpenHomeRenderer implements OhPlayback {

    /** The app side of the renderer. */
    public interface Host {
        /** Creates a music player on this device that holds the items (in this order) and is not started. Called on the worker. */
        AbstractPlayer createPlayer(List<PlayableItem> items);

        /** @return false once the player was shut down by somebody else */
        boolean isAlive(AbstractPlayer player);

        /** Never null: unreadable metadata give an item that plays the address. */
        PlayableItem toPlayableItem(String uri, String metadata);

        /** @return the address of an item a control point can fetch, null if the queue cannot hold the item */
        String queueUriOf(PlayableItem item);

        /** @return the DIDL-Lite text of the item, empty if it has none */
        String queueMetadataOf(PlayableItem item);
    }

    /** The system volume of the device. */
    public interface Volume {
        int percent();

        boolean muted();

        void setPercent(int percent);

        void setMuted(boolean muted);
    }

    /** The threads of the renderer. */
    public interface Threads extends OhRendererCore.Delayer {
        /** Runs the work on the worker thread, after what was handed over before. Returns at once. */
        void work(Runnable work);

        /** Runs the work on the worker thread again and again; the result stops it. */
        Runnable every(Runnable work, long periodMillis);

        /** Runs the call on the player's thread and waits for it. A failure is thrown as a runtime exception. */
        <T> T onPlayer(Callable<T> call);

        /** Runs what is queued, then ends the worker. */
        void close();
    }

    /** What the player says about itself, read in one go on the player's thread. */
    private static final class PlayerState {
        boolean playing;
        boolean paused;
        int index;
        long positionMillis;
        String durationText;
        long itemDurationMillis;
        PlayMode mode;
        /** The items of the player, in its list order. */
        List<PlayableItem> items;
    }

    private static final long POLL_MILLIS = 1000;

    private final Host host;
    private final Volume volume;
    private final Threads threads;
    private final OhRendererCore core;

    // all of this is only touched on the worker
    private AbstractPlayer player;
    /** The ids of the tracks the player holds, in its order. */
    private final List<Long> playerIds = new ArrayList<>();
    /** The queue id of every item this renderer put into the player (by identity); items the user added at the player are not in. */
    private final Map<PlayableItem, Long> idByItem = new IdentityHashMap<>();
    private boolean repeat;
    private boolean shuffle;
    private PlayMode lastKnownMode;
    /** The track the next play starts with when there is no player, 0 for the first one. */
    private long startId;
    private long reportedId = Long.MIN_VALUE;
    private Runnable stopPolling;

    public OpenHomeRenderer(Host host, Volume volume, Threads threads, OhRendererCore.Details details, String protocolInfo) {
        this.host = host;
        this.volume = volume;
        this.threads = threads;
        this.core = new OhRendererCore(this, threads, details, protocolInfo);
    }

    public OhRendererCore core() {
        return core;
    }

    // ---- life cycle

    /** Starts the once-a-second look at the player. */
    public void start() {
        threads.work(() -> {
            if (stopPolling == null) {
                stopPolling = threads.every(this::refreshNow, POLL_MILLIS);
                refreshNow();
            }
        });
    }

    /** Ends the look at the player and the player itself. */
    public void shutdown() {
        threads.work(() -> {
            if (stopPolling != null) {
                stopPolling.run();
                stopPolling = null;
            }
            discardPlayer();
        });
        threads.close();
    }

    // ---- what the controller asks for (OhPlayback): hand over to the worker, return

    @Override
    public void play() {
        threads.work(() -> {
            sync();
            AbstractPlayer current = alivePlayer();
            if (current == null) {
                startFrom(startId);
            } else {
                PlayerState state = read(current);
                if (!state.playing || state.paused) {
                    onPlayer(current::play);
                }
            }
            refreshNow();
        });
    }

    @Override
    public void pause() {
        threads.work(() -> {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                PlayerState state = read(current);
                if (state.playing && !state.paused) {
                    onPlayer(current::pause);
                }
            }
            refreshNow();
        });
    }

    @Override
    public void stop() {
        threads.work(() -> {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                onPlayer(current::stop);
            }
            startId = 0;
            refreshNow();
        });
    }

    @Override
    public void next() {
        threads.work(() -> {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                onPlayer(current::next);
            }
            refreshNow();
        });
    }

    @Override
    public void previous() {
        threads.work(() -> {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                onPlayer(current::previous);
            }
            refreshNow();
        });
    }

    @Override
    public void setRepeat(boolean repeat) {
        threads.work(() -> {
            this.repeat = repeat;
            applyMode();
        });
    }

    @Override
    public void setShuffle(boolean shuffle) {
        threads.work(() -> {
            this.shuffle = shuffle;
            applyMode();
        });
    }

    @Override
    public void seekSecondAbsolute(long second) {
        threads.work(() -> {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                onPlayer(() -> current.seekTo(Math.max(0, second) * 1000L));
            }
        });
    }

    @Override
    public void seekSecondRelative(int seconds) {
        threads.work(() -> {
            AbstractPlayer current = alivePlayer();
            if (current != null) {
                long target = Math.max(0, read(current).positionMillis + seconds * 1000L);
                onPlayer(() -> current.seekTo(target));
            }
        });
    }

    @Override
    public void seekToTrack(long id) {
        threads.work(() -> {
            sync();
            if (core.queue().indexOf(id) < 0) {
                return;
            }
            AbstractPlayer current = alivePlayer();
            if (current == null) {
                startFrom(id);
            } else {
                int index = playerIds.indexOf(id);
                if (index >= 0) {
                    onPlayer(() -> current.playItemAt(index));
                }
            }
            refreshNow();
        });
    }

    @Override
    public void trackInserted(int index, OhQueue.Entry entry) {
        threads.work(() -> {
            sync();
            refreshNow();
        });
    }

    @Override
    public void trackDeleted(int index, long id) {
        threads.work(() -> {
            if (id == startId) {
                // where a new play starts when there is no player: the track that took the place of the deleted one
                startId = index < core.queue().size() ? core.queue().idAt(index) : 0;
            }
            sync();
            refreshNow();
        });
    }

    @Override
    public void queueCleared() {
        threads.work(() -> {
            sync();
            refreshNow();
        });
    }

    @Override
    public void setVolume(int percent) {
        threads.work(() -> {
            volume.setPercent(Math.max(0, Math.min(100, percent)));
            refreshNow();
        });
    }

    @Override
    public void setMute(boolean mute) {
        threads.work(() -> {
            volume.setMuted(mute);
            refreshNow();
        });
    }

    // ---- queue and player

    /**
     * Makes the player's list match the queue: removes the tracks that are not in the queue any more and inserts the new ones at
     * their place. The track that plays can not be removed: the player ends then and the next play starts with the track behind it.
     */
    private void sync() {
        List<OhQueue.Entry> wanted = core.queue().snapshot();
        if (wanted.isEmpty()) {
            discardPlayer();
            startId = 0;
            return;
        }
        AbstractPlayer current = alivePlayer();
        if (current == null) {
            return;
        }
        try {
            // what the user changed at the player comes first: reconcile works by place and must not see a list it did not make
            adoptPlayerEdits(current, onPlayer(() -> new ArrayList<>(current.getItems())));
            if (player == null) {
                return;
            }
            wanted = core.queue().snapshot();
            if (wanted.isEmpty()) {
                discardPlayer();
                startId = 0;
                return;
            }
            reconcile(current, wanted);
        } catch (RuntimeException e) {
            // a player call failed or timed out: what the player holds is not known any more, so it starts again from the queue
            YaaccLogger.w(getClass().getName(), "OpenHome player and queue could not be matched, the player is started again", e);
            discardPlayer();
        }
    }

    private void reconcile(AbstractPlayer current, List<OhQueue.Entry> wanted) {
        Set<Long> wantedIds = new HashSet<>();
        for (OhQueue.Entry entry : wanted) {
            wantedIds.add(entry.getId());
        }
        for (int i = playerIds.size() - 1; i >= 0; i--) {
            if (wantedIds.contains(playerIds.get(i))) {
                continue;
            }
            final int index = i;
            if (onPlayer(() -> current.removeItem(index, true))) {
                // by value: the map compares the boxed ids by reference, which is wrong for ids above 127
                long gone = playerIds.remove(i);
                idByItem.values().removeIf(id -> id == gone);
            } else {
                long next = 0;
                for (int j = i + 1; j < playerIds.size(); j++) {
                    if (wantedIds.contains(playerIds.get(j))) {
                        next = playerIds.get(j);
                        break;
                    }
                }
                discardPlayer();
                startId = next;
                return;
            }
        }
        for (int i = 0; i < wanted.size(); i++) {
            OhQueue.Entry entry = wanted.get(i);
            if (i < playerIds.size() && playerIds.get(i) == entry.getId()) {
                continue;
            }
            final int index = i;
            final PlayableItem item = host.toPlayableItem(entry.getUri(), entry.getMetadata());
            onPlayer(() -> {
                current.insertItems(index, Collections.singletonList(item));
                return null;
            });
            playerIds.add(i, entry.getId());
            idByItem.put(item, entry.getId());
        }
    }

    /** Creates the player with the whole queue and starts it with the track (the first one for 0 or an unknown id). */
    private void startFrom(long id) {
        List<OhQueue.Entry> entries = core.queue().snapshot();
        if (entries.isEmpty()) {
            return;
        }
        List<PlayableItem> items = new ArrayList<>(entries.size());
        List<Long> ids = new ArrayList<>(entries.size());
        Map<PlayableItem, Long> idsByItem = new IdentityHashMap<>();
        for (OhQueue.Entry entry : entries) {
            PlayableItem item = host.toPlayableItem(entry.getUri(), entry.getMetadata());
            items.add(item);
            ids.add(entry.getId());
            idsByItem.put(item, entry.getId());
        }
        AbstractPlayer created = host.createPlayer(items);
        if (created == null) {
            YaaccLogger.w(getClass().getName(), "no player for the OpenHome queue");
            return;
        }
        player = created;
        playerIds.clear();
        playerIds.addAll(ids);
        idByItem.clear();
        idByItem.putAll(idsByItem);
        reportedId = Long.MIN_VALUE;
        lastKnownMode = null;
        applyMode();
        int index = id == 0 ? -1 : ids.indexOf(id);
        if (index < 0) {
            onPlayer(created::play);
        } else {
            onPlayer(() -> created.playItemAt(index));
        }
        startId = 0;
    }

    /**
     * The user changed the list at the player of this device (deleted, moved or added tracks in the playlist of the music player):
     * the queue takes over, so the control points see it and the next {@link #sync()} does not undo it. Only compares the player
     * with what this renderer put into it, so edits of control points that are not synced yet are left alone.
     *
     * @return true if the player had other items than the renderer expected
     */
    private boolean adoptPlayerEdits(AbstractPlayer current, List<PlayableItem> items) {
        List<Long> found = new ArrayList<>(items.size());
        boolean unknown = false;
        for (PlayableItem item : items) {
            Long id = idByItem.get(item);
            found.add(id);
            unknown |= id == null;
        }
        if (!unknown && found.equals(playerIds)) {
            return false;
        }
        try {
            takeOverFromPlayer(current, items, found);
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "OpenHome queue could not take over the edits of the player, the player is started again", e);
            discardPlayer();
        }
        return true;
    }

    private void takeOverFromPlayer(AbstractPlayer current, List<PlayableItem> items, List<Long> found) {
        OhQueue queue = core.queue();
        // 1. tracks the user deleted
        Set<Long> kept = new HashSet<>();
        for (Long id : found) {
            if (id != null) {
                kept.add(id);
            }
        }
        for (Long id : new ArrayList<>(playerIds)) {
            if (!kept.contains(id)) {
                deleteQuietly(queue, id);
            }
        }
        // 2. tracks the user moved: the order of the kept tracks in the queue becomes the one of the player
        List<Long> order = new ArrayList<>();
        Set<Long> orderSet = new HashSet<>();
        for (Long id : found) {
            if (id != null && queue.indexOf(id) >= 0 && orderSet.add(id)) { // an item twice in the player counts once
                order.add(id);
            }
        }
        for (int i = 0; i < order.size(); i++) {
            List<Long> inQueue = new ArrayList<>();
            for (long id : queue.idArray()) {
                if (orderSet.contains(id)) {
                    inQueue.add(id);
                }
            }
            if (inQueue.get(i).longValue() != order.get(i).longValue()) {
                queue.move(order.get(i), i == 0 ? OhIds.NONE : order.get(i - 1));
            }
        }
        // 3. tracks the user added: behind their predecessor; what the queue cannot hold leaves the player again
        List<Integer> refused = new ArrayList<>();
        long previous = OhIds.NONE;
        List<Long> newIds = new ArrayList<>();
        Set<PlayableItem> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < items.size(); i++) {
            Long id = found.get(i);
            if (!seen.add(items.get(i))) {
                // the same item twice in the player: only one place can have its queue id, the repeat leaves the player again
                refused.add(i);
                continue;
            }
            // a known track stays known although a control point deleted it from the queue meanwhile: the sync takes it out of the player
            if (id == null) {
                PlayableItem item = items.get(i);
                String uri = host.queueUriOf(item);
                id = null;
                if (uri != null) {
                    try {
                        id = queue.insert(previous, uri, host.queueMetadataOf(item));
                        idByItem.put(item, id);
                    } catch (OhError e) {
                        YaaccLogger.w(getClass().getName(), "OpenHome queue refuses a track of the player: " + e.getMessage());
                    }
                }
                if (id == null) {
                    refused.add(i);
                    continue;
                }
            }
            newIds.add(id);
            if (queue.indexOf(id) >= 0) {
                previous = id; // a track a control point deleted meanwhile is no place to insert behind
            }
        }
        for (int i = refused.size() - 1; i >= 0; i--) {
            final int index = refused.get(i);
            if (!onPlayer(() -> current.removeItem(index, true))) {
                // the track that plays can not leave the player
                discardPlayer();
                core.queueEditedByPlayer();
                return;
            }
        }
        playerIds.clear();
        playerIds.addAll(newIds);
        Set<PlayableItem> inPlayer = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        inPlayer.addAll(items);
        idByItem.keySet().removeIf(item -> !inPlayer.contains(item));
        core.queueEditedByPlayer();
    }

    private static void deleteQuietly(OhQueue queue, long id) {
        try {
            queue.delete(id);
        } catch (OhError e) {
            // a control point deleted it as well
        }
    }

    private AbstractPlayer alivePlayer() {
        if (player != null && !host.isAlive(player)) {
            player = null;
            playerIds.clear();
            idByItem.clear();
            reportedId = Long.MIN_VALUE;
        }
        return player;
    }

    private void discardPlayer() {
        AbstractPlayer old = player;
        player = null;
        playerIds.clear();
        idByItem.clear();
        reportedId = Long.MIN_VALUE;
        if (old != null && host.isAlive(old)) {
            try {
                onPlayer(old::exit);
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
        onPlayer(() -> current.setPlayMode(mode));
    }

    /** The user changed repeat or shuffle at the player: the controllers are told. */
    private void adoptPlayMode(PlayMode mode) {
        if (mode != null && !mode.equals(lastKnownMode)) {
            lastKnownMode = mode;
            repeat = mode.getRepeat() != PlayMode.RepeatMode.OFF;
            shuffle = mode.isShuffle();
        }
        core.setRepeat(repeat);
        core.setShuffle(shuffle);
    }

    // ---- the player's thread

    private <T> T onPlayer(Callable<T> call) {
        return threads.onPlayer(call);
    }

    private void onPlayer(Runnable call) {
        threads.onPlayer(() -> {
            call.run();
            return null;
        });
    }

    private PlayerState read(AbstractPlayer current) {
        return onPlayer(() -> {
            PlayerState state = new PlayerState();
            state.playing = current.isPlaying();
            state.paused = current.isPaused();
            state.index = current.getCurrentItemIndex();
            state.positionMillis = current.getCurrentPosition();
            state.durationText = current.getDuration();
            List<PlayableItem> items = current.getItems();
            state.itemDurationMillis = state.index >= 0 && state.index < items.size() ? items.get(state.index).getDuration() : 0;
            state.mode = current.getPlayMode();
            state.items = new ArrayList<>(items);
            return state;
        });
    }

    // ---- what the player does

    /** Reports the state of the player to the OpenHome services: called by the poll and after every command (on the worker). */
    public void refresh() {
        threads.work(this::refreshNow);
    }

    private void refreshNow() {
        try {
            reportVolume();
            AbstractPlayer current = alivePlayer();
            if (current == null) {
                core.setTransportState(OhTransportState.STOPPED);
                core.setCurrentId(core.queue().size() == 0 ? 0 : startId);
                return;
            }
            PlayerState state = read(current);
            if (adoptPlayerEdits(current, state.items)) {
                if (alivePlayer() == null) {
                    refreshNow();
                    return;
                }
                state = read(current);
            }
            adoptPlayMode(state.mode);
            if (!state.playing && !state.paused) {
                core.setTransportState(OhTransportState.STOPPED);
                return;
            }
            long id = state.index >= 0 && state.index < playerIds.size() ? playerIds.get(state.index) : 0;
            long duration = durationSeconds(state);
            if (id != reportedId && id != 0) {
                try {
                    OhQueue.Entry entry = core.queue().read(id);
                    reportedId = id;
                    core.setCurrentId(id);
                    core.setTrack(entry.getUri(), entry.getMetadata(), duration);
                } catch (RuntimeException e) {
                    // the track was deleted a moment ago; the next sync takes care of it
                    YaaccLogger.d(getClass().getName(), "current OpenHome track not in the queue any more");
                }
            }
            core.setDuration(duration);
            core.setSeconds(Math.max(0, state.positionMillis) / 1000L);
            core.setTransportState(state.paused ? OhTransportState.PAUSED : OhTransportState.PLAYING);
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "OpenHome renderer refresh failed", e);
        }
    }

    private void reportVolume() {
        core.setVolumePercent(volume.percent());
        core.setMuted(volume.muted());
    }

    private static long durationSeconds(PlayerState state) {
        long fromText = parseSeconds(state.durationText);
        if (fromText > 0) {
            return fromText;
        }
        return state.itemDurationMillis > 0 ? state.itemDurationMillis / 1000L : 0;
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

    /** For tests (on the worker, or with threads that run inline): the player the queue plays on, null if there is none. */
    AbstractPlayer playerForTest() {
        return player;
    }

    /** For tests: how many items of the player the renderer knows the queue id of. */
    int trackedItemsForTest() {
        return idByItem.size();
    }
}
