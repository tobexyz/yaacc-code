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

/**
 * Lets at most ONE call per content provider be in flight, so that one hostile or hanging
 * provider can never occupy more than one thread of the shared pool, however often it is asked.
 * Plain Java.
 * <p>
 * A permit lives exactly as long as its call: {@link #tryAcquire} refuses a provider whose
 * earlier call has not returned (it may be running, or hung in a Binder call that cannot be
 * interrupted, with the caller long gone), and {@link #release}, which the pooled task runs in a
 * {@code finally} the moment the call returns, frees it. There is deliberately NO expiry: a
 * thread that is still pinned must stay accounted for, or the same provider could pin one more
 * thread per interval until the whole pool is gone. The gate also refuses everybody when
 * {@code maxTracked} providers already have a call outstanding, so the map is bounded by that
 * number whatever keys arrive, and so are the pool threads inside provider calls.
 * <p>
 * Residual: a call that never returns keeps its thread and its place until the process ends;
 * {@code maxTracked} such providers (distinct packages) use up all places.
 * <p>
 * The key is whatever identifies the provider: the package that owns the authority where it can be
 * resolved (one app with many authorities is one provider), else one fixed key shared by every
 * provider that cannot be resolved.
 */
public final class ProviderCallGate {

    /** Proof of one admitted call; hand it back to {@link #release} when the call returned. */
    public static final class Permit {
        private final String key;

        private Permit(String key) {
            this.key = key;
        }
    }

    private final int maxTracked;

    // guarded by "this"
    private final Map<String, Permit> tracked = new HashMap<>();

    /** @param maxTracked providers with a call outstanding at once, at least 1 */
    public ProviderCallGate(int maxTracked) {
        if (maxTracked < 1) {
            throw new IllegalArgumentException("at least one place");
        }
        this.maxTracked = maxTracked;
    }

    /** The production gate with the number of {@link ShareLimits}. */
    public static ProviderCallGate createDefault() {
        return new ProviderCallGate(ShareLimits.PROVIDER_MAX_TRACKED);
    }

    /** @return a permit, or null if this provider has a call outstanding or the gate is full */
    public synchronized Permit tryAcquire(String key) {
        if (key == null || tracked.containsKey(key) || tracked.size() >= maxTracked) {
            return null;
        }
        Permit permit = new Permit(key);
        tracked.put(key, permit);
        return permit;
    }

    /** The call returned (result, failure, refusal by the pool or never started). Safe to call more than once. */
    public synchronized void release(Permit permit) {
        if (permit != null && tracked.get(permit.key) == permit) {
            tracked.remove(permit.key);
        }
    }

    /** @return how many providers have a call outstanding (for tests) */
    synchronized int trackedCount() {
        return tracked.size();
    }
}
