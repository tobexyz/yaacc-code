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
package de.yaacc.multiroom.feed;

import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.GroupVolume;

/**
 * The coordination of playing into one Snapcast group (task 4.4), without Android: it joins {@link FeedStreams} (the
 * stream and the group on the server), {@link FeedServer} (the control connection), a {@link FeedOutputFactory} (the
 * connection to the stream port) and the clock. The player is thin glue around it: it asks for a start before ExoPlayer
 * plays, calls {@link #pause()} / {@link #stop()} / {@link #release()}, and hands {@link #sink()} to the audio tap.
 *
 * <p><b>Threads.</b> Every call that blocks (control calls, connecting, closing the feed, the drain) runs on the
 * injected single-thread {@code worker}, in the order of the calls; the public methods only queue work or touch
 * volatile state, so they are safe to call from the main thread. {@link #sink()} is called on ExoPlayer's playback
 * thread; its {@code write} waits at most what the {@link FeedOutput} waits.
 *
 * <p><b>Start</b> ({@link #start}): take the feed slot of the server (one feed per server), wait for the control
 * connection, {@code ensure()} the stream, {@code useFor(group)} (remembers the group's previous stream), then read the
 * <b>peer address of the control connection</b> and connect the feed to exactly that address and the port that
 * {@code ensure()} reported. The address is read after the last control call and handed on; it is never logged or kept
 * longer than the call. The callback hears {@code onReady} on the worker; ExoPlayer starts only then, so no audio is
 * lost to a feed that is not there yet. A {@link #stop()} / {@link #release()} that comes while a start is in progress
 * cancels it: the callback hears nothing and the cleanup of the stop runs after the start.
 *
 * <p><b>Pause</b> discards what is queued in the feed (the player's pause does not flush the audio processors); the feed
 * stays open. <b>Seek</b> needs nothing: ExoPlayer flushes the processors, and the tap discards. <b>Stop</b> waits
 * about one chunk for the queue to drain (bounded), closes the feed on the worker (closing may block for seconds),
 * gives the group its previous stream back and frees the slot; the stream stays for a start soon after. <b>Release</b>
 * does the same and also removes the stream ({@code shutdown()}) and ends the worker.
 *
 * <p><b>Failures.</b> A start that does not succeed is reported once as a typed {@link FeedError} (never an exception),
 * after everything it did was undone (the group is back, the slot is free). A feed that breaks while playing, or a
 * control connection that is lost ({@link #controlLost()}), closes the feed and tells the {@link Listener} once; the
 * player stops cleanly. After a lost control connection the group may stay on the stream until the next stop or idle
 * check can reach the server.
 *
 * <p><b>Idle.</b> While a stream is held a timer (the injected scheduler) looks every minute whether the feed has had no
 * audio for {@code idleTimeoutMillis} (a long pause); then it closes the feed and calls
 * {@link FeedStreams#removeIfIdle()}. If that does not remove the stream (a restore failed, a group still plays it, the
 * server is not reachable) the next attempt comes later, the delay doubles up to ten minutes, and it starts again after
 * the next audio.
 *
 * <p><b>Volume.</b> {@link #setVolume} / {@link #setMuted} are the group's volume and mute on the server (the phone's
 * own volume is never touched): levels are changed relative to the levels at the start of a gesture (calls closer than
 * {@code volumeGestureGapMillis} belong to one), and only the latest wanted value is sent.
 *
 * <p>Nothing here logs an address or a name; {@link Listener#onLog} gets lines with states and counters only.
 */
public final class SnapcastFeedController {

    /** The settings; the defaults are those of the design. */
    public static final class Config {
        long idleTimeoutMillis = SnapcastStreams.DEFAULT_IDLE_TIMEOUT_MILLIS;
        long idleCheckMillis = 60_000L;
        long maxIdleCheckMillis = 10 * 60_000L;
        long controlWaitMillis = 5_000L;
        long slotWaitMillis = 3_000L;
        long drainMaxMillis = 250L;
        long drainGraceMillis = 60L;
        long volumeGestureGapMillis = 2_000L;
        Executor volumeExecutor;

        public Config() {
        }

        /**
         * Where volume and mute run (default: the worker of the controller). A volume gesture is up to 256 control
         * calls, so the app gives it an executor of its own: a slow server can then not hold up a stop or a release.
         */
        public Config volumeExecutor(Executor value) {
            volumeExecutor = value;
            return this;
        }

        public Config idleTimeoutMillis(long value) {
            idleTimeoutMillis = Math.max(1, value);
            return this;
        }

