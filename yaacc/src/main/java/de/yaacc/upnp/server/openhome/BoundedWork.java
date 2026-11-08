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
package de.yaacc.upnp.server.openhome;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the tasks that wait for the worker of the OpenHome renderer. Every host of the network may send actions, and the worker
 * does them one after the other: when far more wait than a real control point ever causes, further tasks are dropped instead of
 * queued, so a flood can not fill the memory of the phone or keep the player busy for minutes.
 */
final class BoundedWork {
    /** A control point sends a handful of actions per second at most; this is minutes of that. */
    static final int MAX_WAITING = 500;

    private final int max;
    private final AtomicInteger waiting = new AtomicInteger();

    BoundedWork() {
        this(MAX_WAITING);
    }

    BoundedWork(int max) {
        this.max = max;
    }

    /** @return true if the task may be queued (then {@link #done()} has to follow), false if it is to be dropped */
    boolean tryAcquire() {
        while (true) {
            int current = waiting.get();
            if (current >= max) {
                return false;
            }
            if (waiting.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void done() {
        waiting.decrementAndGet();
    }

    int waiting() {
        return waiting.get();
    }
}
