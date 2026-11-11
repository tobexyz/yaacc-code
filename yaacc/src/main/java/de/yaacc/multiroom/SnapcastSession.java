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
package de.yaacc.multiroom;

import org.json.JSONObject;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastCaller;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastModelUpdater;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * Everything that belongs to one snapserver: the control connection ({@link SnapcastRpc}), the model that follows the
 * server's notifications ({@link SnapcastModelUpdater}) and the control calls ({@link SnapcastOperations}). It
 * exposes the current immutable {@link SnapcastServerStatus}, tells its listeners when the status or the connection
 * changed, and releases every thread on {@link #close()}. Pure Java (the transport is injected).
 *
 * <p><b>How it follows the usage notes of the control library.</b>
 * <ul>
 *   <li>The RPC gets a listener executor of its own that does nothing but the deliveries; the status reads run on a
 *   separate worker, so a read that waits for its delivery can never wait for a task queued behind it.</li>
 *   <li>The re-read requester of the updater runs on the listener thread; here it only hands the read to the worker.</li>
 *   <li>A fresh status is read when the connection comes up ({@code onConnected}), on the worker. A re-read that
 *   fails (a timeout, a connection problem; not an error the server answered) is requested again through the
 *   updater, which keeps its limit of one per second and ten per minute; a held-back request is delivered by a
 *   one-second tick that runs only while the connection is up. After {@value #MAX_READ_RETRIES} failures in a row it
 *   gives up until the next doubt or connection.</li>
 *   <li>{@link #retry()} (the receivers page became visible) is the only caller of {@code SnapcastRpc.retry()}; it
 *   resets the failure count of the reconnects, so it is never called by a loop here.</li>
 *   <li>Every change of our own through {@link #getOperations()} goes through {@code callOrdered}, so the model
 *   follows it in line order; the listeners are told after it.</li>
 * </ul>
 *
 * <p>Threads: the reader and the writer of the RPC, one listener thread, one worker and (without an injected
 * scheduler) one timer thread. Names and ids in the status are display text of the server, never an identity.
 */
public final class SnapcastSession implements SnapcastServerLink {

    /** How often in a row a failed read is requested again. */
    static final int MAX_READ_RETRIES = 5;
    private static final long TICK_MILLIS = 1_000L;

    private final SnapcastServerKey key;
    private final SnapcastRpc rpc;
    private final SnapcastModelUpdater updater;
    private final SnapcastOperations operations;
    private final ExecutorService worker;
    private final ExecutorService listenerExecutor;
    private final ScheduledExecutorService ownTimer;
    private final RetryScheduler scheduler;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean readQueued = new AtomicBoolean();
    private final SnapcastTickGate tickGate;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger readFailures = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean online;
    private volatile boolean gaveUp;
    private volatile SnapcastException.Kind lastErrorKind;
    private volatile int lastErrorCode;

    /**
     * @param transport opens the control connection (for the app: a {@code SocketTransport} to an address that was
     *                  resolved with a time limit)
     * @param config    the limits of the connection
     * @param scheduler runs the reconnect timers and the tick; null: an own timer thread
     */
    public SnapcastSession(SnapcastServerKey key, SnapcastTransport transport, SnapcastRpc.Config config,
                           MonotonicClock clock, RetryScheduler scheduler) {
        this.key = key;
        this.worker = Executors.newSingleThreadExecutor(daemon("snapcast-session-worker"));
        this.listenerExecutor = Executors.newSingleThreadExecutor(daemon("snapcast-session-listener"));
        if (scheduler == null) {
            final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("snapcast-session-timer"));
            this.ownTimer = timer;
            this.scheduler = (task, delayMillis) -> {
                try {
                    timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException closedMeanwhile) {
                    // closed: no more timers
                }
            };
        } else {
            this.ownTimer = null;
            this.scheduler = scheduler;
        }
        this.rpc = new SnapcastRpc(transport, config, this.scheduler, listenerExecutor, clock);
        this.updater = new SnapcastModelUpdater(clock, this::submitRead);
        this.operations = new SnapcastOperations(new FiringCaller(rpc), updater);
        this.tickGate = new SnapcastTickGate(this::scheduleTick);
        rpc.addListener(new SnapcastRpc.Listener() {
            @Override
            public void onConnected() {
                if (closed) {
                    return;
                }
                online = true;
                gaveUp = false;
                lastErrorKind = null;
                readFailures.set(0);
                submitRead(); // a fresh status: the old model may be behind
                startTick();
                fireChanged();
            }

            @Override
            public void onNotification(String method, JSONObject params) {
                if (closed) {
                    return;
                }
                SnapcastServerStatus before = updater.getStatus();
                SnapcastServerStatus after = updater.apply(method, params);
                if (!Objects.equals(before, after)) {
                    fireChanged();
                }
            }

            @Override
            public void onNotificationsLost() {
                if (!closed) {
                    updater.onNotificationsLost();
                }
            }

            @Override
            public void onDisconnected(SnapcastException cause) {
                if (closed) {
                    return;
                }
                online = false;
                record(cause);
                fireChanged();
            }

            @Override
            public void onGaveUp(SnapcastException cause) {
                if (closed) {
                    return;
                }
                online = false;
                gaveUp = true;
                record(cause);
                fireChanged();
            }
        });
    }

    private static ThreadFactory daemon(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void record(SnapcastException cause) {
        lastErrorKind = cause == null ? null : cause.getKind();
        lastErrorCode = cause == null ? 0 : cause.getCode();
    }

    /** Fires the listeners after an ordered call of ours, which may have changed the model. */
    private final class FiringCaller implements SnapcastCaller {
        private final SnapcastRpc delegate;

        FiringCaller(SnapcastRpc delegate) {
            this.delegate = delegate;
        }

        @Override
        public JSONObject call(String method, JSONObject params) throws SnapcastException {
            return delegate.call(method, params);
        }

        @Override
        public <T> T callOrdered(String method, JSONObject params, ResultHandler<T> handler) throws SnapcastException {
            T result = delegate.callOrdered(method, params, handler);
            fireChanged();
            return result;
        }
    }

    @Override
    public SnapcastServerKey getKey() {
        return key;
    }

    /** The calls that change the server. They block: make them on a worker, never on the main thread. */
    @Override
    public SnapcastOperations getOperations() {
        return operations;
    }

    @Override
    public SnapcastServerStatus getStatus() {
        return updater.getStatus();
    }

    @Override
    public boolean isOnline() {
        return online && !closed;
    }

    /** True when the reconnects stopped after too many failures; {@link #retry()} starts them again. */
    public boolean hasGivenUp() {
        return gaveUp;
    }

    /** The kind of the last connection or read failure; null when the last read or connection succeeded. */
    public SnapcastException.Kind getLastErrorKind() {
        return lastErrorKind;
    }

    /** The JSON-RPC code of the last failure (401 or 403 when the server wants authorization), else 0. */
    public int getLastErrorCode() {
        return lastErrorCode;
    }

    /**
     * The address of the control connection, or null without one. The feed of a stream connects to this address
     * and to no other. It is an address of the local network: never log it.
     */
    @Override
    public InetAddress getPeerAddress() {
        return rpc.getPeerAddress();
    }

    @Override
    public long getConnectionGeneration() {
        return rpc.getConnectionGeneration();
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void start() {
        if (!closed && started.compareAndSet(false, true)) {
            rpc.start();
        }
    }

    @Override
    public void retry() {
        if (closed) {
            return;
        }
        if (!started.get()) {
            start();
            return;
        }
        gaveUp = false;
        rpc.retry();
    }

    /**
     * Releases the connection and every thread. The listeners are told <b>once</b>, before they are dropped, that the link
     * is offline ({@link #isOnline()} is false in the call): whoever depends on the link (a feed that writes to the server
     * of this link) learns that it is gone and stops (security review W1: removing a server must end a running feed). The
     * call is made on the calling thread and holds no lock of this class; a listener that fails is skipped.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        online = false;
        fireChanged();
        listeners.clear();
        rpc.close();
        worker.shutdownNow();
        listenerExecutor.shutdownNow();
        if (ownTimer != null) {
            ownTimer.shutdownNow();
        }
    }

    // ---- reading the status

    /** The re-read requester of the updater and the read after a connect: queue one read on the worker. */
    private void submitRead() {
        if (closed || !readQueued.compareAndSet(false, true)) {
            return; // one that has not started yet will see the newest state
        }
        try {
            worker.execute(this::readStatus);
        } catch (RejectedExecutionException closedMeanwhile) {
            readQueued.set(false);
        }
    }

    private void readStatus() {
        readQueued.set(false);
        if (closed) {
            return;
        }
        try {
            operations.getStatus(); // sets the model in line order and fires the listeners
            readFailures.set(0);
            lastErrorKind = null;
            lastErrorCode = 0;
        } catch (SnapcastException failure) {
            if (closed) {
                return;
            }
            lastErrorKind = failure.getKind();
            lastErrorCode = failure.getCode();
            boolean transientFailure = failure.getKind() == SnapcastException.Kind.TIMEOUT
                    || failure.getKind() == SnapcastException.Kind.IO
                    || failure.getKind() == SnapcastException.Kind.MALFORMED;
            if (transientFailure && rpc.getState() == SnapcastRpc.State.CONNECTED
                    && readFailures.incrementAndGet() <= MAX_READ_RETRIES) {
                updater.requestReRead(); // within the rate limit; a held-back request comes with the tick
            }
            fireChanged();
        } catch (RuntimeException unexpected) {
            if (!closed) {
                lastErrorKind = SnapcastException.Kind.IO;
                fireChanged();
            }
        }
    }

    // ---- the tick that delivers held-back re-reads

    private void startTick() {
        tickGate.start();
    }

    private void scheduleTick() {
        scheduler.schedule(() -> tickGate.run(() -> !closed && online, updater::poll), TICK_MILLIS);
    }

    private void fireChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onLinkChanged(this);
            } catch (RuntimeException ignored) {
                // a listener's failure must not end the thread that delivers to the others
            }
        }
    }
}
