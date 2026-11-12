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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
 * <p><b>Threads.</b> One reader thread per connection attempt reads and decodes, and decides when a Time request is due;
 * one writer thread is the only code that writes to the socket (so a server that stops reading holds nobody: a full
 * queue of 32 ends the connection). The reader counts the frames of the server and drops the connection above
 * {@code maxFramesPerSecond} (2000) of the injected clock. Neither thread calls a listener. Listeners run on a serial executor (an own thread unless the caller gives one),
 * in the order of the stream. What waits for a slow listener is bounded for every kind of event: chunks in count and bytes
 * (a chunk beyond the bounds is dropped and counted), server settings and offset updates keep only the latest value,
 * and codec headers (never replaced or dropped silently, the order to the chunks matters) are limited to
 * {@code maxPendingHeaders} (8); more waiting than that is a flood and ends the connection as PROTOCOL_ERROR. The
 * outage events {@code onLost} and {@code onGaveUp} are limited by the reconnect rules (one report per outage, five
 * failures in a row). A listener that throws is skipped for that call.
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
        /** The codec header has values the engine does not support (sample rate, bit depth, channels). Permanent. */
        UNSUPPORTED_FORMAT,
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

        /**
         * For a TIMEOUT, which limit of ours it was (a number, never the server's text): 1 the idle limit, 2 the handshake
         * limit, 3 the writer queue was full ("the server does not read"), 4 a message in the middle was too slow or a read
         * timed out; 0 for every other reason (the server or the network ended it).
         */
        public int getOwnLimitCode() {
            if (reason != LossReason.TIMEOUT) {
                return 0;
            }
            switch (detail) {
                case "idle":
                    return 1;
                case "handshake":
                    return 2;
                case "the server does not read":
                    return 3;
                default:
                    return 4;
            }
        }

        /** True when a retry would meet the same answer. */
        public boolean isPermanent() {
            return reason == LossReason.UNAUTHORIZED || reason == LossReason.UNSUPPORTED_CODEC
                    || reason == LossReason.UNSUPPORTED_FORMAT;
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
        private int maxPendingHeaders = 8;
        private int maxFramesPerSecond = 2_000;

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

        /**
         * Codec headers that may wait for a slow listener (default 8). A server sends one per stream; more waiting than
         * that is a flood and the connection is dropped as PROTOCOL_ERROR (a header is never dropped silently).
         */
        public Config maxPendingHeaders(int value) {
            maxPendingHeaders = (int) positive("maxPendingHeaders", value);
            return this;
        }

        /**
         * The most frames of the server that are accepted in one second (default 2000), counted on the injected clock;
         * more ends the connection as PROTOCOL_ERROR. A real server sends one chunk per {@code chunk_ms} (20 ms by
         * default, so 50 a second) and one Time answer per request (about one a second); 2000 a second would be a chunk
         * every half millisecond.
         */
        public Config maxFramesPerSecond(int value) {
            maxFramesPerSecond = (int) positive("maxFramesPerSecond", value);
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
            c.maxPendingHeaders = maxPendingHeaders;
            c.maxFramesPerSecond = maxFramesPerSecond;
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

    private final AtomicReference<Writer> activeWriter = new AtomicReference<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final AtomicInteger pendingChunks = new AtomicInteger();
    private final AtomicLong pendingChunkBytes = new AtomicLong();
    private final AtomicInteger pendingHeaders = new AtomicInteger();
    private final AtomicReference<ServerSettingsMessage> latestSettings = new AtomicReference<>();
    private final AtomicReference<long[]> latestOffset = new AtomicReference<>();
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

    /**
     * A connection to this address and port (default port {@value #DEFAULT_PORT}) with the default settings and own
     * threads. Pass an address that is already resolved (the one the control connection uses, or one resolved once by the
     * caller), never a name: a name is looked up again at every reconnect, which can change its answer and holds a
     * thread in the resolver that {@link #close()} can not end. The wildcard, multicast addresses and bad ports are
     * refused with an {@link IllegalArgumentException}.
     */
    public static SnapcastConnection forAddress(InetAddress address, int port, HelloMessage hello) {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            // the wildcard connects to the phone itself, a multicast address to nobody in particular
            throw new IllegalArgumentException("not an address of a server");
        }
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

    /** Frames of the server that were read and handled so far (all attempts together). */
    long framesHandled() {
        synchronized (progressLock) {
            return framesRead;
        }
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

    /**
     * Ends the connection for good. Not for the main thread: it waits up to 2 s for the reader thread, which can be
     * held that long by a connect that is in progress.
     */
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
        activeWriter.set(null);
    }

    private static void closeQuietly(SnapcastTransport.Connection toClose) {
        if (toClose != null) {
            toClose.close();
        }
    }

    // ---- sending

    /**
     * Tells the server the volume of this client (0 to 100) and whether it is muted. It never blocks: the message is
     * handed to the writer thread of the connection, and when an earlier one has not been written yet it is replaced
     * (only the latest values matter).
     *
     * @return false when there is no connection or the writer's queue is full
     */
    public boolean sendClientInfo(int volume, boolean muted) {
        Writer writer = activeWriter.get();
        if (writer == null) {
            return false;
        }
        byte[] bytes = SnapcastMessageCodec.encode(new ClientInfoMessage(volume, muted), nextMessageId(), 0,
                clock.nowMicros());
        return writer.offerClientInfo(bytes);
    }

    private int nextMessageId() {
        return nextId.getAndUpdate(value -> value >= 0xffff ? 1 : value + 1);
    }

    /**
     * The only thread that writes to the socket. A server that stops reading can block a write for good (a socket has no
     * write timeout), so nobody else waits for one: the reader hands messages over through a bounded queue, and when the
     * queue is full the server is not reading and the connection is given up. A failed write closes the socket, which
     * ends the reader.
     */
    private static final class Writer {
        private static final int MAX_QUEUED = 32;
        private static final byte[] CLIENT_INFO_MARKER = new byte[0];

        private final java.util.concurrent.LinkedBlockingQueue<byte[]> queue = new java.util.concurrent.LinkedBlockingQueue<>(MAX_QUEUED);
        private final AtomicReference<byte[]> clientInfo = new AtomicReference<>();
        private final Thread thread;
        private volatile boolean internalError;

        Writer(final OutputStream out, final SnapcastTransport.Connection socket) {
            thread = new Thread(() -> {
                try {
                    while (true) {
                        byte[] next = queue.take();
                        if (next == CLIENT_INFO_MARKER) {
                            next = clientInfo.getAndSet(null);
                            if (next == null) {
                                continue;
                            }
                        }
                        out.write(next);
                        out.flush();
                    }
                } catch (IOException | InterruptedException e) {
                    socket.close(); // wakes the reader
                } catch (Throwable t) {
                    // a bug in a stream must not reach the uncaught handler (a crash of the app) or leave the connection
                    // up with a writer that is gone: remember it for the reader's loss and close the socket
                    internalError = true;
                    socket.close();
                }
            }, "snapcast-bin-writer");
            thread.setDaemon(true);
            thread.start();
        }

        /** @return false when the queue is full: the server does not read */
        boolean offer(byte[] bytes) {
            return queue.offer(bytes);
        }

        boolean offerClientInfo(byte[] bytes) {
            if (clientInfo.getAndSet(bytes) == null && !queue.offer(CLIENT_INFO_MARKER)) {
                clientInfo.set(null);
                return false;
            }
            return true;
        }

        /** True when the writer ended because of something that is not a failure of the network. */
        boolean hasInternalError() {
            return internalError;
        }

        void stop() {
            thread.interrupt();
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
        // activeWriter is not touched here: the session cleared its own writer (compare and clear) and a new attempt
        // may have published its writer already
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

    /**
     * Keeps only the latest value of a kind of event that a slow listener has not heard yet: the first value queues a
     * task, later ones replace the value, and the task delivers whatever is newest when it runs.
     */
    private <T> void dispatchLatest(final AtomicReference<T> slot, T value, final BiConsumer<Listener, T> call) {
        if (closed || slot.getAndSet(value) != null) {
            return; // a task is queued already and will take the newest value
        }
        try {
            listenerExecutor.execute(() -> {
                final T latest = slot.getAndSet(null);
                if (latest != null) {
                    deliver(listener -> call.accept(listener, latest));
                }
            });
        } catch (RejectedExecutionException e) {
            slot.set(null);
        }
    }

    /**
     * Codec headers keep their order relative to the chunks and are never replaced or dropped silently; the number
     * waiting is bounded instead.
     *
     * @return false when too many wait: the caller drops the connection
     */
    private boolean dispatchHeader(final CodecHeaderMessage header) {
        if (pendingHeaders.incrementAndGet() > config.maxPendingHeaders) {
            pendingHeaders.decrementAndGet();
            return false;
        }
        try {
            listenerExecutor.execute(() -> {
                try {
                    deliver(listener -> listener.onCodecHeader(header));
                } finally {
                    pendingHeaders.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pendingHeaders.decrementAndGet();
        }
        return true;
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
        private long windowStartedAt;
        private int framesInWindow;
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
            Writer writer = null;
            try {
                InputStream in = socket.getInputStream();
                writer = new Writer(socket.getOutputStream(), socket);
                FrameReader reader = FrameReader.forClient(in, clock, config.messageDeadlineMs, socket::setReadTimeout,
                        (int) Math.min(Integer.MAX_VALUE, config.maxPendingChunkBytes));
                startedAt = clock.nowMicros();
                lastReceivedAt = startedAt;
                writer.offer(SnapcastMessageCodec.encode(hello, nextMessageId(), 0, startedAt));
                activeWriter.set(writer); // only now: a sendClientInfo from another thread can not go out before the Hello
                while (isCurrent(attempt)) {
                    long now = clock.nowMicros();
                    if (settingsSeen && now >= nextTimeSyncAt && !sendTimeRequest(writer, now)) {
                        return new Loss(LossReason.TIMEOUT, "the server does not read");
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
                    if (overBudget(now)) {
                        return new Loss(LossReason.PROTOCOL_ERROR, "too many frames");
                    }
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
                return new Loss(writer != null && writer.hasInternalError() ? LossReason.INTERNAL : LossReason.IO_ERROR, "");
            } finally {
                if (writer != null) {
                    activeWriter.compareAndSet(writer, null);
                    writer.stop();
                }
            }
        }

        /** Counts the frame in the window of the current second of the injected clock. */
        private boolean overBudget(long now) {
            if (now - windowStartedAt >= 1_000_000L || now < windowStartedAt) {
                windowStartedAt = now;
                framesInWindow = 0;
            }
            return ++framesInWindow > config.maxFramesPerSecond;
        }

        private Loss lossOf(SnapcastProtocolException e) {
            switch (e.getKind()) {
                case UNSUPPORTED_CODEC:
                    return new Loss(LossReason.UNSUPPORTED_CODEC, e.getMessage());
                case UNSUPPORTED_FORMAT:
                    return new Loss(LossReason.UNSUPPORTED_FORMAT, e.getMessage());
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

        /** @return false when the writer's queue is full */
        private boolean sendTimeRequest(Writer writer, long now) {
            int id = nextMessageId();
            if (pendingTime.size() >= MAX_PENDING_TIME_REQUESTS) {
                Iterator<Integer> oldest = pendingTime.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            pendingTime.put(id, now);
            boolean queued = writer.offer(SnapcastMessageCodec.encode(new TimeMessage(0, 0), id, 0, now));
            timeRequestsSent++;
            long interval = timeRequestsSent < config.timeSyncBurstCount ? config.timeSyncBurstIntervalMs
                    : config.timeSyncIntervalMs;
            nextTimeSyncAt = now + interval * 1000L;
            return queued;
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
                        dispatchLatest(latestSettings, settings, Listener::onServerSettings);
                    }
                    checkHandshake();
                    return null;
                }
                case CODEC_HEADER: {
                    consecutiveUnexpected = 0;
                    final CodecHeaderMessage header = (CodecHeaderMessage) message;
                    headerSeen = true;
                    if (isCurrent(attempt) && !dispatchHeader(header)) {
                        return new Loss(LossReason.PROTOCOL_ERROR, "too many codec headers");
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
                    if (!handleTime(frame, now)) {
                        return unexpected(); // an answer nobody asked for must not make the other strange frames forgiven
                    }
                    consecutiveUnexpected = 0;
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

        /** @return false for an answer that does not belong to a request of ours */
        private boolean handleTime(Frame frame, long now) {
            MessageHeader header = frame.getHeader();
            Long sentAt = pendingTime.remove(header.getRefersTo());
            if (sentAt == null) {
                unsolicitedTime.incrementAndGet();
                return false;
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
                dispatchLatest(latestOffset, new long[]{offsetFilter.offsetMicros(), offsetFilter.jitterMicros()},
                        (listener, values) -> listener.onOffsetUpdate(values[0], values[1]));
            }
            noteTimeAnswer();
            return true;
        }
    }
}
