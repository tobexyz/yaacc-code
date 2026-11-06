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
package de.yaacc.player;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.openhome.OhControl;
import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhRemote;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.openhome.ClingOhControl;
import de.yaacc.upnp.callback.openhome.OhPlayableItems;
import de.yaacc.util.YaaccLogger;

/**
 * Plays on an OpenHome device and lets the device own the playlist: the queue of the device is the playlist this player shows and
 * edits (insert, delete), the transport buttons go to the device, and what the device reports (track, state, position, volume,
 * repeat, shuffle) is what the screen shows. The player holds no queue of its own.
 * <p>
 * All calls to the device run one after the other on one worker thread; the callers only hand their work over. The device is asked
 * for its state about every two seconds and after every command.
 */
public class OpenHomePlayer extends AbstractPlayer {
    private static final long POLL_SECONDS = 2;
    private static final int UNREACHABLE_AFTER_FAILURES = 3;

    private final String deviceId;
    private final Device<?, ?, ?> device;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhome-player");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhome-player-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final int notificationId = Math.abs(java.util.UUID.randomUUID().hashCode());

    // worker only
    private OhRemote remote;
    private boolean ready;
    private int failures;
    private List<Long> ids = new ArrayList<>();
    private final Map<Long, PlayableItem> itemsById = new HashMap<>();

    // read by the screen
    private volatile OhRemote.State state = new OhRemote.State();
    private volatile long stateTakenAt = System.currentTimeMillis();
    private volatile boolean unreachable;
    private ScheduledFuture<?> poll;

