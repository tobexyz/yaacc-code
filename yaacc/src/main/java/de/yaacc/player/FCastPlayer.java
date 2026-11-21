/*
 *
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
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.yaacc.BuildConfig;
import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.fcast.FCastPlayRequests;
import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.fcast.protocol.FCastConnection;
import de.yaacc.fcast.protocol.FCastMessages;
import de.yaacc.fcast.protocol.FCastReceiverState;
import de.yaacc.fcast.protocol.FCastSession;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadCacheHandler;
import de.yaacc.util.image.ImageDownloader;

/**
 * Plays on an FCast receiver (task 4.3, design.md section 5). The receiver fetches the URL of an item itself; this player sends
 * one {@code Play} per item and drives the queue (next, previous, repeat, shuffle, play list) like the players of the other
 * receivers. What the receiver reports (state, position, volume, the end of an item) comes from the {@link FCastSession}; the
 * decisions are in {@link FCastPlayerLogic}.
 *
 * <ul>
 *   <li><b>End of an item:</b> audio and video advance when the receiver reports the end (event, paused at the duration, or the
 *   silence rule of the logic). A picture advances by the slide time like for other receivers; the timer of the base class is used
 *   for pictures only, a timer for audio or video would race with the end the receiver reports.</li>
 *   <li><b>The receiver's volume is left alone</b> when an item starts: the {@code Play} carries no volume.</li>
 *   <li><b>Mute</b> is emulated (D10), see {@link FCastPlayerLogic}.</li>
 *   <li><b>Leaving:</b> destroying the player stops the item on the receiver, waits briefly for the receiver's answer and then
 *   closes the session, so no connection is left.</li>
 *   <li>Nothing here logs a URL, a title or an address.</li>
 * </ul>
 *
 * <p>The ids: {@link #getId()} (the notification id) is random, {@link #getDeviceId()} is the receiver id {@code fcast:host:port}.
 */
public class FCastPlayer extends AbstractPlayer {

    private static final String TAG = FCastPlayer.class.getName();
    private static final long TICK_SECONDS = 1;
    /** How long a command of the user may keep the player busy before an event of the receiver is retried (15 times). */
    private static final long BUSY_RETRY_MILLIS = 200;
    private static final int BUSY_RETRIES = 15;
    private static final long CLOSE_WAIT_MILLIS = 2_000;

