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
package de.yaacc.fcast.protocol;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.util.YaaccLogger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The use of one FCast receiver by the app (design.md, section 2). It owns the connection, makes it when a command needs it,
 * and keeps {@link FCastReceiverState}: <b>the state is what the receiver reported</b>. A command ({@link #play},
 * {@link #pause} ...) never changes the playback fields; it sets a {@link FCastReceiverState.Pending} marker that the receiver's
 * answer, or {@code pendingTimeoutMillis}, clears. What another sender does with the same receiver shows through the same
 * messages (an item that this session did not start is flagged {@code itemFromElsewhere}); the session never fights it.
 *
 * <p><b>Who uses it.</b> {@link #acquire()} and {@link #release()} count the users (a player, an open settings sheet). The
 * connection is made on the first command and is closed {@code idleCloseMillis} after the last user left and the last
 * command (decision D11: an idle card has no connection). While somebody uses the receiver and the connection is lost for any
 * reason but our own close, the session retries <b>once</b> after {@code reconnectDelayMillis}; a retry that fails, or a second loss
 * before the connection was stable for {@code stableMillis}, ends in {@code FAILED} with the reason as text and no further
 * attempt (the player reports the error and stops). A connection lost with nobody using it just goes {@code IDLE}.
 *
 * <p><b>Threads.</b> Everything runs on the single-thread executor that is passed in (also the listener calls, which must not
 * block); the methods may be called from any thread. {@link #getState()} is a volatile read.
 */
public final class FCastSession implements AutoCloseable {

    /** Makes a connected {@link FCastConnection} that reports to the given listener. Called on the session's thread. */
    public interface Connector {
        FCastConnection connect(FCastConnection.Listener listener) throws IOException;
    }

    /** What happens to the receiver, in order, on the session's thread. */
    public interface Listener {
        /** Called after every change of the state (also each position update). */
        void onStateChanged(FCastReceiverState state);

        /** The receiver reported a playback error (its text is cleaned and cut). */
        default void onPlaybackError(String message) {
        }

        /** The receiver says an item was started (by us, by another sender, or by the receiver). */
        default void onPlayUpdate(FCastMessages.PlayUpdate update, FCastReceiverState state) {
        }

        /** A subscribed event (media item start, end or change) arrived. */
        default void onMediaItemEvent(FCastMessages.Event event) {
        }
    }

    /** Times; injectable so tests do not wait. */
    public static final class Config {
        public long idleCloseMillis = 30_000;
        public long reconnectDelayMillis = 1_000;
        public long stableMillis = 10_000;
        public long pendingTimeoutMillis = 3_000;
    }

    private static final String TAG = FCastSession.class.getName();

    /** The connector of the app: one TCP connection to the receiver with these settings. */
    public static Connector connectorFor(final InetSocketAddress target, final FCastConnection.Config config, final MonotonicClock clock,
                                         final String senderName, final String appName, final String appVersion) {
        return listener -> {
            FCastConnection connection = new FCastConnection(target, listener, config, clock, senderName, appName, appVersion);
            connection.connect();
            return connection;
        };
    }

    private final Connector connector;
    private final Listener listener;
    private final Config config;
    private final MonotonicClock clock;
    private final ScheduledExecutorService executor;

    private volatile FCastReceiverState state = FCastReceiverState.INITIAL;
    private volatile FCastConnection connection;
    private volatile boolean closed;

    // the following are touched on the executor thread only
    private int generation;
    private int users;
    private long lastActivity;
    private long connectedAt;
    private int retriesInRow;
    private String lastPlayedUrl;
    private ScheduledFuture<?> idleTask;
    private ScheduledFuture<?> pendingTask;
    private int pendingToken;

    public FCastSession(Connector connector, Listener listener, Config config, MonotonicClock clock, ScheduledExecutorService executor) {
        this.connector = connector;
        this.listener = listener;
        this.config = config;
        this.clock = clock;
        this.executor = executor;
    }

    /** The state as of now. */
    public FCastReceiverState getState() {
        return state;
    }

    // ------------------------------------------------------------------ users

    /** Somebody (a player, an open sheet) uses the receiver: the connection is kept and a lost one is retried once. */
    public void acquire() {
        post(() -> {
            users++;
            touch();
            cancelIdle();
        });
    }

    /** The user is done; the connection closes after the idle time when nobody else is using the receiver. */
    public void release() {
        post(() -> {
            users = Math.max(0, users - 1);
            touch();
            armIdle();
        });
    }

    // ------------------------------------------------------------------ commands

    /** Plays the item; sets {@code Pending.PLAY} until the receiver reports. The URL is sent as given. */
    public void play(final FCastMessages.PlayRequest request) {
        post(() -> command(FCastOpcode.PLAY, FCastReceiverState.Pending.PLAY, request::toJson, request.getUrl()));
    }

    public void pause() {
        post(() -> command(FCastOpcode.PAUSE, FCastReceiverState.Pending.PAUSE, () -> null, null));
    }

    public void resume() {
        post(() -> command(FCastOpcode.RESUME, FCastReceiverState.Pending.RESUME, () -> null, null));
    }

    public void stop() {
        post(() -> command(FCastOpcode.STOP, FCastReceiverState.Pending.STOP, () -> null, null));
    }

    public void seek(final double seconds) {
        post(() -> command(FCastOpcode.SEEK, FCastReceiverState.Pending.SEEK, () -> FCastMessages.seek(seconds), null));
    }

    /** {@code volume} 0 to 1. The volume field of the state changes when the receiver says so. */
    public void setVolume(final double volume) {
        post(() -> command(FCastOpcode.SET_VOLUME, FCastReceiverState.Pending.NONE, () -> FCastMessages.setVolume(volume), null));
    }

    /** Asks for a speed (a receiver of version 1 refuses: the error says so). */
    public void setSpeed(final double speed) {
        post(() -> command(FCastOpcode.SET_SPEED, FCastReceiverState.Pending.NONE, () -> FCastMessages.setSpeed(speed), null));
    }

    /** Subscribes to an event type, e.g. {@link FCastMessages#EVENT_MEDIA_ITEM_END} (version 3 receivers only). */
    public void subscribe(final int eventType) {
        post(() -> command(FCastOpcode.SUBSCRIBE_EVENT, FCastReceiverState.Pending.NONE, () -> FCastMessages.subscribeEvent(eventType), null));
    }

    /** Builds the body of a command (may refuse). */
    private interface Body {
        String get() throws FCastProtocolException;
    }

    private void command(FCastOpcode opcode, FCastReceiverState.Pending pending, Body body, String playedUrl) {
        if (closed) {
            return;
        }
        touch();
        String text;
        try {
            text = body.get(); // a message that can not be built is refused before a connection is made for it
        } catch (FCastProtocolException e) {
            fail(e.getMessage());
            return;
        }
        if (!ensureConnected()) {
            return;
        }
        try {
            if (!connection.send(opcode, text)) {
                // the connection closed under us; its close event follows and decides what happens next
                return;
            }
            if (playedUrl != null) {
                lastPlayedUrl = playedUrl;
            }
            if (pending != FCastReceiverState.Pending.NONE) {
                setPending(pending);
            }
        } catch (FCastProtocolException e) {
            fail(e.getMessage());
        }
        armIdle();
    }

    // ------------------------------------------------------------------ connection

    /** Makes the connection if there is none. @return true if one is up */
    private boolean ensureConnected() {
        FCastConnection existing = connection;
        if (existing != null && !existing.isClosed()) {
            return true;
        }
        publish(state.toBuilder().link(state.getLink() == FCastReceiverState.Link.RECONNECTING ? FCastReceiverState.Link.RECONNECTING
                : FCastReceiverState.Link.CONNECTING));
        final int mine = ++generation;
        try {
            FCastConnection made = connector.connect(new FCastConnection.Listener() {
                @Override
                public void onMessage(FCastMessages.Incoming message) {
                    post(() -> FCastSession.this.onMessage(mine, message));
                }

                @Override
                public void onClosed(FCastConnection.CloseReason reason, String detail) {
                    post(() -> FCastSession.this.onClosed(mine, reason, detail));
                }
            });
            if (closed) {
                made.close();
                return false;
            }
            connection = made;
            connectedAt = clock.nowMillis();
            publish(state.toBuilder().link(FCastReceiverState.Link.CONNECTED).protocolVersion(made.getNegotiatedVersion()));
            return true;
        } catch (IOException e) {
            connection = null;
            publish(state.toBuilder().link(FCastReceiverState.Link.FAILED).playback(FCastReceiverState.Playback.UNKNOWN, null, null, null)
                    .pending(FCastReceiverState.Pending.NONE).error("could not connect to the receiver (" + e.getClass().getSimpleName() + ")"));
            return false;
        }
    }

    private void onClosed(int which, FCastConnection.CloseReason reason, String detail) {
        if (which != generation || closed) {
            return;
        }
        long now = clock.nowMillis();
        if (connectedAt != 0 && now - connectedAt >= config.stableMillis) {
            retriesInRow = 0;
        }
        connection = null;
        cancelPending();
        FCastReceiverState.Builder lost = state.toBuilder().playback(FCastReceiverState.Playback.UNKNOWN, null, null, null)
                .pending(FCastReceiverState.Pending.NONE);
        if (reason == FCastConnection.CloseReason.CLOSED_BY_US || users == 0) {
            publish(lost.link(FCastReceiverState.Link.IDLE));
            return;
        }
        if (retriesInRow >= 1) {
            publish(lost.link(FCastReceiverState.Link.FAILED).error("the connection to the receiver was lost again (" + reason + ")"));
            return;
        }
        retriesInRow++;
        publish(lost.link(FCastReceiverState.Link.RECONNECTING));
        try {
            executor.schedule(this::reconnect, config.reconnectDelayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    private void reconnect() {
        if (closed || connection != null) {
            return;
        }
        if (users == 0) {
            publish(state.toBuilder().link(FCastReceiverState.Link.IDLE));
            return;
        }
        if (!ensureConnected()) {
            fail("the connection to the receiver was lost and could not be made again");
        }
    }

    // ------------------------------------------------------------------ messages of the receiver

    private void onMessage(int which, FCastMessages.Incoming message) {
        if (which != generation || closed) {
            return;
        }
        FCastReceiverState.Builder b = state.toBuilder();
        if (message instanceof FCastMessages.Version) {
            FCastConnection c = connection;
            if (c != null) {
                b.protocolVersion(c.getNegotiatedVersion());
            }
            publish(b);
        } else if (message instanceof FCastMessages.Initial) {
            FCastMessages.Initial initial = (FCastMessages.Initial) message;
            b.receiver(initial.getDisplayName(), initial.getAppName(), initial.getAppVersion());
            FCastMessages.PlayData data = initial.getPlayData();
            if (data != null) {
                b.item(data.getUrl(), data.getContainer(), data.getTitle(), isElsewhere(data.getUrl()));
            }
            publish(b);
        } else if (message instanceof FCastMessages.PlaybackUpdate) {
            applyPlaybackUpdate(b, (FCastMessages.PlaybackUpdate) message);
        } else if (message instanceof FCastMessages.VolumeUpdate) {
            publish(b.volume(((FCastMessages.VolumeUpdate) message).getVolume()));
        } else if (message instanceof FCastMessages.PlaybackError) {
            String text = ((FCastMessages.PlaybackError) message).getMessage();
            cancelPending();
            FCastReceiverState next = b.pending(FCastReceiverState.Pending.NONE).error(text.isEmpty() ? "the receiver reported a playback error" : text).build();
            state = next;
            listener.onStateChanged(next);
            listener.onPlaybackError(text);
        } else if (message instanceof FCastMessages.PlayUpdate) {
            FCastMessages.PlayUpdate update = (FCastMessages.PlayUpdate) message;
            FCastMessages.PlayData data = update.getPlayData();
            if (data != null) {
                b.item(data.getUrl(), data.getContainer(), data.getTitle(), isElsewhere(data.getUrl()));
            }
            if (state.getPending() == FCastReceiverState.Pending.PLAY) {
                cancelPending();
                b.pending(FCastReceiverState.Pending.NONE);
            }
            FCastReceiverState next = b.build();
            state = next;
            listener.onStateChanged(next);
            listener.onPlayUpdate(update, next);
        } else if (message instanceof FCastMessages.Event) {
            listener.onMediaItemEvent((FCastMessages.Event) message);
        }
    }

    private boolean isElsewhere(String url) {
        return url == null || !url.equals(lastPlayedUrl);
    }

    private void applyPlaybackUpdate(FCastReceiverState.Builder b, FCastMessages.PlaybackUpdate update) {
        FCastReceiverState.Playback playback;
        switch (update.getState()) {
            case FCastMessages.STATE_PLAYING:
                playback = FCastReceiverState.Playback.PLAYING;
                break;
            case FCastMessages.STATE_PAUSED:
                playback = FCastReceiverState.Playback.PAUSED;
                break;
            default:
                playback = FCastReceiverState.Playback.IDLE;
        }
        if (playback == FCastReceiverState.Playback.IDLE) {
            b.playback(playback, null, null, update.getSpeed());
        } else {
            b.playback(playback, update.getTime() != null ? update.getTime() : state.getPosition(),
                    update.getDuration() != null ? update.getDuration() : state.getDuration(),
                    update.getSpeed() != null ? update.getSpeed() : state.getSpeed());
        }
        if (confirms(state.getPending(), update)) {
            cancelPending();
            b.pending(FCastReceiverState.Pending.NONE);
        }
        publish(b);
    }

    /** Does this update answer the command that is pending? */
    private static boolean confirms(FCastReceiverState.Pending pending, FCastMessages.PlaybackUpdate update) {
        switch (pending) {
            case PLAY:
                return true;
            case PAUSE:
                return update.getState() == FCastMessages.STATE_PAUSED;
            case RESUME:
                return update.getState() == FCastMessages.STATE_PLAYING;
            case STOP:
                return update.getState() == FCastMessages.STATE_IDLE;
            case SEEK:
                return update.getTime() != null;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ pending, errors, idle

    private void setPending(FCastReceiverState.Pending pending) {
        cancelPending();
        publish(state.toBuilder().pending(pending));
        final int token = ++pendingToken;
        try {
            pendingTask = executor.schedule(() -> {
                if (token == pendingToken && state.getPending() != FCastReceiverState.Pending.NONE) {
                    publish(state.toBuilder().pending(FCastReceiverState.Pending.NONE));
                }
            }, config.pendingTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    private void cancelPending() {
        pendingToken++;
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    private void fail(String text) {
        YaaccLogger.w(TAG, "FCast: " + text);
        publish(state.toBuilder().error(text).pending(FCastReceiverState.Pending.NONE));
    }

    private void touch() {
        lastActivity = clock.nowMillis();
    }

    private void cancelIdle() {
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
    }

    /** With nobody using the receiver the connection closes {@code idleCloseMillis} after the last activity. */
    private void armIdle() {
        if (users > 0 || connection == null || idleTask != null || closed) {
            return;
        }
        long wait = Math.max(1, lastActivity + config.idleCloseMillis - clock.nowMillis());
        try {
            idleTask = executor.schedule(this::idleCheck, wait, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    private void idleCheck() {
        idleTask = null;
        FCastConnection c = connection;
        if (closed || users > 0 || c == null) {
            return;
        }
        if (clock.nowMillis() - lastActivity >= config.idleCloseMillis) {
            c.close(); // its close event makes the state IDLE
        } else {
            armIdle();
        }
    }

    private void publish(FCastReceiverState.Builder builder) {
        FCastReceiverState next = builder.build();
        state = next;
        listener.onStateChanged(next);
    }

    private void post(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    YaaccLogger.e(TAG, "FCast: the session failed: " + e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // the executor is shut down: the session is over
        }
    }

    /** Closes the connection and ends the session; the state becomes {@code IDLE}. The executor is not shut down (it is the caller's). */
    @Override
    public void close() {
        closed = true;
        FCastConnection c = connection;
        if (c != null) {
            c.close();
        }
        post(() -> {
            cancelIdle();
            cancelPending();
            connection = null;
            state = state.toBuilder().link(FCastReceiverState.Link.IDLE).playback(FCastReceiverState.Playback.UNKNOWN, null, null, null)
                    .pending(FCastReceiverState.Pending.NONE).build();
        });
    }
}
