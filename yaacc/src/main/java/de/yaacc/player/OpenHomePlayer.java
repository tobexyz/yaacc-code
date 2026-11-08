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

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.content.Intent;
import android.net.Uri;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;
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
import de.yaacc.Yaacc;
import de.yaacc.openhome.OhControl;
import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhRemote;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.openhome.OhUriPolicy;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.openhome.ClingOhControl;
import de.yaacc.upnp.callback.openhome.OhPlayableItems;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadCacheHandler;
import de.yaacc.util.image.ImageDownloader;

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
    private List<Long> deviceIds = new ArrayList<>();
    private final Map<Long, PlayableItem> itemsById = new HashMap<>();

    // read by the screen
    private volatile OhRemote.State state = new OhRemote.State();
    private volatile long stateTakenAt = System.currentTimeMillis();
    private volatile boolean unreachable;
    private ScheduledFuture<?> poll;
    private final java.util.concurrent.atomic.AtomicBoolean pollPending = new java.util.concurrent.atomic.AtomicBoolean();

    public OpenHomePlayer(UpnpClient upnpClient, Device<?, ?, ?> receiverDevice, String name, String shortName) {
        super(upnpClient);
        this.device = receiverDevice;
        this.deviceId = receiverDevice.getIdentity().getUdn().getIdentifierString();
        setName(name);
        setShortName(shortName);
        poll = timer.scheduleWithFixedDelay(() -> {
            // a slow device must not pile up polls behind the one that is still running
            if (pollPending.compareAndSet(false, true)) {
                post(() -> {
                    try {
                        refresh();
                    } finally {
                        pollPending.set(false);
                    }
                });
            }
        }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Takes up a device that plays already (started by another controller, or by this app before it was reinstalled): the player
     * reads the playlist and the state of the device and shows them. Nothing is sent to the device: it is not woken up, its source
     * is not changed (that happens with the first command of the user).
     */
    public void attach() {
        post(() -> {
            if (remote == null) {
                remote = new OhRemote(createControl());
            }
            refresh();
        });
    }

    // ---- the worker

    private interface Job {
        void run() throws OhControlException;
    }

    /** @return false if the player is gone and the work was dropped */
    private boolean post(Runnable runnable) {
        try {
            commands.execute(runnable);
            return true;
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(getClass().getName(), "OpenHome player is gone, work dropped");
            return false;
        }
    }

    /** Runs the job on the worker, then asks the device what it does now. */
    private void command(String what, Job job) {
        command(what, job, null);
    }

    /** As {@link #command(String, Job)}; {@code always} runs after the job, whatever happened to it (also if it never ran). */
    private void command(String what, Job job, Runnable always) {
        boolean posted = post(() -> {
            try {
                ensureReady();
                job.run();
                failures = 0;
            } catch (OhControlException e) {
                YaaccLogger.w(getClass().getName(), "OpenHome " + what + " failed: " + e.getMessage());
                countFailure(e);
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "OpenHome " + what + " failed", e);
            } finally {
                if (always != null) {
                    always.run();
                }
            }
            refresh();
        });
        if (!posted && always != null) {
            always.run();
        }
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
        if (remote == null || pendingEdits.get() > 0) {
            return;
        }
        try {
            List<Long> newIds = remote.idArray();
            List<OhQueue.Entry> entries = null;
            // compared with what the device reported last time, not with what could be shown (tracks the app did not take in)
            if (!newIds.equals(deviceIds)) {
                entries = remote.queue();
                deviceIds = newIds;
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
            feedFiles();
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
        List<PlayableItem> list;
        int index = 0;
        fileQueueActive = fileQueue != null;
        fileSentForScreen = fileSent;
        if (fileQueue != null) {
            // the picked files: the screen shows all of them, also the ones the device does not have yet
            list = new ArrayList<>(fileQueue);
            Integer current = fileIndexById.get(state.id);
            index = current != null ? current : Math.max(0, fileCurrent);
        } else {
            list = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                list.add(itemsById.get(ids.get(i)));
                if (ids.get(i) == state.id) {
                    index = i;
                }
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
    public void playItemAt(int index) {
        command("play item", () -> {
            if (fileQueue != null && index >= 0 && index < fileQueue.size()) {
                // a file of the picked queue: shared and given to the device now, whichever it was before
                if (sendFiles(index)) {
                    remote.play();
                }
                return;
            }
            List<Long> current = ids;
            if (index >= 0 && index < current.size()) {
                remote.seekId(current.get(index));
            }
        });
    }

    @Override
    public void previous() {
        command("previous", () -> {
            if (fileQueue != null && fileCurrent > 0) {
                // the share of the file before is gone: shared again and given to the device
                if (sendFiles(fileCurrent - 1)) {
                    remote.play();
                }
            } else {
                remote.previous();
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
        final List<PlayableItem> list = java.util.Arrays.asList(playableItems);
        if (getShareLease() != null && !list.isEmpty()) {
            startFiles(new ArrayList<>(list));
            return;
        }
        final List<OhRemote.Track> tracks = tracks(list);
        if (tracks.isEmpty()) {
            // nothing the device could play: its queue stays as it is
            return;
        }
        command("set playlist", () -> {
            leaveFileMode();
            remote.replace(tracks);
        });
    }

    // ---- files picked on this phone: shared with the device just in time (the current and the next file), as for AVTransport

    // worker only
    private List<PlayableItem> fileQueue;
    // the same for the screen's thread
    private volatile boolean fileQueueActive;
    private volatile int fileSentForScreen;
    private final Map<Long, Integer> fileIndexById = new HashMap<>();
    private int fileSent;
    private int fileCurrent = -1;

    /**
     * The files of the picked queue: the screen shows all of them; the device gets the first two (the share of a file exists while
     * it plays and while it is next), {@link #feedFiles} adds each following file when the device moves on.
     */
    private void startFiles(List<PlayableItem> files) {
        command("set files", () -> {
            fileQueue = files;
            fileCurrent = -1;
            if (!sendFiles(0)) {
                leaveFileMode();
            }
        });
    }

    /** Back to the plain queue of the device: the picked files are not followed any more. */
    private void leaveFileMode() {
        fileQueue = null;
        fileIndexById.clear();
        fileCurrent = -1;
        fileSent = 0;
    }

    /**
     * Shares the file at the index and the one after it and puts them into the queue of the device, instead of what is there.
     *
     * @return false when the share of the file could not be created
     */
    private boolean sendFiles(int index) throws OhControlException {
        PlayableItem first = fileQueue.get(index);
        PlayableItem second = index + 1 < fileQueue.size() ? fileQueue.get(index + 1) : null;
        if (!getShareLease().moveToItems(first, second)) {
            YaaccLogger.w(getClass().getName(), "The share of a file could not be created");
            return false;
        }
        List<PlayableItem> toSend = new ArrayList<>();
        toSend.add(first);
        if (second != null) {
            toSend.add(second);
        }
        List<Integer> indexes = new ArrayList<>();
        List<OhRemote.Track> tracks = new ArrayList<>();
        for (int i = 0; i < toSend.size(); i++) {
            List<OhRemote.Track> one = tracks(java.util.Collections.singletonList(toSend.get(i)));
            if (!one.isEmpty()) {
                tracks.add(one.get(0));
                indexes.add(index + i);
            }
        }
        fileSent = index + toSend.size();
        fileIndexById.clear();
        fileCurrent = -1;
        if (tracks.isEmpty()) {
            return false;
        }
        List<Long> ids = remote.replace(tracks);
        for (int k = 0; k < ids.size() && k < indexes.size(); k++) {
            fileIndexById.put(ids.get(k), indexes.get(k));
        }
        return true;
    }

    /** When the device plays another of our files: shares it and the one after it, and gives the device that next file. */
    private void feedFiles() throws OhControlException {
        if (fileQueue == null) {
            return;
        }
        Integer current = fileIndexById.get(state.id);
        if (current == null || current == fileCurrent) {
            return;
        }
        PlayableItem next = current + 1 < fileQueue.size() ? fileQueue.get(current + 1) : null;
        if (!getShareLease().moveToItems(fileQueue.get(current), next)) {
            YaaccLogger.w(getClass().getName(), "The share of a file could not be created");
            fileCurrent = current;
            return;
        }
        if (next != null && fileSent <= current + 1) {
            List<OhRemote.Track> one = tracks(java.util.Collections.singletonList(next));
            if (!one.isEmpty()) {
                // a failure leaves fileCurrent and fileSent as they were: the next poll tries again
                List<Long> ids = remote.append(one);
                if (!ids.isEmpty()) {
                    fileIndexById.put(ids.get(0), current + 1);
                }
            }
            fileSent = current + 2;
        }
        fileCurrent = current;
    }

    @Override
    public void addItems(List<PlayableItem> playableItems) {
        final List<PlayableItem> added = new ArrayList<>(playableItems);
        if (getShareLease() != null && fileQueueActive) {
            // the picked files go on: the new items come after them
            command("add to playlist", () -> {
                if (fileQueue != null) {
                    fileQueue.addAll(added);
                } else {
                    appendTracks(added);
                }
            });
            return;
        }
        final List<OhRemote.Track> tracks = tracks(added);
        if (tracks.isEmpty()) {
            return;
        }
        command("add to playlist", () -> remote.append(tracks));
    }

    private void appendTracks(List<PlayableItem> items) throws OhControlException {
        List<OhRemote.Track> tracks = tracks(items);
        if (!tracks.isEmpty()) {
            remote.append(tracks);
        }
    }

    @Override
    public void clear() {
        command("clear playlist", () -> {
            leaveFileMode();
            remote.deleteAll();
        });
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
        if (fileQueueActive) {
            // a file that the device has not got yet only leaves the list; the ones it has are the device's
            command("delete file", () -> {
                if (fileQueue != null && itemIndex >= fileSent && itemIndex < fileQueue.size()) {
                    fileQueue.remove(itemIndex);
                }
            });
            return itemIndex >= fileSentForScreen;
        }
        command("delete track", () -> {
            List<Long> current = ids;
            if (itemIndex < current.size()) {
                remote.deleteId(current.get(itemIndex));
            }
        });
        return true;
    }

    /**
     * The device decides what it plays next: nothing of the playlist is locked, except the picked files it was given already (a
     * file the device has not got yet can still be taken out of the list).
     */
    @Override
    public boolean isPlaylistPositionLocked(int position) {
        return fileQueueActive && position < fileSentForScreen;
    }

    // edits of the order that the screen shows already and the worker has not done yet: the poll leaves the list alone meanwhile
    private final java.util.concurrent.atomic.AtomicInteger pendingEdits = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Moves a track of the playlist (what a drag in the list does). The list shows the new order at once, the device is told on
     * the worker. The track that plays, and in a picked queue the files the device has, stay where they are.
     */
    @Override
    public boolean moveInPlayOrder(int fromPosition, int toPosition) {
        List<PlayableItem> list = new ArrayList<>(getItems());
        if (fromPosition == toPosition || fromPosition < 0 || toPosition < 0 || fromPosition >= list.size()
                || toPosition >= list.size()) {
            return false;
        }
        if (fileQueueActive) {
            if (fromPosition < fileSentForScreen || toPosition < fileSentForScreen) {
                return false; // the device has these files
            }
        } else if (fromPosition == getCurrentItemIndex() && (isPlaying() || isPaused())) {
            return false; // a track that plays is not moved
        }
        PlayableItem playing = getCurrentItemIndex() < list.size() ? list.get(getCurrentItemIndex()) : null;
        list.add(toPosition, list.remove(fromPosition));
        pendingEdits.incrementAndGet();
        adoptDeviceState(list, Math.max(0, list.indexOf(playing)), isPlaying(), isPaused());
        command("move track", () -> {
            if (fileQueue != null) {
                fileQueue.add(toPosition, fileQueue.remove(fromPosition));
                return;
            }
            List<Long> current = new ArrayList<>(ids);
            if (fromPosition >= current.size() || toPosition >= current.size()) {
                return;
            }
            long id = current.remove(fromPosition);
            long movedId = remote.moveTrack(id, toPosition == 0 ? 0 : current.get(toPosition - 1));
            // the next drag, queued before the poll looks again, counts from this order (a device with Playlist 1 gives the track a new id)
            current.add(toPosition, movedId);
            ids = current;
        }, pendingEdits::decrementAndGet);
        return true;
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

    /**
     * Opens the player screen (the one of the AVTransport player: it shows and controls any player with its playlist, volume,
     * repeat and shuffle). Used by the notification, the receiver rows and the mini player.
     */
    @Override
    public PendingIntent getNotificationIntent() {
        Intent notificationIntent = new Intent(getContext(), AVTransportPlayerActivity.class);
        // makes the intents of different players differ, so each keeps its own player id
        notificationIntent.setData(Uri.parse("http://0.0.0.0/" + getId() + ""));
        notificationIntent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getActivity(getContext(), 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
    }

    // ---- the album art of the track the device plays (the address is in the DIDL-Lite text of the queue entry)

    /**
     * The album art of the current track: the {@code albumArtURI} of its metadata. The address comes from the device, so only
     * http and https addresses are used.
     */
    @Override
    public URI getAlbumArt() {
        List<PlayableItem> list = getItems();
        int index = getCurrentItemIndex();
        if (index < 0 || index >= list.size() || list.get(index) == null || list.get(index).getItem() == null) {
            return null;
        }
        DIDLObject.Property<URI> property = list.get(index).getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        URI uri = property == null ? null : property.getValue();
        return uri != null && OhUriPolicy.isAcceptable(uri.toString()) ? uri : null;
    }

    /** The notification shows the album art when it is in the cache; it is fetched for the next update when it is not. */
    @Override
    public Bitmap getIcon() {
        URI albumArt = getAlbumArt();
        if (albumArt != null) {
            final android.net.Uri artUri = android.net.Uri.parse(albumArt.toString());
            IconDownloadCacheHandler cache = IconDownloadCacheHandler.getInstance();
            Bitmap bitmap = cache.getBitmap(artUri, 512, 512);
            if (bitmap != null) {
                return bitmap;
            }
            if (!artMayBeFetched(artUri.toString())) {
                return super.getIcon();
            }
            ((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor().execute(() -> {
                try {
                    Bitmap loaded = new ImageDownloader().retrieveImageWithCertainSize(artUri, 512, 512);
                    if (loaded != null) {
                        cache.addBitmap(artUri, 512, 512, loaded);
                        updateMetadataInternal();
                    }
                } catch (RuntimeException e) {
                    YaaccLogger.w(getClass().getName(), "OpenHome album art not loaded", e);
                }
            });
        }
        return super.getIcon();
    }

    private static final long ART_RETRY_MILLIS = 60_000;
    private static final int ART_ATTEMPTS_KEPT = 32;
    // when the download of an album art address was started last: the screen asks for the icon again and again, and an address that
    // does not answer must not start a download every time
    private final java.util.Map<String, Long> artAttempts = new java.util.LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
            return size() > ART_ATTEMPTS_KEPT;
        }
    };

    boolean artMayBeFetched(String address) {
        long now = System.currentTimeMillis();
        synchronized (artAttempts) {
            Long last = artAttempts.get(address);
            if (last != null && now - last < ART_RETRY_MILLIS) {
                return false;
            }
            artAttempts.put(address, now);
            return true;
        }
    }

    /** The name of the device this player plays on, for the player screen. */
    public String getDeviceName() {
        return device.getDetails() == null ? null : device.getDetails().getFriendlyName();
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

    /** The playlist belongs to the device: closing the player ends the screen and the poll, the device keeps playing. */
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
