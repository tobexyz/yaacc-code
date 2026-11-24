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

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;

import java.util.UUID;

import de.yaacc.multiroom.feed.FeedTapChain;
import de.yaacc.multiroom.feed.PcmFormat;
import de.yaacc.multiroom.feed.PcmSink;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.hub.PcmStreamer;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * The player that plays the queue of the app to a Sendspin player (issue #325), in the shape of {@link SnapcastPlayer}: ExoPlayer
 * plays as always but its audio does not go to the phone's speaker; a processor chain ({@link FeedTapChain}) converts what ExoPlayer
 * decodes to PCM 16 bit stereo at the rate the player listed and hands it to the {@link PcmStreamer} of the connection, which cuts it
 * into timestamped chunks, paces them and sends them. The streamer's wait is what keeps ExoPlayer at real time.
 * <ul>
 *   <li>A start waits for the connection to be ready. Playing to a player that is not approved is the operator's approval (spec:
 *       "an operator action that clearly means to use the client"); a player that demands pairing, lost its pairing or can not be
 *       given a stream says so in a message.</li>
 *   <li>A pause of ExoPlayer, from wherever it comes, ends the stream ({@code stream/end}); the resume starts it again. A seek flushes
 *       the tap, which sends {@code stream/clear}. A stop ends the stream.</li>
 *   <li>The volume and mute are the player's own (a command to the player); the phone's volume is never touched.</li>
 * </ul>
 * The rate of the tap is chosen when the player is made (ExoPlayer is built later, when the service connects), so the app makes the
 * player only when the rate of the Sendspin player is known; see {@code PlayerService}.
 */
@UnstableApi
public class SendspinPlayer extends LocalMediaSessionPlayer {

    private static final long READY_TIMEOUT_MILLIS = 12_000L;
    private static final long READY_POLL_MILLIS = 50L;

    private final String deviceId;
    private final Endpoint endpoint;
    private final SendspinHub hub;
    private final int sampleRate;
    private final int playerId = SnapcastPlayerLogic.playerIdFrom(UUID.randomUUID().hashCode());
    private volatile HubConnection connection;
    private volatile PcmStreamer streamer;
    private volatile boolean lostNoted;

    // the tap is built before the streamer can exist for sure (the service connects on another thread)
    private final PcmSink tapSink = new PcmSink() {
        @Override
        public boolean write(byte[] data, int offset, int length) {
            PcmStreamer current = streamer;
            if (current == null) {
                return false;
            }
            boolean ok = current.write(data, offset, length);
            if (!ok && current.hasFailed()) {
                connectionLost();
            }
            return ok;
        }

        @Override
        public void discard() {
            PcmStreamer current = streamer;
            if (current != null) {
                current.discard();
            }
        }
    };

    /**
     * @param device     the player this plays to
     * @param sampleRate the rate of its stream (from its hello); the tap converts to it
     */
    public SendspinPlayer(UpnpClient upnpClient, SendspinReceiverDevice device, int sampleRate, String name, String shortName) {
        super(upnpClient, name, shortName);
        this.deviceId = device.getReceiverId();
        this.endpoint = device.getEndpoint();
        this.sampleRate = sampleRate;
        this.hub = SendspinHub.getShared(upnpClient.getContext());
    }

    // ---- ExoPlayer with the tap