    public OpenHomePlayer(UpnpClient upnpClient, Device<?, ?, ?> receiverDevice, String name, String shortName) {
        super(upnpClient);
        this.device = receiverDevice;
        this.deviceId = receiverDevice.getIdentity().getUdn().getIdentifierString();
        setName(name);
        setShortName(shortName);
        poll = timer.scheduleWithFixedDelay(() -> post(this::refresh), POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    // ---- the worker

    private interface Job {
        void run() throws OhControlException;
    }

    private void post(Runnable runnable) {
        try {
            commands.execute(runnable);
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(getClass().getName(), "OpenHome player is gone, work dropped");
        }
    }

    /** Runs the job on the worker, then asks the device what it does now. */
    private void command(String what, Job job) {
        post(() -> {
            try {
                ensureReady();
                job.run();
                failures = 0;
            } catch (OhControlException e) {
                YaaccLogger.w(getClass().getName(), "OpenHome " + what + " failed: " + e.getMessage());
                countFailure(e);
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "OpenHome " + what + " failed", e);
            }
            refresh();
        });
    }

    private void ensureReady() throws OhControlException {
        if (remote == null) {
            remote = new OhRemote(createControl());
        }
        if (!ready) {
            remote.prepare();
            ready = true;
        }
    }

    /** The way to the device; tests replace it. */
    protected OhControl createControl() {
        UpnpClient client = getUpnpClient();
        return new ClingOhControl(device, client == null ? null : client.getHttpRequestSender());
    }

    private void countFailure(OhControlException e) {
        if (e.getCode() == OhControlException.NO_ANSWER && ++failures >= UNREACHABLE_AFTER_FAILURES) {
            unreachable = true;
            ready = false; // wake the device and select the playlist again when it answers
        }
    }

    /** Reads the queue and the state of the device and hands them to the screen. */
    private void refresh() {
        if (remote == null) {
            return;
        }
        try {
            List<Long> newIds = remote.idArray();
            List<OhQueue.Entry> entries = null;
            if (!newIds.equals(ids)) {
                entries = remote.queue();
            }
            OhRemote.State newState = remote.readState();
            failures = 0;
            unreachable = false;
            if (entries != null) {
                rebuildItems(entries);
            }
            state = newState;
            stateTakenAt = System.currentTimeMillis();
            publish();
        } catch (OhControlException e) {
            countFailure(e);
            if (unreachable) {
                publish();
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "OpenHome refresh failed", e);
        }
    }

    private void rebuildItems(List<OhQueue.Entry> entries) {
        int defaultDuration = getUpnpClient() == null ? 0 : getUpnpClient().getDefaultDuration();
        List<Long> newIds = new ArrayList<>(entries.size());
        Map<Long, PlayableItem> keep = new HashMap<>();
        for (OhQueue.Entry entry : entries) {
            PlayableItem item = itemsById.get(entry.getId());
            if (item == null) {
                item = OhPlayableItems.fromTrack(entry.getUri(), entry.getMetadata(), defaultDuration);
            }
            keep.put(entry.getId(), item);
            newIds.add(entry.getId());
        }
        itemsById.clear();
        itemsById.putAll(keep);
        ids = newIds;
    }

    private void publish() {
        List<PlayableItem> list = new ArrayList<>(ids.size());
        int index = 0;
        for (int i = 0; i < ids.size(); i++) {
            list.add(itemsById.get(ids.get(i)));
            if (ids.get(i) == state.id) {
                index = i;
            }
        }
        OhRemote.State current = state;
        boolean playing = current.transportState == OhTransportState.PLAYING || current.transportState == OhTransportState.BUFFERING;
        boolean pausedNow = current.transportState == OhTransportState.PAUSED;
        adoptDeviceState(list, index, playing, pausedNow);
        PlayMode deviceMode = PlayMode.of(current.repeat ? PlayMode.RepeatMode.ALL : PlayMode.RepeatMode.OFF, current.shuffle);
        PlayMode mode = getPlayMode();
        boolean sameAsAsked = mode.isShuffle() == deviceMode.isShuffle()
                && (mode.getRepeat() != PlayMode.RepeatMode.OFF) == (deviceMode.getRepeat() != PlayMode.RepeatMode.OFF);
        if (!sameAsAsked) {
            super.setPlayMode(deviceMode);
        }
    }

    /** True once the device did not answer several times in a row; the screen can say so. */
    public boolean isUnreachable() {
        return unreachable;
    }

    // ---- the transport

    @Override
    public void play() {
        command("play", () -> remote.play());
    }

    @Override
    public void pause() {
        command("pause", () -> remote.pause());
    }

    @Override
    public void stop() {
        command("stop", () -> remote.stop());
    }

    @Override
    public void next() {
        command("next", () -> remote.next());
    }

    @Override
    public void previous() {
        command("previous", () -> remote.previous());
    }

    @Override
    public void playItemAt(int index) {
        command("play item", () -> {
            List<Long> current = ids;
            if (index >= 0 && index < current.size()) {
                remote.seekId(current.get(index));
            }
        });
    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        command("seek", () -> remote.seekSecondAbsolute(millisecondsFromStart / 1000L));
    }

    @Override
    public void fastForward(int seconds) {
        seekTo(getCurrentPosition() + seconds * 1000L);
    }

    @Override
    public void fastRewind(int seconds) {
        seekTo(Math.max(0, getCurrentPosition() - seconds * 1000L));
    }

    // ---- the playlist: the queue of the device

    @Override
    public void setItems(PlayableItem... playableItems) {
        final List<OhRemote.Track> tracks = tracks(java.util.Arrays.asList(playableItems));
        if (tracks.isEmpty()) {
            // nothing the device could play: its queue stays as it is
            return;
        }
        command("set playlist", () -> remote.replace(tracks));
    }

    @Override
    public void addItems(List<PlayableItem> playableItems) {
        final List<OhRemote.Track> tracks = tracks(playableItems);
        if (tracks.isEmpty()) {
            return;
        }
        command("add to playlist", () -> remote.append(tracks));
    }

    @Override
    public void clear() {
        command("clear playlist", () -> remote.deleteAll());
    }

    @Override
    public boolean removeItem(int itemIndex) {
        return removeItem(itemIndex, false);
    }

    @Override
    public boolean removeItem(int itemIndex, boolean alsoPlayed) {
        if (itemIndex < 0 || itemIndex >= getItems().size()) {
            return false;
        }
        command("delete track", () -> {
            List<Long> current = ids;
            if (itemIndex < current.size()) {
                remote.deleteId(current.get(itemIndex));
            }
        });
        return true;
    }

    /** The device decides what it plays next: nothing of the playlist is locked. */
    @Override
    public boolean isPlaylistPositionLocked(int position) {
        return false;
    }

    /** Moving needs OpenHome Playlist version 2 or later and is not offered. */
    @Override
    public boolean moveInPlayOrder(int fromPosition, int toPosition) {
        return false;
    }

    @Override
    public void insertItems(int index, List<PlayableItem> playableItems) {
        // the queue grows at its end; a place in the middle is not offered to the user
        addItems(playableItems);
    }

    private static List<OhRemote.Track> tracks(List<PlayableItem> items) {
        List<OhRemote.Track> tracks = new ArrayList<>();
        for (PlayableItem item : items) {
            if (OhPlayableItems.isSendable(item)) {
                tracks.add(new OhRemote.Track(item.getUri().toString(), OhPlayableItems.metadataOf(item)));
            } else {
                // a device on the network can only fetch http(s) addresses; files of this phone are not offered yet
                YaaccLogger.d(OpenHomePlayer.class.getName(), "OpenHome: track skipped, no http(s) address");
            }
        }
        return tracks;
    }

    // ---- the play mode: repeat and shuffle are booleans on the device, repeat one shows as repeat all

    @Override
    public void setPlayMode(PlayMode mode) {
        super.setPlayMode(mode);
        final boolean repeat = mode.getRepeat() != PlayMode.RepeatMode.OFF;
        final boolean shuffle = mode.isShuffle();
        command("play mode", () -> {
            remote.setRepeat(repeat);
            remote.setShuffle(shuffle);
        });
    }

    // ---- position, volume

    @Override
    public long getCurrentPosition() {
        OhRemote.State current = state;
        long position = current.seconds * 1000L;
        if (current.transportState == OhTransportState.PLAYING) {
            position += Math.max(0, System.currentTimeMillis() - stateTakenAt);
            if (current.durationSeconds > 0) {
                position = Math.min(position, current.durationSeconds * 1000L);
            }
        }
        return position;
    }

    @Override
    public String getDuration() {
        return format(state.durationSeconds);
    }

    @Override
    public String getElapsedTime() {
        return format(getCurrentPosition() / 1000L);
    }

    private static String format(long seconds) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    @Override
    public int getVolume() {
        return state.volumePercent;
    }

    @Override
    public void setVolume(int volume) {
        final int percent = Math.max(0, Math.min(100, volume));
        command("volume", () -> remote.setVolumePercent(percent));
    }

    @Override
    public boolean getMute() {
        return state.muted;
    }

    @Override
    public void setMute(boolean mute) {
        command("mute", () -> remote.setMute(mute));
    }

    @Override
    public boolean hasActionGetVolume() {
        return true;
    }

    @Override
    public boolean hasActionGetMute() {
        return true;
    }

    // ---- the rest of a player

    @Override
    public String getDeviceId() {
        return deviceId;
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_devices_32;
    }

    @Override
    protected int getNotificationId() {
        return notificationId;
    }

    // The device plays: the player does not load or start items itself.
    @Override
    protected void stopItem(PlayableItem playableItem) {
    }

    @Override
    protected Object loadItem(PlayableItem playableItem) {
        return playableItem;
    }

    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem, int index) {
    }

    @Override
    public void onDestroy() {
        stopWorkers();
        super.onDestroy();
    }

    /** Ends the poll and the worker (queued commands still run). */
    void stopWorkers() {
        if (poll != null) {
            poll.cancel(false);
        }
        timer.shutdownNow();
        commands.shutdown();
    }
}
