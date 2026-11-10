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
package de.yaacc.multiroom.discovery;

import java.util.ArrayDeque;
import java.util.Iterator;

import de.yaacc.multiroom.control.MonotonicClock;

/**
 * The services that wait to be resolved. Android's {@code NsdManager} resolves one service at a time (a second
 * request fails with "already active"), a resolution can stay unanswered, and a hostile network can announce any
 * number of names. So: a bounded first-in-first-out queue, one active entry, the same name queued once, and an
 * active entry that was not answered within the timeout is given up. Pure; the thin Android wrapper only asks it what
 * to do.
 *
 * @param <T> what the wrapper needs to start a resolution (the service info object)
 */
public final class SnapcastResolveQueue<T> {

    /** A service that waits for, or is in, resolution. */
    public static final class Pending<T> {
        private final String name;
        private final T item;

        Pending(String name, T item) {
            this.name = name;
            this.item = item;
        }

        public String getName() {
            return name;
        }

        public T getItem() {
            return item;
        }
    }

    private final MonotonicClock clock;
    private final long timeoutMillis;
    private final int capacity;
    private final ArrayDeque<Pending<T>> waiting = new ArrayDeque<>();
    private Pending<T> active;
    private long activeSince;

    public SnapcastResolveQueue(MonotonicClock clock, long timeoutMillis, int capacity) {
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
        this.capacity = capacity;
    }

    /**
     * Queues a service. A name that is queued already gets the newer item; a name that is being resolved is not
     * queued again.
     *
     * @return false if the service was dropped because the queue is full or the name is empty
     */
    public synchronized boolean offer(String name, T item) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (active != null && active.name.equals(name)) {
            return true;
        }
        for (Iterator<Pending<T>> it = waiting.iterator(); it.hasNext(); ) {
            if (it.next().name.equals(name)) {
                it.remove();
                waiting.addLast(new Pending<>(name, item));
                return true;
            }
        }
        if (waiting.size() >= capacity) {
            return false;
        }
        waiting.addLast(new Pending<>(name, item));
        return true;
    }

    /**
     * The service to resolve now, or null when one is being resolved (and has time left) or none waits. The caller
     * starts the resolution and calls {@link #done} when it ends; call this again after {@link #done} and now and
     * then, so that an unanswered resolution is given up.
     */
    public synchronized Pending<T> next() {
        if (active != null && clock.nowMillis() - activeSince >= timeoutMillis) {
            active = null; // no answer: give it up
        }
        if (active != null || waiting.isEmpty()) {
            return null;
        }
        active = waiting.pollFirst();
        activeSince = clock.nowMillis();
        return active;
    }

    /** The resolution of this name ended (answer or failure); an answer for any other name is ignored. */
    public synchronized void done(String name) {
        if (active != null && active.name.equals(name)) {
            active = null;
        }
    }

    /** Forgets everything (discovery stopped). */
    public synchronized void clear() {
        waiting.clear();
        active = null;
    }

    public synchronized int size() {
        return waiting.size() + (active == null ? 0 : 1);
    }
}