    @Override
    protected ExoPlayer createExoPlayer() {
        Context context = getContext();
        final PcmFormat target = new PcmFormat(sampleRate, 16, 2);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                AudioProcessor[] chain = FeedTapChain.create(target, tapSink, false);
                return SnapcastPlayer.newSink(context, chain);
            }
        };
        ExoPlayer player = new ExoPlayer.Builder(context, renderers)
                .setWakeMode(C.WAKE_MODE_NETWORK) // the stream needs the CPU and the Wi-Fi while the screen is off
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                PcmStreamer current = streamer;
                if (current == null) {
                    return;
                }
                if (SendspinPlayerLogic.onPlayWhenReadyChanged(playWhenReady) == SendspinPlayerLogic.StreamAction.END) {
                    current.end();
                } else {
                    current.begin();
                }
            }
        });
        return player;
    }

    @Override
    void stopAfterFailures(String errorCodeName) {
        YaaccLogger.d(getClass().getName(), "ExoPlayer error: " + errorCodeName);
        fail(SendspinPlayerLogic.Problem.LOST);
    }

    // ---- transport

    @Override
    protected void startItem(final PlayableItem playableItem, final Object loadedItem, final int index) {
        whenReady(() -> startOnExoPlayer(playableItem, loadedItem, index));
    }

    @Override
    protected void doResume() {
        whenReady(this::resumeOnExoPlayer);
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        super.stopItem(playableItem);
        PcmStreamer current = streamer;
        if (current != null) {
            current.end();
        }
    }

    @Override
    public void onDestroy() {
        PcmStreamer current = streamer;
        streamer = null;
        if (current != null) {
            current.end();
        }
        HubConnection c = connection;
        if (c != null) {
            c.stopPlayback(); // drops the playback activity so that another server can take the player
        }
        super.onDestroy();
    }

    void startOnExoPlayer(PlayableItem playableItem, Object loadedItem, int index) {
        super.startItem(playableItem, loadedItem, index);
    }

    void resumeOnExoPlayer() {
        super.doResume();
    }

    /**
     * Brings the connection up (made again if it ended; the user asked for this), waits for it to be ready, approves the player when
     * it waits for the operator, then runs {@code then} on the main thread with the streamer open for audio.
     */
    private void whenReady(final Runnable then) {
        final HubConnection c = hub.acquire(endpoint, true);
        if (c == null) {
            fail(SendspinPlayerLogic.Problem.NOT_READY); // a player that connected to this phone is gone and has to come back
            return;
        }
        connection = c;
        lostNoted = false;
        // playing is the operator's action: the first activation of a new connection then already declares 'playback', which wins
        // against another server that merely holds the player (an idle first activation would tie with it and lose)
        c.startPlayback();
        Thread waiter = new Thread(() -> {
            long deadline = System.nanoTime() + READY_TIMEOUT_MILLIS * 1_000_000L;
            boolean approved = false;
            boolean activated = false;
            while (true) {
                HubConnection.Phase phase = c.phase();
                SendspinPlayerLogic.Wait decision = SendspinPlayerLogic.waitFor(phase);
                if (decision == SendspinPlayerLogic.Wait.READY) {
                    final PcmStreamer made = c.streamer();
                    if (made == null) {
                        fail(SendspinPlayerLogic.Problem.NOT_READY);
                        return;
                    }
                    made.begin();
                    streamer = made;
                    postToMain(then);
                    return;
                }
                if (decision == SendspinPlayerLogic.Wait.FAIL) {
                    fail(SendspinPlayerLogic.problemOf(phase, c.endReason()));
                    return;
                }
                if (decision == SendspinPlayerLogic.Wait.ACTIVATE && !activated) {
                    activated = true;
                    c.startPlayback(); // the activity is declared only while this player plays to the player
                }
                if (decision == SendspinPlayerLogic.Wait.APPROVE && !approved) {
                    approved = true;
                    c.approve(); // playing to the player is the approval
                }
                if (System.nanoTime() > deadline) {
                    fail(SendspinPlayerLogic.Problem.NOT_READY);
                    return;
                }
                try {
                    Thread.sleep(READY_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "sendspin-player-start");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void connectionLost() {
        if (!lostNoted) {
            lostNoted = true;
            fail(SendspinPlayerLogic.Problem.LOST);
        }
    }

    /** Tells the user and stops the player. */
    void fail(SendspinPlayerLogic.Problem problem) {
        final Context context = getContext();
        YaaccLogger.d(getClass().getName(), "play failed: " + problem);
        final String text = context.getString(SendspinPlayerLogic.textFor(problem));
        postToMain(() -> {
            showToast(text);
            stopWhenFree(0);
        });
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

    // ---- the volume is the player's

    private de.yaacc.sendspin.session.SendspinMessages.PlayerState playerState() {
        HubConnection c = connection != null ? connection : hub.connection(endpoint);
        de.yaacc.sendspin.session.SendspinMessages.ClientState state = c == null ? null : c.state();
        return state == null ? null : state.player;
    }

    @Override
    public int getVolume() {
        de.yaacc.sendspin.session.SendspinMessages.PlayerState state = playerState();
        return state == null || state.volume == null ? 0 : state.volume;
    }

    @Override
    public void setVolume(int volume) {
        HubConnection c = connection != null ? connection : hub.connection(endpoint);
        if (c != null) {
            c.setVolume(volume);
        }
    }

    @Override
    public boolean getMute() {
        de.yaacc.sendspin.session.SendspinMessages.PlayerState state = playerState();
        return state != null && state.muted != null && state.muted;
    }

    @Override
    public void setMute(boolean mute) {
        HubConnection c = connection != null ? connection : hub.connection(endpoint);
        if (c != null) {
            c.setMuted(mute);
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
