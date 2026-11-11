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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import java.util.UUID;
import java.util.function.Function;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.feed.FeedError;
import de.yaacc.multiroom.feed.FeedTapChain;
import de.yaacc.multiroom.feed.PcmOnlyAudioSink;
import de.yaacc.multiroom.feed.PcmSink;
import de.yaacc.multiroom.feed.SnapcastFeedController;
import de.yaacc.multiroom.feed.SnapcastStreams;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Plays the music of the local player into a Snapcast group (experimental, task 4.4): the phone reads and decodes the
 * queue itself, exactly like {@link LocalMediaSessionPlayer} (queue, play mode, seek, positions, notification), but
 * the decoded audio does not go to the phone's speaker. ExoPlayer is built with an audio sink whose processor chain
 * ({@link FeedTapChain}) hands the audio, converted to 48000 Hz 16 bit stereo, to the feed of the
 * {@link SnapcastFeedController} and sends silence on to the speaker.
 *
 * <p>This class is the glue; the coordination (stream, group, peer address, connection, pause, stop, idle, failures) is
 * in the controller, which is tested without Android. What this class does:
 * <ul>
 *   <li>ExoPlayer starts only after the controller reported a feed that is open ({@link #startItem},
 *   {@link #doResume}); a failure is shown as a toast with a typed text and stops the player.</li>
 *   <li>A pause of ExoPlayer, from wherever it comes, discards what the feed has queued; a resume of ExoPlayer from
 *   outside while the feed was closed (a long pause) opens the feed again (the first moment of audio is lost).</li>
 *   <li>A stop closes the feed and gives the group its stream back; destroying the player also removes the stream.</li>
 *   <li>The player's volume is the group's volume on the server; the phone's own volume is never touched.</li>
 * </ul>
 *
 * <p><b>Ids.</b> {@link #getId()} (the notification id) is random, as for the AVTransport players, and {@link
 * #getDeviceId()} is the receiver id of the group so that the receiver rows find their player. Nothing here logs a
 * receiver id, an address or a name.
 */
@UnstableApi
public class SnapcastPlayer extends LocalMediaSessionPlayer {

    private final String deviceId;
    private final int playerId = SnapcastPlayerLogic.playerIdFrom(UUID.randomUUID().hashCode());
    private volatile SnapcastFeedController controller;
    private volatile boolean delayNoted;
    private volatile boolean stopOutstanding;
    /** The item whose start was held back because the user paused while the feed was opening. */
    private volatile Deferred deferred;

    private static final class Deferred {
        final PlayableItem item;
        final Object loaded;
        final int index;

        Deferred(PlayableItem item, Object loaded, int index) {
            this.item = item;
            this.loaded = loaded;
            this.index = index;
        }
    }

    // the tap is built before the controller can exist for sure (the service connects on another thread)
    private final PcmSink tapSink = new PcmSink() {
        @Override
        public boolean write(byte[] data, int offset, int length) {
            SnapcastFeedController current = controller;
            return current != null && current.sink().write(data, offset, length);
        }

        @Override
        public void discard() {
            SnapcastFeedController current = controller;
            if (current != null) {
                current.sink().discard();
            }
        }
    };

    private final SnapcastFeedController.Listener feedListener = new SnapcastFeedController.Listener() {
        @Override
        public void onFeedLost(FeedError error) {
            feedFailed(error);
        }

        @Override
        public void onLog(String line) {
            YaaccLogger.d(SnapcastPlayer.class.getName(), "feed: " + line);
        }
    };

    /**
     * @param device            the group this player plays into
     * @param controllerFactory makes the controller of the feed; it gets the listener of this player
     */
    public SnapcastPlayer(UpnpClient upnpClient, SnapcastGroupDevice device, String name, String shortName,
                          Function<SnapcastFeedController.Listener, SnapcastFeedController> controllerFactory) {
        super(upnpClient, name, shortName);
        this.deviceId = device.getIdentity().getUdn().getIdentifierString();
        this.controller = controllerFactory.apply(feedListener);
    }

    // ---- ExoPlayer with the tap

    @Override
    protected ExoPlayer createExoPlayer() {
        Context context = getContext();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                // the processors of the builder sit behind the sink's own trimming, channel mapping and 16 bit
                // conversion and in front of silence skipping and speed, see docs/tech.md "Snapcast feed tap"
                AudioProcessor[] chain = FeedTapChain.create(SnapcastStreams.FORMAT, tapSink, false);
                return newSink(context, chain);
            }
        };
        ExoPlayer player = new ExoPlayer.Builder(context, renderers)
                .setWakeMode(C.WAKE_MODE_NETWORK) // the feed needs the CPU and the Wi-Fi while the screen is off
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                SnapcastFeedController current = controller;
                if (current == null) {
                    return;
                }
                switch (SnapcastPlayerLogic.onPlayWhenReadyChanged(playWhenReady, current.isFeeding())) {
                    case DISCARD:
                        current.pause();
                        break;
                    case START_FEED:
                        current.start(startAnswer(null, null));
                        break;
                    default:
                        break;
                }
            }
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                // only the name of the code: the message of a source error can hold a url
                playerFailed(error.getErrorCodeName());
            }
        });
        return player;
    }

    /** ExoPlayer failed (a source that can not be read, a track the tap refuses): close the feed, tell the user, stop. */
    void playerFailed(String errorCodeName) {
        YaaccLogger.d(getClass().getName(), "ExoPlayer error: " + errorCodeName);
        feedFailed(FeedError.of(FeedError.Kind.PLAYER_FAILED));
    }

    /**
     * The sink of the player: the processors of the tap, behind a sink that takes raw PCM only (see
     * {@link PcmOnlyAudioSink}: why pass-through must be refused, and why the audio capabilities of the builder can not
     * do it). Offload stays off (ExoPlayer's default; nothing here enables it, and the sink refuses it too).
     */
    static AudioSink newSink(Context context, AudioProcessor[] chain) {
        return new PcmOnlyAudioSink(new DefaultAudioSink.Builder(context).setAudioProcessors(chain).build());
    }

    // ---- transport

    @Override
    protected void startItem(final PlayableItem playableItem, final Object loadedItem, final int index) {
        SnapcastFeedController current = controller;
        if (current == null) {
            return; // destroyed
        }
        deferred = null;
        // always through the controller, which answers at once when the feed is open: a stop that is queued but has not
        // run yet is then ordered before this start, and the feed is made again
        current.start(startAnswer(() -> startOnExoPlayer(playableItem, loadedItem, index),
                () -> deferred = new Deferred(playableItem, loadedItem, index)));
    }

    @Override
    protected void doResume() {
        SnapcastFeedController current = controller;
        if (current == null) {
            return;
        }
        Deferred waiting = deferred;
        if (waiting != null) {
            // the user paused while the first start was in progress: ExoPlayer never started the item, start it now
            startItem(waiting.item, waiting.loaded, waiting.index);
            return;
        }
        current.start(startAnswer(this::resumeOnExoPlayer, null));
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        deferred = null;
        super.stopItem(playableItem);
        SnapcastFeedController current = controller;
        if (current != null) {
            current.stop();
        }
    }

    @Override
    public void onDestroy() {
        SnapcastFeedController current = controller;
        controller = null;
        if (current != null) {
            current.release();
        }
        super.onDestroy();
    }

    /** What ExoPlayer does to start an item; separate so that a test can see when it is called. */
    void startOnExoPlayer(PlayableItem playableItem, Object loadedItem, int index) {
        super.startItem(playableItem, loadedItem, index);
    }

    /** What ExoPlayer does to resume; separate for the same reason. */
    void resumeOnExoPlayer() {
        super.doResume();
    }

    /**
     * The callback of a start: go on with {@code whenReady} (if any), or tell the user and stop. A pause that came while
     * the feed was opening is honoured: ExoPlayer is not started (the feed stays open for the resume) and
     * {@code whenPaused} remembers what was to be started.
     */
    SnapcastFeedController.StartCallback startAnswer(final Runnable whenReady, final Runnable whenPaused) {
        return new SnapcastFeedController.StartCallback() {
            @Override
            public void onReady() {
                noteDelayOnce();
                if (isPaused()) {
                    if (whenPaused != null) {
                        whenPaused.run();
                    }
                    return;
                }
                if (whenReady != null) {
                    whenReady.run();
                }
            }

            @Override
            public void onFailed(FeedError error) {
                feedFailed(error);
            }
        };
    }

    private void noteDelayOnce() {
        if (!delayNoted) {
            delayNoted = true;
            toast(getContext().getString(R.string.snapcast_play_delay_note));
        }
    }

    /** Tells the user and stops the player; the controller has undone everything it did. */
    void feedFailed(FeedError error) {
        final Context context = getContext();
        YaaccLogger.d(getClass().getName(), "feed failed: " + error.getKind());
        toast(SnapcastPlayerLogic.errorMessage(error, context::getString));
        postToMain(() -> stopWhenFree(0));
    }

    /**
     * {@code stop()} is ignored while another command of the player runs; the failure would then leave a player that
     * shows "playing" with a closed feed. So it is tried again a little later, a second at most.
     */
    void stopWhenFree(int attempt) {
        if (isProcessingCommand() && attempt < STOP_RETRIES) {
            postToMainDelayed(() -> stopWhenFree(attempt + 1), STOP_RETRY_MILLIS);
            return;
        }
        boolean busy = isProcessingCommand();
        stop(); // clears the flag like every user action
        if (busy) {
            // the last try was ignored as well. Say so, and remember it until the user does something
            YaaccLogger.d(getClass().getName(), "stop after a failure was not possible, a command was still running");
            stopOutstanding = true;
        }
    }

    /** True when a stop after a failure could not be carried out; the next user action clears it. */
    boolean isStopOutstanding() {
        return stopOutstanding;
    }

    @Override
    public void play() {
        stopOutstanding = false;
        super.play();
    }

    @Override
    public void pause() {
        stopOutstanding = false;
        super.pause();
    }

    @Override
    public void stop() {
        stopOutstanding = false;
        super.stop();
    }

    @Override
    public void next() {
        stopOutstanding = false;
        super.next();
    }

    @Override
    public void previous() {
        stopOutstanding = false;
        super.previous();
    }

    private static final int STOP_RETRIES = 20;
    private static final long STOP_RETRY_MILLIS = 50L;

    private void toast(final String text) {
        postToMain(() -> showToast(text));
    }

    /** A long toast; a test records the text instead. */
    void showToast(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
    }

    /** Runs on the main thread (a toast and ExoPlayer need it); a test runs the work at once. */
    void postToMain(Runnable work) {
        new Handler(Looper.getMainLooper()).post(work);
    }

    /** As {@link #postToMain}, after a delay; a test runs the work at once or collects it. */
    void postToMainDelayed(Runnable work, long delayMillis) {
        new Handler(Looper.getMainLooper()).postDelayed(work, delayMillis);
    }

    /** The sink that the audio tap of ExoPlayer writes to (a test writes to it directly). */
    PcmSink tapSink() {
        return tapSink;
    }

    // ---- identity

    @Override
    protected int getNotificationId() {
        return playerId;
    }

    @Override
    public String getDeviceId() {
        return deviceId;
    }

    // ---- the volume is the group's

    @Override
    public int getVolume() {
        SnapcastFeedController current = controller;
        return current == null ? 0 : current.getVolume();
    }

    @Override
    public void setVolume(int volume) {
        SnapcastFeedController current = controller;
        if (current != null) {
            current.setVolume(volume);
        }
    }

    @Override
    public boolean getMute() {
        SnapcastFeedController current = controller;
        return current != null && current.isMuted();
    }

    @Override
    public void setMute(boolean mute) {
        SnapcastFeedController current = controller;
        if (current != null) {
            current.setMuted(mute);
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
}
