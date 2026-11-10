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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The bookkeeping of the tick of a {@link SnapcastSession}: at most one chain of ticks runs, it ends when the
 * connection is not up, and a connection that comes up (again) starts a chain. A tick clears the "running" flag
 * <b>before</b> it looks at the connection, so a start that lands while the tick is ending sees a cleared flag and
 * schedules its own chain; the other order loses it (the connection would be up without a tick).
 */
final class SnapcastTickGate {

    private final AtomicBoolean running = new AtomicBoolean();
    private final Runnable schedule;

    /** @param schedule queues the next call of {@link #run} after the tick interval */
    SnapcastTickGate(Runnable schedule) {
        this.schedule = schedule;
    }

    /** The connection came up: start a chain unless one runs. */
    void start() {
        if (running.compareAndSet(false, true)) {
            schedule.run();
        }
    }

    /**
     * The scheduled task.
     *
     * @param active whether the connection is up and the session is open
     * @param work   what a tick does
     */
    void run(BooleanSupplier active, Runnable work) {
        running.set(false);
        if (!active.getAsBoolean()) {
            return; // a start() from now on finds the flag clear
        }
        if (!running.compareAndSet(false, true)) {
            return; // a start() claimed the chain meanwhile and has scheduled its own task
        }
        work.run();
        schedule.run();
    }
}