    private final String deviceId;
    private final FCastEndpoint endpoint;
    private final MonotonicClock clock = MonotonicClock.SYSTEM;
    private final int notificationId = Math.abs(UUID.randomUUID().hashCode());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fcast-player");
        thread.setDaemon(true);
        return thread;
    });
    private final FCastPlayerLogic logic = new FCastPlayerLogic(clock);
    private final FCastSession session;
    private final ScheduledFuture<?> tick;

    private volatile boolean destroyed;
    private volatile boolean pausedLocally;
    /** True from the Play of an item until our Stop: something of ours is on the receiver. */
    private volatile boolean itemOnReceiver;
    private volatile double speed = 1.0;
    private volatile long resumeAtMillis;

    // executor thread only
    private int handledErrorSerial;
    private Double lastVolume;

    // the art of the notification: one attempt per address and minute
    private String artAttemptAddress;
    private long artAttemptAt;

    public FCastPlayer(UpnpClient upnpClient, FCastReceiverDevice receiver, String name, String shortName) {
        super(upnpClient);
        setName(name);
        setShortName(shortName);
        this.deviceId = receiver.getReceiverId();
        this.endpoint = receiver.getEndpoint();
        this.session = createSession(new SessionListener(), executor);
        this.session.acquire();
        this.tick = executor.scheduleWithFixedDelay(this::onTick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** The session of this player; tests replace the way to the receiver. */
    protected FCastSession createSession(FCastSession.Listener listener, ScheduledExecutorService executor) {
        final FCastEndpoint target = endpoint;
        FCastSession.Connector connector = connectionListener -> FCastSession.connectorFor(
                new InetSocketAddress(target.getHost(), target.getPort()), new FCastConnection.Config(), clock,
                "YAACC", "YAACC", BuildConfig.VERSION_NAME).connect(connectionListener);
        return new FCastSession(connector, listener, new FCastSession.Config(), clock, executor);
    }

    /** The session, for the settings sheet and the tests. */
    public FCastSession getSession() {
        return session;
    }

    // ------------------------------------------------------------------ what the session reports

    private final class SessionListener implements FCastSession.Listener {
        @Override
        public void onStateChanged(FCastReceiverState state) {
            handleState(state);
        }

        @Override
        public void onPlaybackError(String message) {
            itemFailed(R.string.fcast_play_failed);
        }

        @Override
        public void onMediaItemEvent(FCastMessages.Event event) {
            act(logic.onEvent(event.getType(), event.getItemUrl()));
        }
    }

    private void handleState(FCastReceiverState state) {
        if (destroyed) {
            return;
        }
        Double volume = state.getVolume();
        if (volume != null && !volume.equals(lastVolume)) {
            lastVolume = volume;
            logic.onVolumeUpdate(volume);
        }
        if (state.getLink() == FCastReceiverState.Link.FAILED && state.getErrorSerial() != handledErrorSerial) {
            handledErrorSerial = state.getErrorSerial();
            YaaccLogger.d(TAG, "FCast receiver not reachable");
            toast(R.string.fcast_unreachable);
            logic.onStopCommand();
            itemOnReceiver = false;
            whenFree(this::stop, 0);
            return;
        }
        act(logic.onState(state));
    }

    private void onTick() {
        if (!destroyed) {
            act(logic.onTick(session.getState()));
        }
    }

    private void act(FCastPlayerLogic.Action action) {
        switch (action) {
            case ITEM_ENDED:
                whenFree(this::onItemEnded, 0);
                break;
            case STOPPED_BY_RECEIVER:
                itemOnReceiver = false;
                whenFree(this::stop, 0);
                break;
            case EXTERNAL_PAUSE:
                pausedLocally = true;
                adoptExternalPause();
                break;
            case EXTERNAL_RESUME:
                pausedLocally = false;
                adoptExternalResume();
                break;
            default:
                break;
        }
    }

    /**
     * Runs the work when no command of the user is running: the base class ignores a skip or a stop while it is busy, and an end
     * that the receiver reports only once must not be lost.
     */
    private void whenFree(final Runnable work, final int attempt) {
        if (destroyed) {
            return;
        }
        if (!isProcessingCommand()) {
            try {
                work.run();
            } catch (RuntimeException e) {
                YaaccLogger.w(TAG, "FCast player action failed (" + e.getClass().getSimpleName() + ")");
            }
            return;
        }
        if (attempt >= BUSY_RETRIES) {
            YaaccLogger.d(TAG, "FCast player stayed busy, an action was dropped");
            return;
        }
        try {
            executor.schedule(() -> whenFree(work, attempt + 1), BUSY_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the player is gone
        }
    }

    /** The current item can not be played (the receiver says so, or the request does not fit): skip it or stop. */
    private void itemFailed(final int textRes) {
        try {
            executor.execute(() -> {
                boolean hasNext = getNextItemIndex() != PlayOrder.NONE;
                itemOnReceiver = false;
                toast(textRes);
                if (logic.onError(hasNext) == TrackFailurePolicy.Decision.SKIP) {
                    whenFree(this::next, 0);
                } else {
                    whenFree(this::stop, 0);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the player is gone
        }
    }

    // ------------------------------------------------------------------ transport

    @Override
    protected Object loadItem(PlayableItem playableItem) {
        return playableItem;
    }

    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem, int index) {
        if (playableItem == null || destroyed) {
            return;
        }
        Uri uri = playableItem.getUri();
        FCastPlayRequests.Result result = FCastPlayRequests.build(uri == null ? null : uri.toString(), playableItem.getMimeType(), null,
                playableItem.getTitle(), artAddress(playableItem), resumeAtMillis / 1000.0, Double.NaN);
        resumeAtMillis = 0;
        if (result.getRequest() == null) {
            YaaccLogger.d(TAG, "FCast item refused: " + result.getRefusal());
            itemFailed(result.getRefusal() == FCastPlayRequests.Refusal.TOO_LARGE ? R.string.fcast_item_too_large : R.string.fcast_play_failed);
            return;
        }
        if (speed != 1.0) {
            result.getRequest().speed(speed);
        }
        pausedLocally = false;
        itemOnReceiver = true;
        logic.onPlayCommand(uri.toString());
        session.play(result.getRequest());
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        logic.onStopCommand();
        pausedLocally = false;
        resumeAtMillis = 0;
        if (itemOnReceiver) {
            itemOnReceiver = false;
            session.stop();
        }
    }

    @Override
    protected void doPause() {
        if (isImage(currentItem())) {
            return; // a picture has nothing to pause on the receiver; the slide timer is stopped by the base class
        }
        logic.onPauseCommand();
        pausedLocally = true;
        session.pause();
    }

    @Override
    protected void doResume() {
        pausedLocally = false;
        PlayableItem item = currentItem();
        if (!isImage(item) && session.getState().getPlayback() == FCastReceiverState.Playback.PAUSED) {
            logic.onResumeCommand();
            session.resume();
            return;
        }
        // a picture, or an item the receiver does not hold any more: show it again (audio and video from where it was)
        if (!isImage(item)) {
            resumeAtMillis = getCurrentPosition();
        }
        super.doResume();
    }

    @Override
    protected void doPostLoadItem(PlayableItem playableItem) {
        // only a picture advances by a timer; the end of audio and video is reported by the receiver
        if (isImage(playableItem)) {
            super.doPostLoadItem(playableItem);
        }
    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        if (isImage(currentItem())) {
            return;
        }
        session.seek(FCastPlayerLogic.seekSeconds(millisecondsFromStart, session.getState().getDuration()));
    }

    /** The playback speed of the running item and of the following ones (the settings sheet, 0.25 to 4). */
    public void setSpeed(double newSpeed) {
        speed = Math.max(FCastMessages.MIN_SPEED, Math.min(FCastMessages.MAX_SPEED, newSpeed));
        session.setSpeed(speed);
    }

    private PlayableItem currentItem() {
        List<PlayableItem> list = getItems();
        int index = getCurrentItemIndex();
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private static boolean isImage(PlayableItem item) {
        return item != null && FCastPlayerLogic.isImage(item.getMimeType());
    }

    private static String artAddress(PlayableItem item) {
        URI art = item.getItem() == null ? null : AlbumArt.of(item.getItem());
        return art == null ? null : art.toString();
    }

    // ------------------------------------------------------------------ position, volume

    @Override
    public long getCurrentPosition() {
        return FCastPlayerLogic.positionMillis(session.getState(), clock.nowMillis(), pausedLocally);
    }

    @Override
    public String getDuration() {
        long millis = FCastPlayerLogic.durationMillis(session.getState());
        return format(millis > 0 ? millis / 1000 : 0);
    }

    @Override
    public String getElapsedTime() {
        return format(getCurrentPosition() / 1000);
    }

    private static String format(long seconds) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    @Override
    public int getVolume() {
        return FCastPlayerLogic.toPercent(session.getState().getVolume());
    }

    @Override
    public void setVolume(int volume) {
        session.setVolume(FCastPlayerLogic.toProtocolVolume(volume));
    }

    @Override
    public boolean getMute() {
        return logic.isMuted(session.getState().getVolume());
    }

    @Override
    public void setMute(boolean mute) {
        double volume = logic.onMuteRequested(mute, session.getState().getVolume());
        if (!Double.isNaN(volume)) {
            session.setVolume(volume);
        }
    }

    @Override
    public boolean hasActionGetVolume() {
        return true;
    }

    @Override
    public boolean hasActionGetMute() {
        return true;
    }

    // ------------------------------------------------------------------ identity, notification

    @Override
    protected int getNotificationId() {
        return notificationId;
    }

    @Override
    public String getDeviceId() {
        return deviceId;
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_devices_32;
    }

    @Override
    public PendingIntent getNotificationIntent() {
        Intent notificationIntent = new Intent(getContext(), AVTransportPlayerActivity.class);
        // makes the intents of different players differ, so each keeps its own player id
        notificationIntent.setData(Uri.parse("http://0.0.0.0/" + getId() + ""));
        notificationIntent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getActivity(getContext(), 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
    }

    @Override
    public URI getAlbumArt() {
        PlayableItem item = currentItem();
        return item == null || item.getItem() == null ? null : AlbumArt.of(item.getItem());
    }

    /** The notification shows the art when it is in the cache; it is fetched once a minute at most per address when it is not. */
    @Override
    public Bitmap getIcon() {
        URI albumArt = getAlbumArt();
        if (albumArt != null) {
            final Uri artUri = Uri.parse(albumArt.toString());
            IconDownloadCacheHandler cache = IconDownloadCacheHandler.getInstance();
            Bitmap bitmap = cache.getBitmap(artUri, 512, 512);
            if (bitmap != null) {
                return bitmap;
            }
            if (artMayBeFetched(artUri.toString())) {
                ((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor().execute(() -> {
                    try {
                        Bitmap loaded = new ImageDownloader().retrieveImageWithCertainSize(artUri, 512, 512);
                        if (loaded != null) {
                            cache.addBitmap(artUri, 512, 512, loaded);
                            updateMetadataInternal();
                        }
                    } catch (RuntimeException e) {
                        YaaccLogger.w(TAG, "FCast album art not loaded (" + e.getClass().getSimpleName() + ")");
                    }
                });
            }
        }
        return super.getIcon();
    }

    private synchronized boolean artMayBeFetched(String address) {
        long now = System.currentTimeMillis();
        if (address.equals(artAttemptAddress) && now - artAttemptAt < 60_000) {
            return false;
        }
        artAttemptAddress = address;
        artAttemptAt = now;
        return true;
    }

    // ------------------------------------------------------------------ leaving

    @Override
    public void onDestroy() {
        destroyed = true;
        tick.cancel(false);
        if (itemOnReceiver) {
            itemOnReceiver = false;
            logic.onStopCommand();
            session.stop();
        }
        super.onDestroy();
        session.release();
        closeSessionSoon(clock.nowMillis() + CLOSE_WAIT_MILLIS);
    }

    /** Closes the session once the receiver answered our Stop (or after two seconds): no connection is left behind. */
    private void closeSessionSoon(final long deadline) {
        try {
            executor.schedule(() -> {
                FCastReceiverState state = session.getState();
                boolean waiting = state.getLink() == FCastReceiverState.Link.CONNECTED
                        && state.getPending() == FCastReceiverState.Pending.STOP && clock.nowMillis() < deadline;
                if (waiting) {
                    closeSessionSoon(deadline);
                    return;
                }
                session.close();
                executor.shutdown();
            }, 100, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            session.close();
        }
    }

    /** For tests: stops the threads of the player. */
    void stopWorkers() {
        destroyed = true;
        tick.cancel(false);
        session.close();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ messages

    /** Shows a short text; tests replace it. */
    protected void showToast(final int textRes) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context.getApplicationContext(), textRes, Toast.LENGTH_LONG).show());
    }

    private void toast(int textRes) {
        try {
            showToast(textRes);
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "FCast message not shown (" + e.getClass().getSimpleName() + ")");
        }
    }
}
