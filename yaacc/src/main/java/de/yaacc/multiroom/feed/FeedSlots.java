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

import java.util.HashMap;
import java.util.Map;

/**
 * The slots of all servers, by an opaque key (the text of the server key; it is only compared, never shown or logged).
 * At most one holder per key; the number of keys is bounded by the number of servers (16) because a key is dropped
 * when its slot is given back. Thread safe.
 */
public final class FeedSlots {

    private final Object lock = new Object();
    private final Map<String, Object> holders = new HashMap<>();

    /** A new slot for a key; each holder (a controller) gets its own. */
    public FeedSlot slotFor(final String key) {
        if (key == null) {
            throw new IllegalArgumentException("no key");
        }
        return new FeedSlot() {
            @Override
            public boolean tryAcquire(long waitMillis) {
                return FeedSlots.this.acquire(key, this, waitMillis);
            }

            @Override
            public void release() {
                FeedSlots.this.release(key, this);
            }
        };
    }

    private boolean acquire(String key, Object holder, long waitMillis) {
        long deadline = System.nanoTime() + Math.max(0, waitMillis) * 1_000_000L;
        synchronized (lock) {
            while (true) {
                Object current = holders.get(key);
                if (current == null || current == holder) {
                    holders.put(key, holder);
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    long millis = remaining / 1_000_000L;
                    lock.wait(Math.max(1, millis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private void release(String key, Object holder) {
        synchronized (lock) {
            if (holders.get(key) == holder) {
                holders.remove(key);
                lock.notifyAll();
            }
        }
    }

    /** How many keys are held now (for tests). */
    int heldCount() {
        synchronized (lock) {
            return holders.size();
        }
    }
}
