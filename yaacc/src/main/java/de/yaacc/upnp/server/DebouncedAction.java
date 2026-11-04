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
package de.yaacc.upnp.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs an action once for any number of requests that arrive within a delay: the first request starts the delay, requests
 * during it are absorbed, then the action runs once on a worker thread. A request after the action started starts a new round.
 */
public final class DebouncedAction {

    private final Runnable action;
    private final long delayMillis;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean pending = new AtomicBoolean();

    public DebouncedAction(Runnable action, long delayMillis, String threadName) {
        this.action = action;
        this.delayMillis = delayMillis;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Asks for the action; it runs once after the delay, however often this is called meanwhile. Never blocks. */
    public void request() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(this::run, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) { // executor shut down
            pending.set(false);
        }
    }

    /** Ends the worker thread; a request that is waiting is dropped. */
    public void shutdown() {
        executor.shutdownNow();
    }

    private void run() {
        pending.set(false); // a request from now on starts a new round
        action.run();
    }
}
