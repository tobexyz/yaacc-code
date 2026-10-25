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
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Lets at most ONE call per content provider be in flight, so that one hostile or hanging
 * provider can never occupy more than one thread of the shared pool, and remembers a provider
 * whose call outlived the caller's wait ("stuck") so that further calls to it are dropped without
 * taking a thread. Plain Java.
 * <ul>
 * <li>{@link #tryAcquire} refuses a provider that has a call in flight or a live stuck marker, and
 * refuses everybody when {@code maxTracked} providers are already in flight or stuck (the map is
 * bounded by that number, whatever keys arrive).</li>
 * <li>{@link #release} (the call returned, in whatever way) frees the provider at once, also when
 * it was stuck: the thread is free again, so there is nothing left to protect.</li>
 * <li>{@link #markStuck} (the caller stopped waiting, the call still runs) keeps the provider
 * blocked for {@code stuckMillis}; after that it may be tried again. A marker is not a ban for a
 * provider that answers, only a bound on how long a provider that never returns keeps a place.
 * Residual: a call that never returns still pins its thread physically; the pool bound and
 * {@code maxTracked} keep the other providers working.</li>
 * </ul>
 * The key is whatever identifies the provider: the package that owns the authority where it can be
 * resolved (one app with many authorities is one provider), else the authority.
 */
public final class ProviderCallGate {

    /** Proof of one admitted call; hand it back to {@link #release} when the call returned. */
    public static final class Permit {
        private final String key;
        // guarded by the gate
        private boolean stuck;
        private long stuckSinceMillis;
        private boolean released;

        private Permit(String key) {
            this.key = key;
        }
    }

    private final LongSupplier nowMillis;
    private final int maxTracked;
    private final long stuckMillis;

    // guarded by "this"
    private final Map<String, Permit> tracked = new HashMap<>();

    /**
     * @param nowMillis  a monotonic clock
     * @param maxTracked providers in flight or stuck at once, at least 1
     * @param stuckMillis how long a stuck marker blocks its provider, positive
     */
    public ProviderCallGate(LongSupplier nowMillis, int maxTracked, long stuckMillis) {
        if (nowMillis == null || maxTracked < 1 || stuckMillis <= 0L) {
            throw new IllegalArgumentException("clock, a size and a positive duration are required");
        }
        this.nowMillis = nowMillis;
        this.maxTracked = maxTracked;
        this.stuckMillis = stuckMillis;
    }

    /** The production gate with the numbers of {@link ShareLimits}. */
    public static ProviderCallGate createDefault() {
        return new ProviderCallGate(() -> System.nanoTime() / 1_000_000L, ShareLimits.PROVIDER_MAX_TRACKED,
                ShareLimits.PROVIDER_STUCK_MILLIS);
    }

    /** @return a permit, or null if this provider has a call in flight or is stuck, or the gate is full */
    public synchronized Permit tryAcquire(String key) {
        if (key == null) {
            return null;
        }
        purgeExpired(nowMillis.getAsLong());
        if (tracked.containsKey(key) || tracked.size() >= maxTracked) {
            return null;
        }
        Permit permit = new Permit(key);
        tracked.put(key, permit);
        return permit;
    }

    /** The call returned (result, failure or refusal by the pool). Safe to call more than once. */
    public synchronized void release(Permit permit) {
        if (permit == null) {
            return;
        }
        permit.released = true;
        // not the entry of a newer call of the same provider (this one may have expired meanwhile)
        if (tracked.get(permit.key) == permit) {
            tracked.remove(permit.key);
        }
    }

    /** The caller stopped waiting but the call still runs. No effect if it already returned. */
    public synchronized void markStuck(Permit permit) {
        if (permit == null || permit.released || tracked.get(permit.key) != permit) {
            return;
        }
        permit.stuck = true;
        permit.stuckSinceMillis = nowMillis.getAsLong();
    }

    /** @return how many providers are in flight or stuck (for tests) */
    synchronized int trackedCount() {
        purgeExpired(nowMillis.getAsLong());
        return tracked.size();
    }

    private void purgeExpired(long now) {
        for (Iterator<Permit> it = tracked.values().iterator(); it.hasNext(); ) {
            Permit permit = it.next();
            if (permit.stuck && now - permit.stuckSinceMillis >= stuckMillis) {
                it.remove();
            }
        }
    }
}
