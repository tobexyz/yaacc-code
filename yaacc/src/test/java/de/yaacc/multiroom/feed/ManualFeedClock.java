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
package de.yaacc.multiroom.feed;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A clock that only moves when the test says so. {@link #sleepNanos} blocks (in real time) until the test has advanced
 * the clock to the wake-up time, so a thread under test runs exactly when the virtual time lets it, with no real
 * sleeping for the pacing. {@link #advance} releases the sleepers that are due before it returns, so
 * {@link #awaitSleepers} after an advance can not see the old sleeper by mistake.
 */
final class ManualFeedClock implements FeedClock {

    private static final class Sleeper {
        final long target;
        final Thread thread = Thread.currentThread();
        boolean released;

        Sleeper(long target) {
            this.target = target;
        }
    }

    private final Object lock = new Object();
    private final List<Sleeper> sleepers = new ArrayList<>();
    private final java.util.Set<Thread> permits = new java.util.HashSet<>();
    private long now;

    ManualFeedClock() {
        this(1_000_000_000L); // not zero: a clock that starts at 0 hides "unset" bugs
    }

    ManualFeedClock(long start) {
        now = start;
    }

    @Override
    public long nanoTime() {
        synchronized (lock) {
            return now;
        }
    }

    @Override
    public void sleepNanos(long nanos) throws InterruptedException {
        synchronized (lock) {
            Sleeper sleeper = new Sleeper(now + nanos);
            if (nanos <= 0 || permits.remove(Thread.currentThread())) {
                return;
            }
            sleepers.add(sleeper);
            lock.notifyAll();
            try {
                while (!sleeper.released) {
                    lock.wait();
                }
            } finally {
                sleepers.remove(sleeper);
            }
        }
    }

    /**
     * Ends the sleep of this thread early; if it does not sleep now, its next sleep returns at once (the permit of
     * {@code LockSupport.unpark}).
     */
    @Override
    public void wake(Thread thread) {
        synchronized (lock) {
            for (Iterator<Sleeper> it = sleepers.iterator(); it.hasNext(); ) {
                Sleeper sleeper = it.next();
                if (sleeper.thread == thread) {
                    sleeper.released = true;
                    it.remove();
                    lock.notifyAll();
                    return;
                }
            }
            permits.add(thread);
        }
    }

    /** Moves the clock and releases every sleeper whose time has come. */
    void advance(long nanos) {
        synchronized (lock) {
            now += nanos;
            for (Iterator<Sleeper> it = sleepers.iterator(); it.hasNext(); ) {
                Sleeper sleeper = it.next();
                if (sleeper.target <= now) {
                    sleeper.released = true;
                    it.remove();
                }
            }
            lock.notifyAll();
        }
    }

    void advanceMillis(long millis) {
        advance(millis * 1_000_000L);
    }

    /** The wake-up time of the earliest sleeper, or -1 when nobody sleeps. */
    long nextWakeTarget() {
        synchronized (lock) {
            long best = -1;
            for (Sleeper sleeper : sleepers) {
                if (best < 0 || sleeper.target < best) {
                    best = sleeper.target;
                }
            }
            return best;
        }
    }

    /** Moves the clock to the earliest wake-up time; false if nobody sleeps. */
    boolean advanceToNextWake() {
        synchronized (lock) {
            long target = nextWakeTarget();
            if (target < 0) {
                return false;
            }
            advance(Math.max(0, target - now));
            return true;
        }
    }

    /** Waits (in real time, at most {@code timeoutMillis}) until at least {@code count} threads sleep on this clock. */
    boolean awaitSleepers(int count, long timeoutMillis) {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (sleepers.size() < count) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    int sleeperCount() {
        synchronized (lock) {
            return sleepers.size();
        }
    }
}
