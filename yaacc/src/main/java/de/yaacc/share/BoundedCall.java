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
package de.yaacc.share;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Runs a call that may block for ever (a content provider of another app) on a small pool of
 * daemon threads and waits for it only a bounded time. Plain Java.
 * <ul>
 * <li>At most {@code maxThreads} calls run at once; a call that finds the pool busy is refused
 * at once (returns null). A provider that hangs can therefore tie up at most that many threads,
 * however many intents arrive.</li>
 * <li>The caller waits at most {@code timeoutMillis}. A call that returns after the caller gave
 * up has its result handed to {@code disposeLate} (to close a descriptor), never to anybody
 * else.</li>
 * <li>Every failure (timeout, refusal, exception of the call, interrupt) is a null result; the
 * exception is not kept or logged, it may carry the provider's text.</li>
 * <li>With a {@link ProviderCallGate} and a key per provider ({@link #call(String, Callable, long,
 * Consumer)}), one provider has at most one call in flight: a call whose provider still has an
 * earlier call running or stuck is dropped at once without taking a thread, so one hostile
 * provider can pin one thread, not the pool.</li>
 * </ul>
 */
public final class BoundedCall {

    /** Where the result of the call is handed over, exactly once, to one side. */
    private static final class Handoff<T> {
        private T value;
        private boolean abandoned;

        /** @return false if the caller has given up: the result is the producer's to dispose of */
        synchronized boolean put(T result) {
            if (abandoned) {
                return false;
            }
            value = result;
            return true;
        }

        /** The caller stops waiting: takes the result if it arrived, and refuses later ones. */
        synchronized T takeAndAbandon() {
            abandoned = true;
            T result = value;
            value = null;
            return result;
        }
    }

    private final ThreadPoolExecutor pool;
    private final ProviderCallGate gate;

    /**
     * @param maxThreads the most calls that may run (or hang) at once, at least 1
     * @param threadName the name of the pool's threads
     */
    public BoundedCall(int maxThreads, String threadName) {
        this(maxThreads, threadName, null);
    }

    /**
     * @param maxThreads the most calls that may run (or hang) at once, at least 1
     * @param threadName the name of the pool's threads
     * @param gate       the per-provider gate used by {@link #call(String, Callable, long, Consumer)},
     *                   may be null (then a key is not checked)
     */
    public BoundedCall(int maxThreads, String threadName, ProviderCallGate gate) {
        this.gate = gate;
        if (maxThreads < 1) {
            throw new IllegalArgumentException("at least one thread");
        }
        pool = new ThreadPoolExecutor(0, maxThreads, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Runs {@code task} and waits for it at most {@code timeoutMillis}.
     *
     * @param disposeLate called with a result that arrives after the wait ended, on the pool's
     *                    thread; may be null
     * @return the result, or null if there was none in time, the pool was busy, the call failed or
     * the waiting thread was interrupted (its interrupt flag is kept)
     */
    public <T> T call(Callable<T> task, long timeoutMillis, Consumer<T> disposeLate) {
        return run(null, task, timeoutMillis, disposeLate);
    }

    /**
     * Same, for one provider: at most one call per {@code key} is in flight (see
     * {@link ProviderCallGate}). The key must be non-null when the pool has a gate.
     *
     * @param key the provider, for example its package name
     * @return as {@link #call(Callable, long, Consumer)}, and null at once, without any thread
     * or provider call, if the provider has a call in flight or is stuck
     */
    public <T> T call(String key, Callable<T> task, long timeoutMillis, Consumer<T> disposeLate) {
        if (gate == null) {
            return run(null, task, timeoutMillis, disposeLate);
        }
        ProviderCallGate.Permit permit = gate.tryAcquire(key);
        if (permit == null) {
            return null;
        }
        return run(permit, task, timeoutMillis, disposeLate);
    }

    private <T> T run(ProviderCallGate.Permit permit, Callable<T> task, long timeoutMillis, Consumer<T> disposeLate) {
        final Handoff<T> handoff = new Handoff<>();
        Future<Void> future;
        try {
            future = pool.submit(() -> {
                T result;
                try {
                    result = task.call();
                } finally {
                    // the provider has answered (or failed): its call no longer holds a place, however it ended
                    if (permit != null) {
                        gate.release(permit);
                    }
                }
                if (result != null && !handoff.put(result) && disposeLate != null) {
                    try {
                        disposeLate.accept(result);
                    } catch (RuntimeException ignored) {
                        // nothing sensible to do, nothing to log
                    }
                }
                return null;
            });
        } catch (RejectedExecutionException e) {
            if (permit != null) {
                gate.release(permit);
            }
            return null;
        }
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            giveUp(permit, future);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            giveUp(permit, future);
        } catch (ExecutionException e) {
            // the call failed: no result
        }
        return handoff.takeAndAbandon();
    }

    /** The caller stops waiting: the call may still run (a Binder call cannot be interrupted). */
    private void giveUp(ProviderCallGate.Permit permit, Future<Void> future) {
        future.cancel(true);
        if (permit != null) {
            // a no-op if the call returned meanwhile
            gate.markStuck(permit);
        }
    }
}
