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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Caps the number of responses that are streaming from one share at the same time. A response
 * takes a {@link Lease} before it starts and gives it back when it ends; the lease can be
 * released any number of times (the HTTP library may report the end of a response through more
 * than one callback). Plain Java, thread safe.
 * <p>
 * Watchdog: a response reports its progress to the lease ({@link Lease#noteProgress}); a periodic
 * {@link #expireIdle} takes the slot away from a lease that made no progress for the idle time,
 * so a stalled or trickling reader cannot use up the cap. The response notices it at its next
 * callback ({@link Lease#isLive()} is false) and aborts; the slot is not given back a second
 * time when the response finally ends.
 */
public final class ShareConcurrencyLimiter {

    private enum State { LIVE, EXPIRED, CLOSED }

    /** One running response; {@link #release()} is idempotent. */
    public static final class Lease {
        private final ShareConcurrencyLimiter owner;
        private final String key;
        /** Guarded by the limiter's lock. */
        private State state = State.LIVE;
        private volatile boolean live = true;
        /** Millis on the limiter's clock of the last recorded progress. */
        private volatile long lastProgress;
        private long unrecordedBytes;

        private Lease(ShareConcurrencyLimiter owner, String key, long now) {
            this.owner = owner;
            this.key = key;
            this.lastProgress = now;
        }

        /** Gives the slot back (unless the watchdog did already); calling it again does nothing. */
        public void release() {
            owner.close(this);
        }

        /**
         * Reports bytes that the client accepted. Once {@link ShareLimits#MIN_PROGRESS_BYTES} have
         * accumulated since the last mark, the idle clock restarts; a smaller trickle does not.
         */
        public void noteProgress(long bytes) {
            if (bytes <= 0L) {
                return;
            }
            synchronized (this) {
                unrecordedBytes += bytes;
                if (unrecordedBytes >= ShareLimits.MIN_PROGRESS_BYTES || unrecordedBytes < 0L) {
                    unrecordedBytes = 0L;
                    lastProgress = owner.clock.getAsLong();
                }
            }
        }

        /** @return false once the lease was released or taken away by the watchdog: stop sending */
        public boolean isLive() {
            return live;
        }

        /** @return true if the watchdog took the slot away because of missing progress */
        public boolean isExpired() {
            synchronized (owner.lock) {
                return state == State.EXPIRED;
            }
        }
    }

    private final int max;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final Map<String, Integer> active = new HashMap<>();
    /** The leases that hold a slot, for the watchdog. */
    private final Set<Lease> live = new HashSet<>();

    private static final class Holder {
        static final ShareConcurrencyLimiter INSTANCE = new ShareConcurrencyLimiter();
    }

    /** The limiter of the whole process (the HTTP server's responses and the sweeper share it). */
    public static ShareConcurrencyLimiter shared() {
        return Holder.INSTANCE;
    }

    public ShareConcurrencyLimiter() {
        this(ShareLimits.MAX_CONCURRENT_RESPONSES_PER_SHARE);
    }

    /** @throws IllegalArgumentException if {@code max} is not positive */
    public ShareConcurrencyLimiter(int max) {
        this(max, () -> System.nanoTime() / 1_000_000L);
    }

    /**
     * @param clock monotonic milliseconds
     * @throws IllegalArgumentException if {@code max} is not positive
     */
    public ShareConcurrencyLimiter(int max, LongSupplier clock) {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.max = max;
        this.clock = clock;
    }

    /**
     * @param key a well-formed share key ({@link ShareRegistry#isWellFormedKey})
     * @return a lease, or null if the share already serves the maximum or the key is malformed
     */
    public Lease tryAcquire(String key) {
        if (!ShareRegistry.isWellFormedKey(key)) {
            return null;
        }
        synchronized (lock) {
            int count = active.getOrDefault(key, 0);
            if (count >= max) {
                return null;
            }
            active.put(key, count + 1);
            Lease lease = new Lease(this, key, clock.getAsLong());
            live.add(lease);
            return lease;
        }
    }

    /**
     * Takes the slot away from every lease that made no progress for more than {@code idleMillis}.
     * Call it periodically (the share sweeper does, every minute).
     *
     * @return the number of leases that were expired
     * @throws IllegalArgumentException if {@code idleMillis} is not positive
     */
    public int expireIdle(long idleMillis) {
        if (idleMillis <= 0L) {
            throw new IllegalArgumentException("idleMillis must be positive");
        }
        int expired = 0;
        synchronized (lock) {
            long now = clock.getAsLong();
            for (Iterator<Lease> it = live.iterator(); it.hasNext(); ) {
                Lease lease = it.next();
                if (now - lease.lastProgress > idleMillis) {
                    it.remove();
                    lease.state = State.EXPIRED;
                    lease.live = false;
                    give(lease.key);
                    expired++;
                }
            }
        }
        return expired;
    }

    /** @return the number of responses currently holding a lease for the key */
    public int activeCount(String key) {
        if (key == null) {
            return 0;
        }
        synchronized (lock) {
            return active.getOrDefault(key, 0);
        }
    }

    /** @return the number of keys with at least one lease (bookkeeping size, for tests) */
    public int trackedKeys() {
        synchronized (lock) {
            return active.size();
        }
    }

    private void close(Lease lease) {
        synchronized (lock) {
            if (lease.state == State.LIVE) {
                live.remove(lease);
                give(lease.key);
            }
            lease.state = State.CLOSED;
            lease.live = false;
        }
    }

    /** Caller holds the lock. */
    private void give(String key) {
        Integer count = active.get(key);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            active.remove(key);
        } else {
            active.put(key, count - 1);
        }
    }
}
