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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs a call that may block for ever (a read on a provider's pipe) on its OWN daemon thread and
 * waits for it a bounded time. Plain Java.
 * <ul>
 * <li>When the time is up the caller gets control back at once and {@code onAbandon} runs on the
 * CALLER's thread: that is where the blocked resource is closed from another thread (closing a
 * file descriptor wakes a blocked libcore read with an exception).</li>
 * <li>A worker that stays stuck anyway is abandoned, not waited for: it is counted, and beyond
 * {@code maxAbandoned} stuck workers a new call is refused instead of starting yet another thread.
 * A worker that wakes up later finds out ({@code abandoned} is passed to the work), and a result
 * it still produces goes to {@code disposeLate}, never to anybody else.</li>
 * <li>Unlike {@link BoundedCall} there is no shared pool: an abandoned worker does not take a
 * place away from the next call.</li>
 * </ul>
 */
public final class AbandonableCall {

    /** The work. {@code abandoned} turns true once the caller gave up; poll it between steps. */
    public interface Work<T> {
        T run(BooleanSupplier abandoned) throws Exception;
    }

    /** The state shared between the caller and one worker. */
    private static final class Run<T> {
        private final AtomicInteger abandonedAlive;
        private T value;
        private Exception failure;
        private boolean done;
        private boolean abandoned;
        private boolean ended;
        private boolean counted;

        Run(AtomicInteger abandonedAlive) {
            this.abandonedAlive = abandonedAlive;
        }

        /** @return false if the caller has given up: the result is the worker's to dispose of */
        synchronized boolean finish(T result, Exception error) {
            if (abandoned) {
                return false;
            }
            value = result;
            failure = error;
            done = true;
            notifyAll();
            return true;
        }

        synchronized boolean isAbandoned() {
            return abandoned;
        }

        /** The caller stops waiting. @return false if the worker finished first (take its result) */
        synchronized boolean abandon() {
            if (done) {
                return false;
            }
            abandoned = true;
            if (!ended) {
                counted = true;
                abandonedAlive.incrementAndGet();
            }
            return true;
        }

        /** The worker thread is over. */
        synchronized void end() {
            ended = true;
            if (counted) {
                counted = false;
                abandonedAlive.decrementAndGet();
            }
        }

        /** @return true if the worker finished within the time */
        synchronized boolean await(long nanos) throws InterruptedException {
            long deadline = System.nanoTime() + nanos;
            while (!done) {
                long left = deadline - System.nanoTime();
                if (left <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }

    private final int maxAbandoned;
    private final String threadName;
    private final AtomicInteger abandonedAlive = new AtomicInteger();

    /**
     * @param maxAbandoned how many abandoned workers may be alive at the same time, at least 1
     * @param threadName   the name of the worker threads
     */
    public AbandonableCall(int maxAbandoned, String threadName) {
        if (maxAbandoned < 1) {
            throw new IllegalArgumentException("at least one");
        }
        this.maxAbandoned = maxAbandoned;
        this.threadName = threadName;
    }

    /**
     * Runs {@code work} and waits at most {@code timeoutMillis}.
     *
     * @param onAbandon   runs on the calling thread when the time is up (or the caller is
     *                    interrupted), after the worker was marked abandoned; may be null
     * @param disposeLate called, on the worker's thread, with a result that arrives after the
     *                    caller gave up; may be null
     * @return the result of the work (null if the work returned null)
     * @throws TimeoutException           the time was up, or the waiting thread was interrupted (its
     *                                    interrupt flag is kept); the worker is abandoned
     * @throws RejectedExecutionException too many abandoned workers are still alive; nothing was started
     * @throws Exception                  whatever the work threw
     */
    public <T> T call(Work<T> work, long timeoutMillis, Runnable onAbandon, Consumer<T> disposeLate)
            throws Exception {
        if (abandonedAlive.get() >= maxAbandoned) {
            throw new RejectedExecutionException("too many stuck workers");
        }
        final Run<T> run = new Run<>(abandonedAlive);
        Thread worker = new Thread(() -> {
            try {
                T result = null;
                Exception error = null;
                try {
                    result = work.run(run::isAbandoned);
                } catch (Exception e) {
                    error = e;
                } catch (Error e) {
                    // the worker dies; the caller must not wait for it
                    run.finish(null, new IllegalStateException(e.getClass().getSimpleName()));
                    throw e;
                }
                if (!run.finish(result, error) && result != null && disposeLate != null) {
                    try {
                        disposeLate.accept(result);
                    } catch (RuntimeException ignored) {
                        // nothing sensible to do, nothing to log
                    }
                }
            } finally {
                run.end();
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();

        boolean finished;
        boolean interrupted = false;
        try {
            finished = run.await(TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        } catch (InterruptedException e) {
            interrupted = true;
            finished = false;
        }
        if (!finished && run.abandon()) {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (onAbandon != null) {
                try {
                    onAbandon.run();
                } catch (RuntimeException ignored) {
                    // nothing sensible to do, nothing to log
                }
            }
            throw new TimeoutException(interrupted ? "interrupted" : "timed out");
        }
        // the worker finished (possibly at the very last moment): its outcome is the caller's
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (run) {
            if (run.failure != null) {
                throw run.failure;
            }
            return run.value;
        }
    }

    /** @return abandoned workers that are still alive (for tests and diagnostics) */
    public int abandonedAliveCount() {
        return abandonedAlive.get();
    }
}
