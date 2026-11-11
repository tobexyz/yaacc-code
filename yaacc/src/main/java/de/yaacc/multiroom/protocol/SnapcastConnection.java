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
package de.yaacc.multiroom.protocol;

import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.control.SocketTransport;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The phone as a client of one snapserver: the TCP connection of the binary protocol (default port 1704).
 *
 * <p><b>Whom it talks to.</b> To the endpoint of the {@link SnapcastTransport} it was made with, which the caller (the
 * user or the discovery gate) chose. Nothing in a message can name another host or port, and nothing is followed. No
 * credentials are sent (the Hello has no {@code Auth}); a server that wants some answers with an Error 401 or 403 and
 * is given up with the reason {@link LossReason#UNAUTHORIZED}.
 *
 * <p><b>Flow.</b> After the connect the Hello goes out, the server answers with the settings and the codec header, and
 * chunks follow. A chunk that arrives before the codec header is dropped (counted), not delivered. When the settings
 * arrive the time sync starts: a burst of {@code timeSyncBurstCount} (default 16, the size of the filter's
 * window) Time requests {@code timeSyncBurstIntervalMs} (100 ms) apart, then one every {@code timeSyncIntervalMs} (1 s,
 * the interval of the reference client); the answers feed the {@link ClockOffsetFilter}. A message must be complete
 * {@code messageDeadlineMs} (10 s) after its first byte, the
 * settings and the header must be there {@code handshakeTimeoutMs} (10 s) after the Hello, and a connection that
 * delivers nothing at all for {@code idleTimeoutMs} (10 s) is dropped.
 *
 * <p><b>Threads.</b> One reader thread per connection attempt reads, decodes, answers the time sync and writes the
 * Hello; it never calls a listener. Listeners run on a serial executor (an own thread unless the caller gives one),
 * in the order of the stream. The chunks waiting for a slow listener are bounded in count and bytes; a chunk beyond the
 * bounds is dropped and counted. A listener that throws is skipped for that call.
 *
 * <p><b>Reconnect.</b> As in the control client ({@code SnapcastRpc}): a lost connection is retried after 1 s, 2 s,
 * 4 s ... at most 30 s; after 5 failures in a row it stops ({@link State#GAVE_UP}) until {@link #retry()}; a connection
 * that held for {@code stableConnectionMs} (10 s) after a complete handshake starts the count again. {@link
 * Listener#onLost} is called once per outage (not again for every failed retry; a complete handshake ends the outage)
 * and {@link Listener#onGaveUp} once when the retries stop. A permanent reason (authorization, a codec that is not on
 * the allow-list) stops at once, because a retry would meet the same answer.
 *
 * <p>{@link #close()} ends the thread and the socket, also while the thread is blocked in a read, and nothing is
 * reported afterwards.
 */
public final class SnapcastConnection implements Closeable {

    /** The default port of the stream protocol. */
    public static final int DEFAULT_PORT = 1704;

    private static final int MAX_PENDING_TIME_REQUESTS = 16;
    /** Frames of unknown types (or types a client never receives) in a row after which the connection is dropped. */
    private static final int MAX_CONSECUTIVE_UNEXPECTED = 100;
    /** An answer may claim at most this much more network time than we measured with our own clock. */
    private static final long TIME_CONSISTENCY_TOLERANCE_MICROS = 1_000L;
    private static final long CLOSE_JOIN_MILLIS = 2_000L;
    private static final int MAX_DETAIL_LENGTH = 80;

    /** The state of the connection. */
    public enum State {
        /** Not started. */
        IDLE,
        /** A connection is being opened. */
        CONNECTING,
        /** The TCP connection is up (the handshake may still be going on). */
        CONNECTED,
        /** The connection failed; the next attempt is scheduled. */
        WAITING_RETRY,
        /** The retries stopped; nothing happens until {@link #retry()}. */
        GAVE_UP,
        /** Closed for good. */
        CLOSED
    }

    /** Why a connection was lost. */
    public enum LossReason {
        /** The connection could not be opened. */
        CONNECT_FAILED,
        /** The network failed. */
        IO_ERROR,
        /** The server closed the connection. */
        SERVER_CLOSED,
        /** A timeout of the handshake, the idle limit, a read in the middle of a message, or a slow message. */
        TIMEOUT,
        /** The server sent something that is not the protocol (too large, malformed, too many strange frames). */
        PROTOCOL_ERROR,
        /** The server sent an Error message. */
        SERVER_ERROR,
        /** The server wants authorization; this client sends none. Permanent. */
        UNAUTHORIZED,
        /** The server uses a codec that is not on the allow-list. Permanent. */
        UNSUPPORTED_CODEC,
        /** A bug on our side. */
        INTERNAL
    }

    /** A typed reason and, for the user, a short text of ours or the server's (display text only, never logged). */
    public static final class Loss {
        private final LossReason reason;
        private final String detail;

        Loss(LossReason reason, String detail) {
            this.reason = reason;
            this.detail = SnapcastDisplayText.of(detail, MAX_DETAIL_LENGTH);
        }

        public LossReason getReason() {
            return reason;
        }

        /** Cleaned text, possibly the server's own; empty when there is none. */
        public String getDetail() {
            return detail;
        }

        /** True when a retry would meet the same answer. */
        public boolean isPermanent() {
            return reason == LossReason.UNAUTHORIZED || reason == LossReason.UNSUPPORTED_CODEC;
        }
    }

    /** Receives what happens, on the listener executor. All methods do nothing by default. */
    public interface Listener {
        default void onServerSettings(ServerSettingsMessage settings) {
        }

        /** The codec header: initialise the decoder; chunks follow (a new header on the same connection restarts it). */
        default void onCodecHeader(CodecHeaderMessage header) {
        }

        default void onChunk(WireChunkMessage chunk) {
        }

        /** The clock estimate changed (once synchronised, after every accepted time sample). */
        default void onOffsetUpdate(long offsetMicros, long jitterMicros) {
        }

        /** The server was lost; called once per outage. */
        default void onLost(Loss loss) {
        }

        /** The retries stopped; {@link SnapcastConnection#retry()} starts them again. */
        default void onGaveUp(Loss loss) {
        }
    }

    public static final class Config {
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 5_000;
        private long messageDeadlineMs = 10_000;
        private long idleTimeoutMs = 10_000;
        private long handshakeTimeoutMs = 10_000;
        private int timeSyncBurstCount = ClockOffsetFilter.WINDOW;
        private long timeSyncBurstIntervalMs = 100;
        private long timeSyncIntervalMs = 1_000;
        private int maxFailures = 5;
        private long backoffInitialMs = 1_000;
        private long backoffMaxMs = 30_000;
        private long stableConnectionMs = 10_000;
        private int maxPendingChunks = 256;
        private long maxPendingChunkBytes = 4L * 1024 * 1024;

        private static long positive(String name, long value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1");
            }
            return value;
        }

        public Config connectTimeoutMs(int value) {
            connectTimeoutMs = (int) positive("connectTimeoutMs", value);
            return this;
        }

        /** The longest a single read may block; the loop wakes at least this often (default 5 s). */
        public Config readTimeoutMs(int value) {
            readTimeoutMs = (int) positive("readTimeoutMs", value);
            return this;
        }

        /** How long a message may take from its first byte to its last (default 10 s). */
        public Config messageDeadlineMs(long value) {
            messageDeadlineMs = positive("messageDeadlineMs", value);
            return this;
        }

        /** How long nothing at all may arrive (default 10 s; the time sync answers arrive every second). */
        public Config idleTimeoutMs(long value) {
            idleTimeoutMs = positive("idleTimeoutMs", value);
            return this;
        }

        /** How long the settings and the codec header may take after the Hello (default 10 s). */
        public Config handshakeTimeoutMs(long value) {
            handshakeTimeoutMs = positive("handshakeTimeoutMs", value);
            return this;
        }

        /** Time requests sent at the short interval at the start (default 16, the window of the filter). */
        public Config timeSyncBurstCount(int value) {
            timeSyncBurstCount = (int) positive("timeSyncBurstCount", value);
            return this;
        }

        /** The interval within the burst (default 100 ms). */
        public Config timeSyncBurstIntervalMs(long value) {
            timeSyncBurstIntervalMs = positive("timeSyncBurstIntervalMs", value);
            return this;
        }

        /** The interval after the burst (default 1 s). */
        public Config timeSyncIntervalMs(long value) {
            timeSyncIntervalMs = positive("timeSyncIntervalMs", value);
            return this;
        }

        /** Consecutive failures after which the reconnects stop (default 5). */
        public Config maxFailures(int value) {
            maxFailures = (int) positive("maxFailures", value);
            return this;
        }

        public Config backoffInitialMs(long value) {
            backoffInitialMs = positive("backoffInitialMs", value);
            return this;
        }

        public Config backoffMaxMs(long value) {
            backoffMaxMs = positive("backoffMaxMs", value);
            return this;
        }

        /** How long a connection must hold after its handshake to reset the failure count (default 10 s). */
        public Config stableConnectionMs(long value) {
            stableConnectionMs = positive("stableConnectionMs", value);
            return this;
        }

        /** Chunks that may wait for a slow listener (default 256). */
        public Config maxPendingChunks(int value) {
            maxPendingChunks = (int) positive("maxPendingChunks", value);
            return this;
        }

        /** Payload bytes that may wait for a slow listener (default 4 MiB). */
        public Config maxPendingChunkBytes(long value) {
            maxPendingChunkBytes = positive("maxPendingChunkBytes", value);
            return this;
        }

        private Config copy() {
            Config c = new Config();
            c.connectTimeoutMs = connectTimeoutMs;
            c.readTimeoutMs = readTimeoutMs;
            c.messageDeadlineMs = messageDeadlineMs;
            c.idleTimeoutMs = idleTimeoutMs;
            c.handshakeTimeoutMs = handshakeTimeoutMs;
            c.timeSyncBurstCount = timeSyncBurstCount;
            c.timeSyncBurstIntervalMs = timeSyncBurstIntervalMs;
            c.timeSyncIntervalMs = timeSyncIntervalMs;
            c.maxFailures = maxFailures;
            c.backoffInitialMs = backoffInitialMs;
            c.backoffMaxMs = backoffMaxMs;
            c.stableConnectionMs = stableConnectionMs;
            c.maxPendingChunks = maxPendingChunks;
            c.maxPendingChunkBytes = maxPendingChunkBytes;
            return c;
        }
    }

    private final SnapcastTransport transport;
    private final HelloMessage hello;
    private final Config config;
    private final MicroClock clock;
    private final RetryScheduler scheduler;
    private final ScheduledExecutorService ownScheduler;
    private final Executor listenerExecutor;
    private final ExecutorService ownListenerExecutor;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ClockOffsetFilter offsetFilter = new ClockOffsetFilter();

    private final Object lock = new Object();
    private State state = State.IDLE;
    private long generation;
    private SnapcastTransport.Connection connection;
    private Thread readerThread;
    private int failures;
    private long connectedAtMicros;
    private boolean lossReported;
    private volatile boolean closed;

    private final Object writeLock = new Object();
    private OutputStream activeOut; // guarded by writeLock
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final AtomicInteger pendingChunks = new AtomicInteger();
    private final AtomicLong pendingChunkBytes = new AtomicLong();
    private final AtomicLong droppedChunks = new AtomicLong();
    private final AtomicLong droppedBeforeHeader = new AtomicLong();
    private final AtomicLong unsolicitedTime = new AtomicLong();
    private final AtomicLong rejectedTimeSamples = new AtomicLong();
    private final Object progressLock = new Object();
    private long framesRead; // guarded by progressLock
    private int timeAnswers; // guarded by progressLock

    /**
     * @param transport        opens the connection to the one endpoint the caller chose (never one from the server)
     * @param hello            what to tell the server about this client
     * @param config           the limits and timings; copied
     * @param clock            the client clock of the time sync; null: the monotonic clock
     * @param scheduler        runs the reconnect timers; null: an own single-thread scheduler
     * @param listenerExecutor runs the listeners, one task at a time in order; null: an own thread
     */
    public SnapcastConnection(SnapcastTransport transport, HelloMessage hello, Config config, MicroClock clock,
                              RetryScheduler scheduler, Executor listenerExecutor) {
        if (transport == null || hello == null) {
            throw new IllegalArgumentException("transport and hello are required");
        }
        this.transport = transport;
        this.hello = hello;
        this.config = (config == null ? new Config() : config).copy();
        this.clock = clock == null ? MicroClock.monotonic() : clock;
        if (scheduler == null) {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(daemon("snapcast-bin-retry"));
            ownScheduler = executor;
            this.scheduler = (task, delayMillis) -> {
                try {
                    executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException closedMeanwhile) {
                    // closed: no retry
                }
            };
        } else {
            ownScheduler = null;
            this.scheduler = scheduler;
        }
        if (listenerExecutor == null) {
            ownListenerExecutor = Executors.newSingleThreadExecutor(daemon("snapcast-bin-listener"));
            this.listenerExecutor = ownListenerExecutor;
        } else {
            ownListenerExecutor = null;
            this.listenerExecutor = listenerExecutor;
        }
    }

    /** A connection to this address and port (the default port 1704) with the default settings and own threads. */
    public static SnapcastConnection forAddress(InetAddress address, int port, HelloMessage hello) {
        return new SnapcastConnection(new SocketTransport(address, port), hello, new Config(), null, null, null);
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    /** The clock estimate of this connection; the same object for the life of the connection (reset on every attempt). */
    public ClockOffsetFilter getOffsetFilter() {
        return offsetFilter;
    }

    /** Chunks dropped because the listener was too slow (the bounds on the waiting chunks). */
    public long getDroppedChunks() {
        return droppedChunks.get();
    }

    /** Chunks that arrived before the codec header and were not delivered. */
    public long getDroppedBeforeHeader() {
        return droppedBeforeHeader.get();
    }

    /** Time answers that did not belong to a request of ours. */
    public long getUnsolicitedTime() {
        return unsolicitedTime.get();
    }

    /** Time answers whose network time was longer than our own clock measured. */
    public long getRejectedTimeSamples() {
        return rejectedTimeSamples.get();
    }

    // ---- connection management

    /** Starts connecting; does nothing unless the connection was not started yet. */
    public void start() {
        synchronized (lock) {
            if (state == State.IDLE) {
                startAttemptLocked();
            }
        }
    }

    /**
     * Connects now when the connection waits for a retry or gave up (the failure count starts again). Do not call it in
     * an automatic loop: it removes the ceiling of the reconnects.
     */
    public void retry() {
        synchronized (lock) {
            if (state == State.WAITING_RETRY || state == State.GAVE_UP) {
                failures = 0;
                startAttemptLocked();
            }
        }
    }

    private void startAttemptLocked() {
        generation++;
        state = State.CONNECTING;
        final long attempt = generation;
        Thread thread = new Thread(() -> runAttempt(attempt), "snapcast-bin-reader");
        thread.setDaemon(true);
        readerThread = thread;
        thread.start();
    }

    private boolean isCurrent(long attempt) {
        synchronized (lock) {
            return attempt == generation && state != State.CLOSED;
        }
    }

    /** True while the reader thread of the current attempt is alive. */
    boolean isReaderRunning() {
        Thread thread;
        synchronized (lock) {
            thread = readerThread;
        }
        return thread != null && thread.isAlive();
    }

    @Override
    public void close() {
        SnapcastTransport.Connection toClose;
        Thread thread;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            closed = true;
            generation++;
            toClose = connection;
            connection = null;
            thread = readerThread;
        }
        closeQuietly(toClose);
        if (ownScheduler != null) {
            ownScheduler.shutdownNow();
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(CLOSE_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownListenerExecutor != null) {
            ownListenerExecutor.shutdownNow();
        }
        synchronized (writeLock) {
            activeOut = null;
        }
    }

    private static void closeQuietly(SnapcastTransport.Connection toClose) {
        if (toClose != null) {
            toClose.close();
        }
    }

    // ---- sending

    /**
     * Tells the server the volume of this client (0 to 100) and whether it is muted.
     *
     * @return false when there is no connection or the write failed
     */
    public boolean sendClientInfo(int volume, boolean muted) {
        byte[] bytes = SnapcastMessageCodec.encode(new ClientInfoMessage(volume, muted), nextMessageId(), 0,
                clock.nowMicros());
        try {
            write(bytes);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private int nextMessageId() {
        return nextId.getAndUpdate(value -> value >= 0xffff ? 1 : value + 1);
    }

    private void write(byte[] bytes) throws IOException {
        synchronized (writeLock) {
            if (activeOut == null) {
                throw new IOException("not connected");
            }
            activeOut.write(bytes);
            activeOut.flush();
        }
    }

    // ---- the attempt

    private void runAttempt(final long attempt) {
        SnapcastTransport.Connection opened = null;
        Loss loss = null;
        boolean handshaked = false;
        try {
            try {
                opened = transport.open(config.connectTimeoutMs);
                if (opened == null) {
                    throw new IOException("no connection");
                }
            } catch (IOException | RuntimeException e) {
                loss = new Loss(LossReason.CONNECT_FAILED, "");
            }
            if (loss == null) {
                synchronized (lock) {
                    if (attempt != generation || state != State.CONNECTING) {
                        return; // closed or replaced while connecting: the finally block closes the connection
                    }
                    connection = opened;
                    state = State.CONNECTED;
                    connectedAtMicros = clock.nowMicros();
                    offsetFilter.reset();
                }
                Session session = new Session(attempt, opened);
                loss = session.run();
                handshaked = session.handshaked;
            }
        } catch (Throwable t) {
            // the worker boundary: a StackOverflowError or OutOfMemoryError must not end the thread unreported
            loss = new Loss(LossReason.INTERNAL, "");
        } finally {
            closeQuietly(opened);
        }
        attemptEnded(attempt, handshaked, loss);
    }

    private void attemptEnded(long attempt, boolean handshaked, Loss loss) {
        boolean giveUp;
        boolean reportLoss;
        long delay = 0;
        final long token;
        synchronized (lock) {
            if (attempt != generation || state == State.CLOSED) {
                return;
            }
            connection = null;
            if (handshaked && (clock.nowMicros() - connectedAtMicros) >= config.stableConnectionMs * 1000L) {
                failures = 0;
            }
            failures++;
            giveUp = loss.isPermanent() || failures >= config.maxFailures;
            if (giveUp) {
                state = State.GAVE_UP;
            } else {
                state = State.WAITING_RETRY;
                int shift = Math.min(failures - 1, 20);
                delay = Math.min(config.backoffMaxMs, config.backoffInitialMs << shift);
            }
            reportLoss = !lossReported;
            lossReported = true;
            token = generation;
        }
        synchronized (writeLock) {
            activeOut = null;
        }
        // the retry is set up before the listeners hear of the loss, so a listener that reacts to it finds it scheduled
        if (!giveUp) {
            scheduler.schedule(() -> retryTimerFired(token), delay);
        }
        if (reportLoss) {
            dispatch(listener -> listener.onLost(loss));
        }
        if (giveUp) {
            dispatch(listener -> listener.onGaveUp(loss));
        }
    }

    private void retryTimerFired(long token) {
        synchronized (lock) {
            // a retry() or close() since the timer was set has made this one stale
            if (state == State.WAITING_RETRY && generation == token) {
                startAttemptLocked();
            }
        }
    }

    // ---- listeners

    private void dispatch(final Consumer<Listener> call) {
        if (closed) {
            return;
        }
        try {
            listenerExecutor.execute(() -> deliver(call));
        } catch (RejectedExecutionException e) {
            // the executor was shut down: nothing to tell
        }
    }

    private void deliver(Consumer<Listener> call) {
        if (closed) {
            return;
        }
        for (Listener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                // a broken listener must not keep the others from hearing
            }
        }
    }

    private void dispatchChunk(final WireChunkMessage chunk) {
        final int size = chunk.getPayloadLength();
        if (pendingChunks.get() >= config.maxPendingChunks || pendingChunkBytes.get() + size > config.maxPendingChunkBytes) {
            droppedChunks.incrementAndGet();
            return;
        }
        pendingChunks.incrementAndGet();
        pendingChunkBytes.addAndGet(size);
        try {
            listenerExecutor.execute(() -> {
                try {
                    deliver(listener -> listener.onChunk(chunk));
                } finally {
                    pendingChunks.decrementAndGet();
                    pendingChunkBytes.addAndGet(-size);
                }
            });
        } catch (RejectedExecutionException e) {
            pendingChunks.decrementAndGet();
            pendingChunkBytes.addAndGet(-size);
        }
    }

    // ---- progress for tests

    /** Waits until at least {@code count} answers to time requests were processed (accepted or not). */
    boolean awaitTimeSamples(int count, long timeoutMillis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (progressLock) {
            while (timeAnswers < count) {
                long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (left <= 0) {
                    return false;
                }
                progressLock.wait(left);
            }
            return true;
        }
    }

    /** Waits until at least {@code count} frames of the server were read and handled in all (counting from the first attempt). */
    boolean awaitFramesRead(long count, long timeoutMillis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (progressLock) {
            while (framesRead < count) {
                long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (left <= 0) {
                    return false;
                }
                progressLock.wait(left);
            }
            return true;
        }
    }

    private void noteFrame() {
        synchronized (progressLock) {
            framesRead++;
            progressLock.notifyAll();
        }
    }

    private void noteTimeAnswer() {
        synchronized (progressLock) {
            timeAnswers++;
            progressLock.notifyAll();
        }
    }

    // ---- one connection

    /** The state of one connected attempt; used by the reader thread only. */
    private final class Session {
        private final long attempt;
        private final SnapcastTransport.Connection socket;
        boolean handshaked;
        private boolean settingsSeen;
        private boolean headerSeen;
        private int consecutiveUnexpected;
        private long startedAt;
        private long lastReceivedAt;
        private long nextTimeSyncAt;
        private int timeRequestsSent;
        private final Map<Integer, Long> pendingTime = new LinkedHashMap<>();

        Session(long attempt, SnapcastTransport.Connection socket) {
            this.attempt = attempt;
            this.socket = socket;
        }

        /** Runs until the connection ends; returns why. */
        Loss run() {
            OutputStream out = null;
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();
                synchronized (writeLock) {
                    activeOut = out;
                }
                FrameReader reader = new FrameReader(in, clock, config.messageDeadlineMs);
                startedAt = clock.nowMicros();
                lastReceivedAt = startedAt;
                write(SnapcastMessageCodec.encode(hello, nextMessageId(), 0, startedAt));
                while (isCurrent(attempt)) {
                    long now = clock.nowMicros();
                    if (settingsSeen && now >= nextTimeSyncAt) {
                        sendTimeRequest(now);
                    }
                    if (!handshaked && now - startedAt > config.handshakeTimeoutMs * 1000L) {
                        return new Loss(LossReason.TIMEOUT, "handshake");
                    }
                    if (now - lastReceivedAt > config.idleTimeoutMs * 1000L) {
                        return new Loss(LossReason.TIMEOUT, "idle");
                    }
                    socket.setReadTimeout(readTimeoutMillis(now));
                    Frame frame;
                    try {
                        frame = reader.read();
                    } catch (SocketTimeoutException idle) {
                        continue; // nothing arrived: look at the clocks again
                    } catch (EOFException closedByServer) {
                        return new Loss(LossReason.SERVER_CLOSED, "");
                    }
                    now = clock.nowMicros();
                    lastReceivedAt = now;
                    Loss problem = handle(frame, now);
                    if (problem != null) {
                        return problem;
                    }
                    noteFrame(); // counted when handled (delivered or dropped), so a waiter sees the final state
                }
                return new Loss(LossReason.SERVER_CLOSED, ""); // closed or replaced: the result is not used
            } catch (SnapcastProtocolException e) {
                return lossOf(e);
            } catch (IOException e) {
                return new Loss(LossReason.IO_ERROR, "");
            } finally {
                synchronized (writeLock) {
                    if (activeOut == out) {
                        activeOut = null;
                    }
                }
            }
        }

        private Loss lossOf(SnapcastProtocolException e) {
            switch (e.getKind()) {
                case UNSUPPORTED_CODEC:
                    return new Loss(LossReason.UNSUPPORTED_CODEC, e.getMessage());
                case TIMEOUT:
                case DEADLINE:
                    return new Loss(LossReason.TIMEOUT, "");
                case TRUNCATED:
                    return new Loss(LossReason.SERVER_CLOSED, "");
                default:
                    return new Loss(LossReason.PROTOCOL_ERROR, "");
            }
        }

        /** How long the next read may block: the read timeout, cut short by whatever is due earlier. */
        private int readTimeoutMillis(long now) {
            long millis = config.readTimeoutMs;
            if (settingsSeen) {
                millis = Math.min(millis, untilMillis(nextTimeSyncAt, now));
            }
            millis = Math.min(millis, untilMillis(lastReceivedAt + config.idleTimeoutMs * 1000L, now));
            if (!handshaked) {
                millis = Math.min(millis, untilMillis(startedAt + config.handshakeTimeoutMs * 1000L, now));
            }
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
        }

        private long untilMillis(long dueMicros, long now) {
            return Math.max(1, (dueMicros - now + 999) / 1000);
        }

        private void sendTimeRequest(long now) throws IOException {
            int id = nextMessageId();
            if (pendingTime.size() >= MAX_PENDING_TIME_REQUESTS) {
                Iterator<Integer> oldest = pendingTime.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            pendingTime.put(id, now);
            write(SnapcastMessageCodec.encode(new TimeMessage(0, 0), id, 0, now));
            timeRequestsSent++;
            long interval = timeRequestsSent < config.timeSyncBurstCount ? config.timeSyncBurstIntervalMs
                    : config.timeSyncIntervalMs;
            nextTimeSyncAt = now + interval * 1000L;
        }

        /** @return why the connection must end, or null to go on */
        private Loss handle(Frame frame, long now) {
            if (frame.isSkipped()) {
                return unexpected();
            }
            SnapcastBinaryMessage message = frame.getMessage();
            switch (message.type()) {
                case SERVER_SETTINGS: {
                    consecutiveUnexpected = 0;
                    final ServerSettingsMessage settings = (ServerSettingsMessage) message;
                    if (!settingsSeen) {
                        settingsSeen = true;
                        nextTimeSyncAt = now;
                    }
                    if (isCurrent(attempt)) {
                        dispatch(listener -> listener.onServerSettings(settings));
                    }
                    checkHandshake();
                    return null;
                }
                case CODEC_HEADER: {
                    consecutiveUnexpected = 0;
                    final CodecHeaderMessage header = (CodecHeaderMessage) message;
                    headerSeen = true;
                    if (isCurrent(attempt)) {
                        dispatch(listener -> listener.onCodecHeader(header));
                    }
                    checkHandshake();
                    return null;
                }
                case WIRE_CHUNK:
                    consecutiveUnexpected = 0;
                    if (!headerSeen) {
                        droppedBeforeHeader.incrementAndGet();
                    } else if (isCurrent(attempt)) {
                        dispatchChunk((WireChunkMessage) message);
                    }
                    return null;
                case TIME:
                    consecutiveUnexpected = 0;
                    handleTime(frame, now);
                    return null;
                case ERROR: {
                    ErrorMessage error = (ErrorMessage) message;
                    return new Loss(error.isAuthorizationError() ? LossReason.UNAUTHORIZED : LossReason.SERVER_ERROR,
                            error.getMessage());
                }
                default:
                    return unexpected(); // a Hello or a ClientInfo is not something a server sends
            }
        }

        private Loss unexpected() {
            if (++consecutiveUnexpected > MAX_CONSECUTIVE_UNEXPECTED) {
                return new Loss(LossReason.PROTOCOL_ERROR, "");
            }
            return null;
        }

        private void checkHandshake() {
            if (settingsSeen && headerSeen && !handshaked) {
                handshaked = true;
                synchronized (lock) {
                    if (attempt == generation) {
                        lossReported = false; // the outage is over
                    }
                }
            }
        }

        private void handleTime(Frame frame, long now) {
            MessageHeader header = frame.getHeader();
            Long sentAt = pendingTime.remove(header.getRefersTo());
            if (sentAt == null) {
                unsolicitedTime.incrementAndGet();
                return;
            }
            long c2s = ((TimeMessage) frame.getMessage()).latencyMicros();
            long s2c = now - header.sentMicros();
            long measuredRoundTrip = now - sentAt;
            boolean accepted = false;
            if (c2s + s2c > measuredRoundTrip + TIME_CONSISTENCY_TOLERANCE_MICROS) {
                // the server claims more network time than our own clock saw pass: its numbers are not to be believed
                rejectedTimeSamples.incrementAndGet();
            } else {
                accepted = offsetFilter.addSample(c2s, s2c);
            }
            if (accepted && offsetFilter.isSynchronized() && isCurrent(attempt)) {
                final long offset = offsetFilter.offsetMicros();
                final long jitter = offsetFilter.jitterMicros();
                dispatch(listener -> listener.onOffsetUpdate(offset, jitter));
            }
            noteTimeAnswer();
        }
    }
}