        public Config idleCheckMillis(long value) {
            idleCheckMillis = Math.max(1, value);
            return this;
        }

        public Config maxIdleCheckMillis(long value) {
            maxIdleCheckMillis = Math.max(1, value);
            return this;
        }

        public Config controlWaitMillis(long value) {
            controlWaitMillis = Math.max(0, value);
            return this;
        }

        public Config slotWaitMillis(long value) {
            slotWaitMillis = Math.max(0, value);
            return this;
        }

        /** The most the drain before a close waits for the queue to empty. */
        public Config drainMaxMillis(long value) {
            drainMaxMillis = Math.max(0, value);
            return this;
        }

        /** The wait after the queue is empty: the writer still holds up to three chunks (the pacer's look-ahead). */
        public Config drainGraceMillis(long value) {
            drainGraceMillis = Math.max(0, value);
            return this;
        }

        public Config volumeGestureGapMillis(long value) {
            volumeGestureGapMillis = Math.max(0, value);
            return this;
        }
    }

    /** Hears what the controller does on its own. Called on the worker (or the scheduler): hand work off. */
    public interface Listener {
        /** The feed ended without a call of the player (it broke, or the control connection was lost). */
        void onFeedLost(FeedError error);

        /** A line for the log: states and counters, never an address or a name. */
        void onLog(String line);
    }

    /** The answer to {@link #start}. */
    public interface StartCallback {
        /** The feed is open and the group plays the stream: ExoPlayer may play. */
        void onReady();

        /** Nothing is left running; the error says why. */
        void onFailed(FeedError error);
    }

    /** The state, for tests and the player. */
    public enum State {
        /** No feed is open. */
        IDLE,
        /** A start is in progress. */
        STARTING,
        /** The feed is open. */
        READY,
        /** {@link #release()} was called; nothing works any more. */
        RELEASED
    }

    private static final long SLEEP_STEP_MILLIS = 5L;

    private final FeedStreams streams;
    private final FeedServer server;
    private final FeedOutputFactory outputs;
    private final FeedSlot slot;
    private final String groupId;
    private final FeedClock clock;
    private final RetryScheduler scheduler;
    private final Executor worker;
    private final Listener listener;
    private final Config config;
    private final Executor volumeWorker;
    private final Runnable onReleased;

    private volatile State state = State.IDLE;
    private volatile FeedOutput output;
    private final AtomicInteger epoch = new AtomicInteger();
    private final AtomicLong lastAudioMillis;
    // worker only from here
    private int outputGeneration;
    private boolean slotHeld;
    private boolean groupMoved;
    private boolean streamHeld;
    private boolean idleScheduled;
    private long removalBackoffMillis;
    // volume
    private final AtomicInteger wantedVolume = new AtomicInteger(-1);
    private final AtomicBoolean volumeQueued = new AtomicBoolean();
    private Map<String, Integer> gestureLevels;
    private long lastVolumeMillis;

    /**
     * @param groupId    the group of the server this controller plays into
     * @param worker     a single-thread executor; the controller never blocks the thread that calls it
     * @param onReleased runs on the worker at the end of {@link #release()}; the owner ends the worker there. May be null.
     */
    public SnapcastFeedController(FeedStreams streams, FeedServer server, FeedOutputFactory outputs, FeedSlot slot,
                                  String groupId, FeedClock clock, RetryScheduler scheduler, Executor worker,
                                  Listener listener, Config config, Runnable onReleased) {
        if (streams == null || server == null || outputs == null || slot == null || groupId == null || groupId.isEmpty()
                || clock == null || scheduler == null || worker == null || listener == null) {
            throw new IllegalArgumentException("missing argument");
        }
        this.streams = streams;
        this.server = server;
        this.outputs = outputs;
        this.slot = slot;
        this.groupId = groupId;
        this.clock = clock;
        this.scheduler = scheduler;
        this.worker = worker;
        this.listener = listener;
        this.config = config == null ? new Config() : config;
        this.volumeWorker = this.config.volumeExecutor == null ? worker : this.config.volumeExecutor;
        this.onReleased = onReleased;
        this.lastAudioMillis = new AtomicLong(nowMillis());
        this.removalBackoffMillis = this.config.idleCheckMillis;
    }

    // ---- the tap's side

    private final PcmSink sink = new PcmSink() {
        @Override
        public boolean write(byte[] data, int offset, int length) {
            FeedOutput current = output;
            if (current == null) {
                return false;
            }
            boolean taken = current.write(data, offset, length);
            if (taken) {
                lastAudioMillis.set(nowMillis());
                streams.noteAudio();
            }
            return taken;
        }

        @Override
        public void discard() {
            FeedOutput current = output;
            if (current != null) {
                current.discard();
            }
        }
    };

