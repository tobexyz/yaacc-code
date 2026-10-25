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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caps the number of responses that are streaming from one share at the same time. A response
 * takes a {@link Lease} before it starts and gives it back when it ends; the lease can be
 * released any number of times (the HTTP library may report the end of a response through more
 * than one callback). Plain Java, thread safe.
 */
public final class ShareConcurrencyLimiter {

    /** One running response; {@link #release()} is idempotent. */
    public static final class Lease {
        private final ShareConcurrencyLimiter owner;
        private final String key;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(ShareConcurrencyLimiter owner, String key) {
            this.owner = owner;
            this.key = key;
        }

        /** Gives the slot back; calling it again does nothing. */
        public void release() {
            if (released.compareAndSet(false, true)) {
                owner.give(key);
            }
        }
    }

    private final int max;
    private final Object lock = new Object();
    private final Map<String, Integer> active = new HashMap<>();

    public ShareConcurrencyLimiter() {
        this(ShareLimits.MAX_CONCURRENT_RESPONSES_PER_SHARE);
    }

    /** @throws IllegalArgumentException if {@code max} is not positive */
    public ShareConcurrencyLimiter(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
        this.max = max;
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
        }
        return new Lease(this, key);
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

    private void give(String key) {
        synchronized (lock) {
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
}
