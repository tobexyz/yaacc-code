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
package de.yaacc.util;

/**
 * Tells background work (the duration preload) that a Browse is being answered, so the background work gives way: a
 * Browse of a big SAF folder needs the processor and the system's Binder threads for itself, and the preload asks the
 * same document provider for every file. Nothing is blocked for good: a waiter gives up after a limit, and a Browse
 * never waits for the background work.
 * <p>
 * Usage: the Browse calls {@link #enter()} and, in a finally block, {@link #exit()}; the background work calls
 * {@link #awaitIdle(long)} between two files. No Android classes.
 */
public final class SafBrowseGate {

    private final Object monitor = new Object();
    private int active;

    /** A Browse starts. */
    public void enter() {
        synchronized (monitor) {
            active++;
        }
    }

    /** A Browse ended (also when it failed). */
    public void exit() {
        synchronized (monitor) {
            if (active > 0) {
                active--;
            }
            if (active == 0) {
                monitor.notifyAll();
            }
        }
    }

    public boolean isBusy() {
        synchronized (monitor) {
            return active > 0;
        }
    }

    /**
     * Returns at once when no Browse is active, else waits until the last one ended, at most {@code maxMillis}.
     *
     * @return the milliseconds that were waited (0 if nothing was active); an interrupt ends the wait and is kept
     */
    public long awaitIdle(long maxMillis) {
        synchronized (monitor) {
            if (active == 0) {
                return 0;
            }
            long start = System.nanoTime();
            long deadline = start + maxMillis * 1_000_000L;
            try {
                while (active > 0) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        break;
                    }
                    long millis = remainingNanos / 1_000_000L;
                    monitor.wait(Math.max(1L, millis));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return (System.nanoTime() - start) / 1_000_000L;
        }
    }
}