    /**
     * The sink for the audio tap: it hands the PCM to the open feed, and refuses (returns false) while there is none.
     * The same object for the whole life of the controller, so the tap can be built before the first start.
     */
    public PcmSink sink() {
        return sink;
    }

    // ---- calls of the player

    /** The state; READY means a feed is open. */
    public State getState() {
        return state;
    }

    /** True while a feed is open and running. */
    public boolean isFeeding() {
        FeedOutput current = output;
        return state == State.READY && current != null && current.isRunning();
    }

    /**
     * Opens the feed (or confirms that it is open). Returns at once; the callback comes on the worker, unless a
     * {@link #stop()} or {@link #release()} was called in the meantime, in which case it does not come at all.
     */
    public void start(final StartCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("no callback");
        }
        final int startEpoch = epoch.get();
        execute(() -> runStart(startEpoch, callback));
    }

    /** The player paused: what is queued is stale. The feed stays open. */
    public void pause() {
        sink.discard();
    }

    /**
     * The player stopped (also the end of the playlist): drain, close the feed, give the group its previous stream back.
     * Returns at once. A start that has not finished is cancelled.
     */
    public void stop() {
        epoch.incrementAndGet();
        execute(this::runStop);
    }

    /** The player is gone: like {@link #stop()}, and the stream is removed and the worker ended. Returns at once. */
    public void release() {
        epoch.incrementAndGet();
        execute(this::runRelease);
    }

    /** The control connection went down. Returns at once. */
    public void controlLost() {
        execute(() -> {
            if (state == State.READY) {
                try {
                    closeOutput(false, "control connection lost");
                } finally {
                    releaseSlot();
                    state = State.IDLE;
                    scheduleIdleCheck(config.idleCheckMillis);
                }
                listener.onFeedLost(FeedError.of(FeedError.Kind.CONTROL_LOST));
            }
        });
    }

    // ---- volume of the group

    /** The group's volume (the average of its connected members) from the model; 0 if unknown. No network. */
    public int getVolume() {
        SnapcastGroup group = server.findGroup(groupId);
        if (group == null) {
            return 0;
        }
        return Math.max(0, GroupVolume.average(GroupVolume.levels(group)));
    }

    /** True if the group is muted on the server, from the model. */
    public boolean isMuted() {
        SnapcastGroup group = server.findGroup(groupId);
        return group != null && group.isMuted();
    }

    /** Sets the group's volume, 0 to 100. Returns at once; only the latest value of a quick series is sent. */
    public void setVolume(int percent) {
        wantedVolume.set(Math.max(0, Math.min(100, percent)));
        if (volumeQueued.compareAndSet(false, true)) {
            executeOn(volumeWorker, this::applyVolume);
        }
    }

    /** Mutes or unmutes the group on the server. Returns at once. */
    public void setMuted(final boolean muted) {
        executeOn(volumeWorker, () -> {
            try {
                server.setGroupMuted(groupId, muted);
            } catch (SnapcastException | RuntimeException failure) {
                listener.onLog("group mute failed");
            }
        });
    }

    private void applyVolume() {
        volumeQueued.set(false);
        int percent = wantedVolume.getAndSet(-1);
        if (percent < 0) {
            return;
        }
        SnapcastGroup group = server.findGroup(groupId);
        if (group == null) {
            return;
        }
        long now = nowMillis();
        if (gestureLevels == null || now - lastVolumeMillis > config.volumeGestureGapMillis) {
            gestureLevels = GroupVolume.levels(group);
        }
        lastVolumeMillis = now;
        try {
            server.setGroupVolume(group, gestureLevels, percent);
        } catch (SnapcastException | RuntimeException failure) {
            listener.onLog("group volume failed");
        }
    }

    // ---- the work, on the worker

    private void execute(final Runnable task) {
        executeOn(worker, task);
    }

    private void executeOn(Executor target, final Runnable task) {
        try {
            target.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException unexpected) {
                    listener.onLog("unexpected " + unexpected.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ended) {
            // released: the executor is gone
        }
    }

    private long nowMillis() {
        return TimeUnit.NANOSECONDS.toMillis(clock.nanoTime());
    }

    private boolean cancelled(int startEpoch) {
        return epoch.get() != startEpoch || state == State.RELEASED;
    }

    private void runStart(int startEpoch, StartCallback callback) {
        if (cancelled(startEpoch)) {
            return;
        }
        if (isFeeding()) {
            callback.onReady();
            return;
        }
        if (state == State.READY) {
            // the feed died and nobody noticed yet: start over
            closeOutput(false, "feed was not running");
            releaseSlot();
        }
        state = State.STARTING;
        FeedError error;
        try {
            error = connectFeed(startEpoch);
        } catch (RuntimeException unexpected) {
            error = FeedError.of(FeedError.Kind.SERVER_FAILED);
        }
        if (cancelled(startEpoch)) {
            return; // the stop or release that cancelled this start cleans up after it
        }
        if (error != null) {
            undoFailedStart();
            state = State.IDLE;
            listener.onLog("start failed: " + error.getKind());
            callback.onFailed(error);
            return;
        }
        state = State.READY;
        removalBackoffMillis = config.idleCheckMillis;
        lastAudioMillis.set(nowMillis());
        scheduleIdleCheck(config.idleCheckMillis);
        listener.onLog("feed ready");
        callback.onReady();
    }

    /** @return null on success, else why not; the caller undoes what was done */
    private FeedError connectFeed(int startEpoch) {
        if (!slot.tryAcquire(config.slotWaitMillis)) {
            return FeedError.of(FeedError.Kind.BUSY);
        }
        slotHeld = true;
        if (cancelled(startEpoch)) {
            return FeedError.of(FeedError.Kind.NO_CONTROL);
        }
        if (!server.awaitControl(config.controlWaitMillis)) {
            return FeedError.of(FeedError.Kind.NO_CONTROL);
        }
        final long connection = server.connectionGeneration();
        SnapcastStreams.EnsureResult ensured = streams.ensure();
        if (!ensured.isReady()) {
            return errorOf(ensured);
        }
        streamHeld = true;
        groupMoved = true; // set before the call: a move that failed may have been applied, and release() sorts it out
        SnapcastStreams.UseResult used = streams.useFor(groupId);
        if (used == SnapcastStreams.UseResult.NOT_READY) {
            // the server lost the stream (a restart): make it again, once
            ensured = streams.ensure();
            if (!ensured.isReady()) {
                return errorOf(ensured);
            }
            used = streams.useFor(groupId);
        }
        switch (used) {
            case OK:
                break;
            case GROUP_NOT_FOUND:
                return FeedError.of(FeedError.Kind.GROUP_GONE);
            default:
                return FeedError.of(FeedError.Kind.SERVER_FAILED);
        }
        if (cancelled(startEpoch)) {
            return FeedError.of(FeedError.Kind.NO_CONTROL);
        }
        // the last step before connecting: the peer of the control connection as it is now, and no other address
        InetAddress peer = server.peerAddress();
        if (peer == null || server.connectionGeneration() != connection) {
            // no connection, or a reconnect since the stream was made: the address may be another host's
            return FeedError.of(FeedError.Kind.NO_CONTROL);
        }
        final int generation = ++outputGeneration;
        try {
            output = outputs.open(peer, ensured.getPort(), () -> execute(() -> outputFailed(generation)));
        } catch (IOException | RuntimeException cannotConnect) {
            output = null;
            return FeedError.of(FeedError.Kind.CONNECT_FAILED);
        }
        return null;
    }

    private static FeedError errorOf(SnapcastStreams.EnsureResult result) {
        FeedError.Kind kind;
        switch (result.getStatus()) {
            case REFUSED:
                kind = FeedError.Kind.REFUSED;
                break;
            case UNAUTHORIZED:
                kind = FeedError.Kind.UNAUTHORIZED;
                break;
            case NO_FREE_PORT:
                kind = FeedError.Kind.NO_FREE_PORT;
                break;
            case CONFLICT:
                kind = FeedError.Kind.CONFLICT;
                break;
            default:
                kind = FeedError.Kind.SERVER_FAILED;
                break;
        }
        return new FeedError(kind, result.getReason(), result.getHintKey());
    }

    /** What a start that failed (or was cut short) leaves: the group goes back, the slot is free. The stream stays. */
    private void undoFailedStart() {
        closeOutput(false, "start failed");
        giveGroupBack();
        releaseSlot();
        scheduleIdleCheck(config.idleCheckMillis);
    }

    private void giveGroupBack() {
        if (groupMoved) {
            groupMoved = false;
            try {
                streams.release(groupId);
            } catch (RuntimeException ignored) {
                // best effort: the idle check or the next stop tries again through shutdown()
            }
        }
    }

    private void releaseSlot() {
        if (slotHeld) {
            slotHeld = false;
            slot.release();
        }
    }

    private void runStop() {
        if (state == State.RELEASED) {
            return;
        }
        try {
            closeOutput(true, "stopped");
            giveGroupBack();
        } finally {
            // whatever went wrong above: the slot is free and the state is right
            releaseSlot();
            state = State.IDLE;
            scheduleIdleCheck(config.idleCheckMillis);
        }
    }

    private void runRelease() {
        if (state == State.RELEASED) {
            return;
        }
        try {
            closeOutput(true, "released");
            try {
                streams.shutdown(); // restores every group it moved and removes the stream; false: left for the next run
            } catch (RuntimeException ignored) {
                // nothing more can be done
            }
        } finally {
            // the owner must always hear of the release: it lets go of the link and ends the workers
            groupMoved = false;
            streamHeld = false;
            releaseSlot();
            state = State.RELEASED;
            if (onReleased != null) {
                onReleased.run();
            }
        }
    }

    private void outputFailed(int generation) {
        if (generation != outputGeneration || output == null || state == State.RELEASED) {
            return; // an older feed, or one that was closed on purpose
        }
        try {
            closeOutput(false, "feed failed");
            giveGroupBack();
        } finally {
            releaseSlot();
            state = State.IDLE;
            scheduleIdleCheck(config.idleCheckMillis);
        }
        listener.onFeedLost(FeedError.of(FeedError.Kind.FEED_FAILED));
    }

    /**
     * Closes the feed if there is one; with {@code drain} first waits for its queue (bounded). Worker only. A feed that
     * throws while it is drained or closed is dropped all the same: the caller's cleanup goes on.
     */
    private void closeOutput(boolean drain, String why) {
        FeedOutput closing = output;
        output = null;
        outputGeneration++;
        if (closing == null) {
            return;
        }
        String statistics = "";
        try {
            if (drain) {
                drain(closing);
            }
            statistics = closing.statistics();
        } catch (RuntimeException unexpected) {
            listener.onLog("feed drain failed: " + unexpected.getClass().getSimpleName());
        }
        try {
            closing.close();
        } catch (RuntimeException unexpected) {
            listener.onLog("feed close failed: " + unexpected.getClass().getSimpleName());
        }
        listener.onLog("feed closed (" + why + ")" + (statistics == null || statistics.isEmpty() ? "" : " " + statistics));
    }

    /**
     * Waits for the feed's queue to empty (at most {@code drainMaxMillis}) and then for the chunks the writer still
     * holds, so that the last audio of the track is not cut off.
     */
    private void drain(FeedOutput feed) {
        try {
            long waited = 0;
            while (feed.isRunning() && feed.getQueuedBytes() > 0 && waited < config.drainMaxMillis) {
                clock.sleepNanos(TimeUnit.MILLISECONDS.toNanos(SLEEP_STEP_MILLIS));
                waited += SLEEP_STEP_MILLIS;
            }
            if (feed.isRunning() && config.drainGraceMillis > 0) {
                clock.sleepNanos(TimeUnit.MILLISECONDS.toNanos(config.drainGraceMillis));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- the idle check

    private void scheduleIdleCheck(long delayMillis) {
        if (idleScheduled || !streamHeld || state == State.RELEASED) {
            return;
        }
        idleScheduled = true;
        scheduler.schedule(() -> execute(this::idleCheck), delayMillis);
    }

    private void idleCheck() {
        idleScheduled = false;
        if (state == State.RELEASED || !streamHeld) {
            return;
        }
        if (state == State.STARTING) {
            scheduleIdleCheck(config.idleCheckMillis);
            return;
        }
        long idleFor = nowMillis() - lastAudioMillis.get();
        if (idleFor < config.idleTimeoutMillis) {
            // audio came since (or the stop was not long ago): look again when it could be idle, a second after the
            // timeout so that the clock of SnapcastStreams, which is set a moment later, agrees
            scheduleIdleCheck(Math.min(config.maxIdleCheckMillis,
                    Math.max(config.idleCheckMillis, config.idleTimeoutMillis - idleFor + 1_000L)));
            return;
        }
        if (state == State.READY) {
            try {
                closeOutput(false, "idle");
                giveGroupBack();
            } finally {
                releaseSlot();
                state = State.IDLE;
            }
        }
        boolean removed = streams.removeIfIdle();
        if (removed) {
            streamHeld = false;
            groupMoved = false;
            removalBackoffMillis = config.idleCheckMillis;
            listener.onLog("stream removed (idle)");
            return;
        }
        // not removed (a restore failed, a group still plays it, the server is out of reach): later, and later again
        scheduleIdleCheck(removalBackoffMillis);
        removalBackoffMillis = Math.min(config.maxIdleCheckMillis, removalBackoffMillis * 2);
    }
}
