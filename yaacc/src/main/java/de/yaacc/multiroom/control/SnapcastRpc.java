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
package de.yaacc.multiroom.control;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The JSON-RPC 2.0 client of a Snapcast server: newline-delimited messages over one long-lived TCP connection that
 * carries the calls, their answers and the server's notifications.
 *
 * <p><b>Threads.</b> One reader thread per connection attempt reads, parses and routes; one writer thread sends the
 * requests (so a server that stops reading can not hold a caller beyond its deadline); the listeners are called, in
 * order, on a separate executor and never on the socket thread. Calls block the calling thread.
 *
 * <p><b>Order.</b> Everything that changes the model is handed to the listener executor in the order of the lines of
 * the connection: the notifications, and the results of {@link #callOrdered}. So an answer (a status) and the
 * notification the server sent right behind it can not overtake each other.
 *
 * <p><b>Hostile servers.</b> A line is read at the byte level and capped (1 MiB), the text is scanned before
 * {@code org.json} sees it ({@link SnapcastJson}), a batch is capped (64), the notification queue is capped (256
 * entries and 4 MiB of lines; the rest is dropped and reported through {@link Listener#onNotificationsLost()}),
 * notifications of methods that the model does not know are not queued at all (they only report "read again"), the
 * pending requests are capped (32), every call has an overall deadline, ids are made here, and a {@link Throwable} at
 * the worker boundary drops the connection instead of ending the thread. A call that ends at its deadline although
 * nothing at all was received since its request was written (a box that was switched off) drops the connection.
 *
 * <p><b>Reconnect.</b> A lost connection is retried with a doubling delay (1 s, 2 s, ... at most 30 s); after 5
 * failures in a row it stops ({@link State#GAVE_UP}) until {@link #retry()} is called. A connection that held for
 * {@link Config#stableConnectionMs} counts as a success. {@link #reconnect()} replaces a connection that looks alive.
 */
public final class SnapcastRpc implements SnapcastCaller, Closeable {

    /** The state of the connection. */
    public enum State {
        /** Not started. */
        IDLE,
        /** A connection is being opened. */
        CONNECTING,
        /** Connected: calls are possible. */
        CONNECTED,
        /** The connection failed; the next attempt is scheduled. */
        WAITING_RETRY,
        /** Too many failures in a row; nothing happens until {@link #retry()}. */
        GAVE_UP,
        /** Closed for good. */
        CLOSED
    }

    /** Receives what happens, on the listener executor (never on the socket thread). All methods do nothing by default. */
    public interface Listener {
        /** The connection is up. */
        default void onConnected() {
        }

        /**
         * A notification of the server, of a method that {@link SnapcastModelUpdater} knows. Notifications of other
         * methods are not delivered: they only cause {@link #onNotificationsLost()}.
         */
        default void onNotification(String method, JSONObject params) {
        }

        /** Notifications were dropped or a line was unreadable: the model may be out of date, read it again. */
        default void onNotificationsLost() {
        }

        /** An attempt ended: the connection was lost, or could not be made. */
        default void onDisconnected(SnapcastException cause) {
        }

        /** The reconnects stopped after too many failures; {@link SnapcastRpc#retry()} starts them again. */
        default void onGaveUp(SnapcastException cause) {
        }
    }

    /** The settings; the defaults are the limits of the design. */
    public static final class Config {
        private long callTimeoutMs = 10_000;
        private int readTimeoutMs = 5_000;
        private int connectTimeoutMs = 5_000;
        private long lineDeadlineMs = 10_000;
        private int maxLineBytes = SnapcastLimits.MAX_LINE_BYTES;
        private int maxRequestBytes = SnapcastLimits.MAX_REQUEST_BYTES;
        private int maxPending = SnapcastLimits.MAX_PENDING_CALLS;
        private int maxBatch = SnapcastLimits.MAX_BATCH;
        private int notificationQueueSize = SnapcastLimits.MAX_QUEUED_NOTIFICATIONS;
        private long notificationQueueBytes = SnapcastLimits.MAX_QUEUED_NOTIFICATION_BYTES;
        private int maxFailures = 5;
        private long backoffInitialMs = 1_000;
        private long backoffMaxMs = 30_000;
        private long stableConnectionMs = 10_000;
        private int maxBadLines = 16;

        public Config() {
        }

        private Config(Config other) {
            callTimeoutMs = other.callTimeoutMs;
            readTimeoutMs = other.readTimeoutMs;
            connectTimeoutMs = other.connectTimeoutMs;
            lineDeadlineMs = other.lineDeadlineMs;
            maxLineBytes = other.maxLineBytes;
            maxRequestBytes = other.maxRequestBytes;
            maxPending = other.maxPending;
            maxBatch = other.maxBatch;
            notificationQueueSize = other.notificationQueueSize;
            notificationQueueBytes = other.notificationQueueBytes;
            maxFailures = other.maxFailures;
            backoffInitialMs = other.backoffInitialMs;
            backoffMaxMs = other.backoffMaxMs;
            stableConnectionMs = other.stableConnectionMs;
            maxBadLines = other.maxBadLines;
        }

        /** The overall deadline of a call, from the call to the answer (default 10 s). */
        public Config callTimeoutMs(long value) {
            callTimeoutMs = value;
            return this;
        }

        /** How long one read may block (default 5 s); a stall in the middle of a line drops the connection. */
        public Config readTimeoutMs(int value) {
            readTimeoutMs = value;
            return this;
        }

        public Config connectTimeoutMs(int value) {
            connectTimeoutMs = value;
            return this;
        }

        /** How long a line may take from its first byte to its end (default 10 s). */
        public Config lineDeadlineMs(long value) {
            lineDeadlineMs = value;
            return this;
        }

        public Config maxLineBytes(int value) {
            maxLineBytes = value;
            return this;
        }

        public Config maxRequestBytes(int value) {
            maxRequestBytes = value;
            return this;
        }

        public Config maxPending(int value) {
            maxPending = value;
            return this;
        }

        public Config maxBatch(int value) {
            maxBatch = value;
            return this;
        }

        public Config notificationQueueSize(int value) {
            notificationQueueSize = value;
            return this;
        }

        /**
         * The bytes (the sizes of their lines) that notifications may occupy while they wait for the listeners
         * (default 4 MiB). One notification is always accepted into an empty queue.
         */
        public Config notificationQueueBytes(long value) {
            notificationQueueBytes = value;
            return this;
        }

        /** Consecutive failures after which the reconnects stop (default 5). */
        public Config maxFailures(int value) {
            maxFailures = value;
            return this;
        }

        public Config backoffInitialMs(long value) {
            backoffInitialMs = value;
            return this;
        }

        public Config backoffMaxMs(long value) {
            backoffMaxMs = value;
            return this;
        }

        /** How long a connection must hold to reset the failure count (default 10 s). */
        public Config stableConnectionMs(long value) {
            stableConnectionMs = value;
            return this;
        }

        /** Unreadable lines in a row after which the connection is dropped (default 16). */
        public Config maxBadLines(int value) {
            maxBadLines = value;
            return this;
        }

        public long getCallTimeoutMs() {
            return callTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public long getLineDeadlineMs() {
            return lineDeadlineMs;
        }

        public int getMaxLineBytes() {
            return maxLineBytes;
        }

        public int getMaxPending() {
            return maxPending;
        }

        public int getMaxBatch() {
            return maxBatch;
        }

        public int getNotificationQueueSize() {
            return notificationQueueSize;
        }

        public long getNotificationQueueBytes() {
            return notificationQueueBytes;
        }

        public int getMaxFailures() {
            return maxFailures;
        }

        public long getBackoffInitialMs() {
            return backoffInitialMs;
        }

        public long getBackoffMaxMs() {
            return backoffMaxMs;
        }

        public long getStableConnectionMs() {
            return stableConnectionMs;
        }

        public int getMaxBadLines() {
            return maxBadLines;
        }
    }

    /** One entry of the queue to the listeners. */
    private interface Delivery {
        void to(Listener listener);
    }

    private static final class QueuedEvent {
        /** Told to every listener; null for an ordered result. */
        final Delivery delivery;
        /** The call whose result is handled in line order; null for the others. */
        final PendingCall orderedCall;
        final Runnable orderedTask;
        final boolean notification;
        /** The bytes a notification is charged. */
        final int weight;

        private QueuedEvent(Delivery delivery, PendingCall orderedCall, Runnable orderedTask, boolean notification, int weight) {
            this.delivery = delivery;
            this.orderedCall = orderedCall;
            this.orderedTask = orderedTask;
            this.notification = notification;
            this.weight = weight;
        }

        static QueuedEvent lifecycle(Delivery delivery) {
            return new QueuedEvent(delivery, null, null, false, 0);
        }

        static QueuedEvent notification(Delivery delivery, int weight) {
            return new QueuedEvent(delivery, null, null, true, weight);
        }

        static QueuedEvent ordered(PendingCall call, Runnable task) {
            return new QueuedEvent(null, call, task, false, 0);
        }
    }

    /** A call that waits for its answer. */
    private static final class PendingCall {
        /** Handles the result in line order, or null for a plain call. */
        final SnapcastCaller.ResultHandler<?> handler;
        private boolean done;
        private boolean written;
        private long writeStartedNanos;
        private Object result;
        private SnapcastException error;

        PendingCall(SnapcastCaller.ResultHandler<?> handler) {
            this.handler = handler;
        }

        synchronized void complete(Object value) {
            if (!done) {
                result = value;
                done = true;
                notifyAll();
            }
        }

        synchronized void fail(SnapcastException failure) {
            if (!done) {
                error = failure;
                done = true;
                notifyAll();
            }
        }

        /** Runs the handler (on the listener executor) and ends the call with its value or its failure. */
        void runHandler(JSONObject value) {
            try {
                complete(handler.handle(value));
            } catch (SnapcastException e) {
                fail(e);
            } catch (Throwable t) {
                fail(SnapcastException.internal(t));
            }
        }

        synchronized void markWriteStarted() {
            writeStartedNanos = System.nanoTime();
        }

        synchronized void markWritten() {
            written = true;
        }

        synchronized boolean isWritten() {
            return written;
        }

        /**
         * True when the request is not out yet, or when no byte at all came in since it went out (so the peer is
         * stuck or gone, not only slow).
         */
        synchronized boolean looksStuck(long lastReceivedNanos) {
            return !written || lastReceivedNanos - writeStartedNanos < 0;
        }

        synchronized Object await(long deadlineNanos) throws SnapcastException {
            while (!done) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw SnapcastException.timeout("no answer in time");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw SnapcastException.internal(e);
                }
            }
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    /** Set while a thread delivers events of that client: a call that waits for the delivery would wait for itself. */
    private static final ThreadLocal<SnapcastRpc> DELIVERING = new ThreadLocal<>();

    private final SnapcastTransport transport;
    private final Config config;
    private final MonotonicClock clock;
    private final RetryScheduler scheduler;
    private final ScheduledExecutorService ownScheduler;
    private final Executor listenerExecutor;
    private final ExecutorService ownListenerExecutor;
    private final ExecutorService writer;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private State state = State.IDLE;
    private SnapcastTransport.Connection connection;
    private long generation;
    private int failures;
    private long connectedAt;
    /** When a byte was last received on the connection (System.nanoTime), to tell a slow server from a gone one. */
    private volatile long lastReceivedNanos = System.nanoTime();
    private long nextId = 1;
    private final Map<Long, PendingCall> pending = new HashMap<>();

    private final Object eventLock = new Object();
    private final ArrayDeque<QueuedEvent> events = new ArrayDeque<>();
    private int queuedNotifications;
    private long queuedNotificationBytes;
    private boolean draining;
    private boolean lost;
    private boolean eventsClosed;

    /**
     * @param transport        opens the connection
     * @param config           the settings (copied)
     * @param scheduler        runs the reconnect timers; null: an own single-thread scheduler
     * @param listenerExecutor runs the listeners, one task at a time in order; null: an own thread
     * @param clock            the time source for the line deadline and the stable-connection rule
     */
    public SnapcastRpc(SnapcastTransport transport, Config config, RetryScheduler scheduler, Executor listenerExecutor,
                       MonotonicClock clock) {
        this.transport = transport;
        this.config = new Config(config);
        this.clock = clock;
        if (scheduler == null) {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(daemonFactory("snapcast-rpc-retry"));
            ownScheduler = executor;
            this.scheduler = (task, delayMillis) -> {
                try {
                    executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException closed) {
                    // closed meanwhile: no retry
                }
            };
        } else {
            ownScheduler = null;
            this.scheduler = scheduler;
        }
        if (listenerExecutor == null) {
            ownListenerExecutor = Executors.newSingleThreadExecutor(daemonFactory("snapcast-rpc-listener"));
            this.listenerExecutor = ownListenerExecutor;
        } else {
            ownListenerExecutor = null;
            this.listenerExecutor = listenerExecutor;
        }
        writer = Executors.newSingleThreadExecutor(daemonFactory("snapcast-rpc-writer"));
    }

    /** A client for the control port of a server with the default settings and own threads. */
    public static SnapcastRpc forAddress(String host, int port) {
        return new SnapcastRpc(new SocketTransport(host, port), new Config(), null, null, MonotonicClock.SYSTEM);
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    /** The number of calls waiting for an answer. */
    public int getPendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** The number of events waiting for the listeners (the bound is {@link Config#notificationQueueSize} plus a few). */
    public int getQueuedEventCount() {
        synchronized (eventLock) {
            return events.size();
        }
    }

    /** The bytes the waiting notifications are charged (the bound is {@link Config#notificationQueueBytes}). */
    public long getQueuedNotificationBytes() {
        synchronized (eventLock) {
            return queuedNotificationBytes;
        }
    }

    /**
     * The address that the control connection is connected to; null while there is no connection. The feed of a
     * stream connects to this address and to no other (never to an address that a server reply names).
     */
    public InetAddress getPeerAddress() {
        synchronized (lock) {
            return connection == null ? null : connection.getPeerAddress();
        }
    }

    // ---- connection management

    /** Starts connecting; does nothing unless the client was not started yet. */
    public void start() {
        synchronized (lock) {
            if (state == State.IDLE) {
                startAttemptLocked();
            }
        }
    }

    /**
     * Connects now when the client waits for a retry or gave up (the failure count starts again); does nothing while
     * connecting or connected, and after {@link #close()}. Call it when the receivers page becomes visible.
     */
    public void retry() {
        synchronized (lock) {
            if (state == State.IDLE || state == State.WAITING_RETRY || state == State.GAVE_UP) {
                failures = 0;
                startAttemptLocked();
            }
        }
    }

    /**
     * Replaces the connection by a new one now, also from CONNECTED: use it when the connection looks alive but is
     * not (the calls end in timeouts). Waiting calls fail as CLOSED, the failure count starts again. Does nothing
     * while a connection is being opened, and after {@link #close()}.
     */
    public void reconnect() {
        SnapcastTransport.Connection old = null;
        List<PendingCall> toFail = Collections.emptyList();
        boolean dropped = false;
        synchronized (lock) {
            if (state == State.CLOSED || state == State.CONNECTING) {
                return;
            }
            if (state == State.CONNECTED) {
                dropped = true;
                old = connection;
                connection = null;
                toFail = new ArrayList<>(pending.values());
                pending.clear();
            }
            failures = 0;
            startAttemptLocked(); // a new generation makes the old reader and the old timers stale
        }
        final SnapcastException replaced = SnapcastException.closed("connection replaced");
        for (PendingCall call : toFail) {
            call.fail(replaced);
        }
        closeQuietly(old);
        if (dropped) {
            enqueue(QueuedEvent.lifecycle(listener -> listener.onDisconnected(replaced)));
        }
    }

    private void startAttemptLocked() {
        generation++;
        state = State.CONNECTING;
        final long attempt = generation;
        Thread thread = new Thread(() -> runSession(attempt), "snapcast-rpc-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean isCurrent(long attempt) {
        synchronized (lock) {
            return attempt == generation && state != State.CLOSED;
        }
    }

    /** The body of a reader thread: one connection attempt and, if it succeeds, the read loop. */
    private void runSession(long attempt) {
        SnapcastTransport.Connection opened = null;
        SnapcastException ended;
        boolean connectedOnce = false;
        try {
            opened = transport.open(config.connectTimeoutMs);
            if (opened == null) {
                throw new IOException("no connection");
            }
            synchronized (lock) {
                if (attempt != generation || state != State.CONNECTING) {
                    return; // closed or replaced while connecting: the finally block closes the connection
                }
                connection = opened;
                state = State.CONNECTED;
                connectedAt = clock.nowMillis();
                lock.notifyAll();
            }
            connectedOnce = true;
            lastReceivedNanos = System.nanoTime();
            enqueue(QueuedEvent.lifecycle(Listener::onConnected));
            opened.setReadTimeout(config.readTimeoutMs);
            readLoop(opened, attempt);
            ended = SnapcastException.closed("connection closed");
        } catch (SnapcastException e) {
            ended = e;
        } catch (IOException e) {
            ended = SnapcastException.io(e);
        } catch (Throwable t) {
            // the worker boundary: a StackOverflowError or OutOfMemoryError must not end the thread unreported
            ended = SnapcastException.internal(t);
        } finally {
            closeQuietly(opened);
        }
        sessionEnded(attempt, connectedOnce, ended);
    }

    private void readLoop(SnapcastTransport.Connection opened, long attempt) throws IOException, SnapcastException {
        InputStream counting = new FilterInputStream(opened.getInputStream()) {
            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) {
                    lastReceivedNanos = System.nanoTime();
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count > 0) {
                    lastReceivedNanos = System.nanoTime();
                }
                return count;
            }
        };
        SnapcastLineReader reader = new SnapcastLineReader(counting, config.maxLineBytes, config.lineDeadlineMs, clock);
        int badLines = 0;
        while (true) {
            byte[] line;
            try {
                line = reader.readLine();
            } catch (SocketTimeoutException idle) {
                if (!isCurrent(attempt)) {
                    return;
                }
                continue;
            }
            if (line == null) {
                return;
            }
            if (!isCurrent(attempt)) {
                return;
            }
            if (line.length == 0) {
                continue;
            }
            if (handleLine(new String(line, StandardCharsets.UTF_8), line.length)) {
                badLines = 0;
            } else if (++badLines > config.maxBadLines) {
                throw SnapcastException.malformed("too many unreadable lines in a row");
            }
        }
    }

    /** @return false when the line was unreadable */
    private boolean handleLine(String text, int lineBytes) {
        List<SnapcastMessage> messages;
        try {
            messages = SnapcastMessage.parseLine(text, config.maxBatch);
        } catch (SnapcastException unreadable) {
            markLost();
            return false;
        }
        // a batch shares the size of its line between its notifications
        final int weight = Math.max(1, lineBytes / messages.size());
        for (SnapcastMessage message : messages) {
            switch (message.getType()) {
                case RESPONSE:
                    route(message);
                    break;
                case NOTIFICATION:
                    final String method = message.getMethod();
                    if (!SnapcastModelUpdater.isKnown(method)) {
                        markLost(); // nobody can use its params: keep none, only ask for a re-read
                        break;
                    }
                    final JSONObject params = message.getParams();
                    enqueue(QueuedEvent.notification(listener -> listener.onNotification(method, params), weight));
                    break;
                default:
                    break; // a request of the server: this client answers none
            }
        }
        return true;
    }

    private void route(SnapcastMessage response) {
        if (response.getId() == null) {
            return;
        }
        PendingCall call;
        synchronized (lock) {
            call = pending.remove(response.getId());
        }
        if (call == null) {
            return; // late (the call timed out) or never ours
        }
        if (response.getError() != null) {
            call.fail(response.getError());
        } else if (call.handler == null) {
            call.complete(response.getResult());
        } else {
            // the handler runs between the notifications, in the order of the lines
            final PendingCall ordered = call;
            final JSONObject result = response.getResult();
            if (!enqueue(QueuedEvent.ordered(ordered, () -> ordered.runHandler(result)))) {
                ordered.fail(SnapcastException.closed("closed"));
            }
        }
    }

    private void sessionEnded(long attempt, boolean connectedOnce, final SnapcastException cause) {
        List<PendingCall> toFail;
        boolean giveUp;
        long delay = 0;
        final long token;
        synchronized (lock) {
            if (attempt != generation || state == State.CLOSED) {
                return;
            }
            connection = null;
            if (connectedOnce && clock.nowMillis() - connectedAt >= config.stableConnectionMs) {
                failures = 0;
            }
            failures++;
            toFail = new ArrayList<>(pending.values());
            pending.clear();
            giveUp = failures >= config.maxFailures;
            if (giveUp) {
                state = State.GAVE_UP;
            } else {
                state = State.WAITING_RETRY;
                int shift = Math.min(failures - 1, 20);
                delay = Math.min(config.backoffMaxMs, config.backoffInitialMs << shift);
            }
            token = generation;
            lock.notifyAll();
        }
        for (PendingCall call : toFail) {
            call.fail(cause);
        }
        enqueue(QueuedEvent.lifecycle(listener -> listener.onDisconnected(cause)));
        if (giveUp) {
            enqueue(QueuedEvent.lifecycle(listener -> listener.onGaveUp(cause)));
        } else {
            scheduler.schedule(() -> retryTimerFired(token), delay);
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

    // ---- calls

    @Override
    public JSONObject call(String method, JSONObject params) throws SnapcastException {
        return (JSONObject) execute(method, params, new PendingCall(null));
    }

    /**
     * Calls a method and runs {@code handler} on the listener executor, in the order of the lines of the connection
     * (see {@link SnapcastCaller#callOrdered}). The call returns when the handler has run, so the model is up to date
     * when it returns. The overall deadline covers both. It must not be called from a listener: the delivery it waits
     * for would be the thread it blocks (an {@link IllegalStateException} instead of a wait until the deadline).
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T callOrdered(String method, JSONObject params, ResultHandler<T> handler) throws SnapcastException {
        if (handler == null) {
            throw new IllegalArgumentException("no handler");
        }
        if (DELIVERING.get() == this) {
            throw new IllegalStateException("callOrdered from a listener would wait for its own thread");
        }
        return (T) execute(method, params, new PendingCall(handler));
    }

    private Object execute(String method, JSONObject params, final PendingCall call) throws SnapcastException {
        if (method == null || method.isEmpty() || method.length() > SnapcastLimits.MAX_METHOD_LENGTH
                || method.indexOf('\n') >= 0 || method.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bad method name");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.callTimeoutMs);
        final SnapcastTransport.Connection target;
        final long id;
        synchronized (lock) {
            while (state == State.CONNECTING) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw SnapcastException.timeout("not connected in time");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw SnapcastException.internal(e);
                }
            }
            if (state == State.CLOSED) {
                throw SnapcastException.closed("closed");
            }
            if (state != State.CONNECTED) {
                throw SnapcastException.notConnected("not connected (" + state + ")");
            }
            if (pending.size() >= config.maxPending) {
                throw SnapcastException.limit("too many calls waiting");
            }
            id = nextId++;
            pending.put(id, call);
            target = connection;
        }
        try {
            final byte[] bytes = encode(id, method, params);
            try {
                writer.execute(() -> write(target, bytes, call));
            } catch (RejectedExecutionException closed) {
                throw SnapcastException.closed("closed");
            }
            try {
                return call.await(deadline);
            } catch (SnapcastException e) {
                if (e.getKind() == SnapcastException.Kind.TIMEOUT && call.looksStuck(lastReceivedNanos)) {
                    // the request is stuck in the send buffer (the server does not read), or it went out and nothing
                    // at all came back since (the server is gone): drop the connection, the reconnect cycle starts
                    closeQuietly(target);
                }
                throw e;
            }
        } finally {
            synchronized (lock) {
                pending.remove(id);
            }
        }
    }

    private byte[] encode(long id, String method, JSONObject params) throws SnapcastException {
        String text;
        try {
            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.put("params", params);
            }
            text = request.toString();
        } catch (JSONException e) {
            throw SnapcastException.malformed("request can not be written", e);
        }
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > config.maxRequestBytes) {
            throw SnapcastException.limit("request too big");
        }
        return bytes;
    }

    /** Runs on the writer thread. */
    private void write(SnapcastTransport.Connection target, byte[] bytes, PendingCall call) {
        call.markWriteStarted();
        try {
            target.getOutputStream().write(bytes);
            target.getOutputStream().flush();
            call.markWritten();
        } catch (IOException e) {
            call.fail(SnapcastException.io(e));
            closeQuietly(target);
        } catch (Throwable t) {
            call.fail(SnapcastException.internal(t));
            closeQuietly(target);
        }
    }

    // ---- events for the listeners

    private void markLost() {
        boolean submit;
        synchronized (eventLock) {
            if (eventsClosed) {
                return;
            }
            lost = true;
            submit = !draining;
            draining = true;
        }
        if (submit) {
            submitDrain();
        }
    }

    /** @return false when the client is closed and the event was not taken (a dropped notification counts as taken) */
    private boolean enqueue(QueuedEvent event) {
        boolean submit;
        synchronized (eventLock) {
            if (eventsClosed) {
                return false;
            }
            if (event.notification && !fitsLocked(event.weight)) {
                lost = true; // dropped: the model is told to read again
            } else {
                events.add(event);
                if (event.notification) {
                    queuedNotifications++;
                    queuedNotificationBytes += event.weight;
                }
            }
            submit = !draining;
            draining = true;
        }
        if (submit) {
            submitDrain();
        }
        return true;
    }

    /** The count and the byte budget; a notification is always taken into an empty queue. */
    private boolean fitsLocked(int weight) {
        if (queuedNotifications >= config.notificationQueueSize) {
            return false;
        }
        return queuedNotifications == 0 || queuedNotificationBytes + weight <= config.notificationQueueBytes;
    }

    private void submitDrain() {
        try {
            listenerExecutor.execute(this::drain);
        } catch (RejectedExecutionException closed) {
            synchronized (eventLock) {
                draining = false;
            }
        }
    }

    /** Runs on the listener executor: delivers the queued events in order. */
    private void drain() {
        DELIVERING.set(this);
        try {
            deliverAll();
        } finally {
            DELIVERING.remove();
        }
    }

    private void deliverAll() {
        while (true) {
            QueuedEvent event;
            boolean reportLost = false;
            List<PendingCall> abandoned = null;
            synchronized (eventLock) {
                if (eventsClosed) {
                    abandoned = takeOrderedCallsLocked();
                    draining = false;
                    event = null;
                } else {
                    event = events.poll();
                    if (event == null) {
                        if (!lost) {
                            draining = false;
                            return;
                        }
                        lost = false;
                        reportLost = true;
                    } else if (event.notification) {
                        queuedNotifications--;
                        queuedNotificationBytes -= event.weight;
                    }
                }
            }
            if (abandoned != null) {
                failAll(abandoned, SnapcastException.closed("closed"));
                return;
            }
            if (event != null && event.orderedTask != null) {
                try {
                    event.orderedTask.run();
                } catch (Throwable ignored) {
                    // runHandler ends the call itself; nothing else can be done here
                }
                continue;
            }
            for (Listener listener : listeners) {
                try {
                    if (reportLost) {
                        listener.onNotificationsLost();
                    } else {
                        event.delivery.to(listener);
                    }
                } catch (Throwable fromListener) {
                    // a faulty listener must not stop the delivery to the others; nothing to log without a logger
                }
            }
        }
    }

    /** Empties the queue; returns the calls whose result was waiting in it. */
    private List<PendingCall> takeOrderedCallsLocked() {
        List<PendingCall> calls = new ArrayList<>();
        for (QueuedEvent event : events) {
            if (event.orderedCall != null) {
                calls.add(event.orderedCall);
            }
        }
        events.clear();
        queuedNotifications = 0;
        queuedNotificationBytes = 0;
        return calls;
    }

    private static void failAll(List<PendingCall> calls, SnapcastException failure) {
        for (PendingCall call : calls) {
            call.fail(failure);
        }
    }

    // ---- close

    /** Closes the connection, fails the waiting calls, stops the threads. Safe to call twice. */
    @Override
    public void close() {
        SnapcastTransport.Connection toClose;
        List<PendingCall> toFail;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            generation++;
            toClose = connection;
            connection = null;
            toFail = new ArrayList<>(pending.values());
            pending.clear();
            lock.notifyAll();
        }
        // the calls fail as "closed" first: a write that fails on the closing socket must not be the reason they see
        SnapcastException closed = SnapcastException.closed("closed");
        for (PendingCall call : toFail) {
            call.fail(closed);
        }
        closeQuietly(toClose);
        List<PendingCall> undelivered;
        synchronized (eventLock) {
            eventsClosed = true;
            undelivered = takeOrderedCallsLocked();
        }
        failAll(undelivered, closed);
        writer.shutdownNow();
        if (ownScheduler != null) {
            ownScheduler.shutdownNow();
        }
        if (ownListenerExecutor != null) {
            ownListenerExecutor.shutdownNow();
        }
    }

    private static void closeQuietly(SnapcastTransport.Connection toClose) {
        if (toClose != null) {
            try {
                toClose.close();
            } catch (RuntimeException ignored) {
                // closing
            }
        }
    }
}
