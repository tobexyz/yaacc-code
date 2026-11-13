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
package de.yaacc.multiroom.discovery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which failed resolutions are tried again (task 11.6). {@code NsdManager} answers a resolution with
 * {@code FAILURE_ALREADY_ACTIVE} when the platform is busy with another one and, on many phones, with
 * {@code FAILURE_INTERNAL_ERROR} for a service that resolves a moment later; both are retried, {@value #MAX_RETRIES} times at
 * most for one service and after {@value #DELAY_MILLIS} ms, so a hostile network that makes every resolution fail causes a
 * bounded number of calls. Every other code is final. The table is bounded: a flood of service names evicts the oldest.
 * Pure and thread safe.
 */
public final class SnapcastResolveRetry {

    /** The most retries of one service. */
    public static final int MAX_RETRIES = 2;
    /** The wait before a retry. */
    public static final long DELAY_MILLIS = 2_000L;
    /** The most services counted at once. */
    public static final int MAX_TRACKED = 64;

    private static final int FAILURE_INTERNAL_ERROR = 0;
    private static final int FAILURE_ALREADY_ACTIVE = 3;

    private final Map<String, Integer> attempts = new LinkedHashMap<>();

    /** @return true if the service should be resolved again (and counts this failure) */
    public synchronized boolean shouldRetry(String serviceId, int errorCode) {
        if (serviceId == null || (errorCode != FAILURE_INTERNAL_ERROR && errorCode != FAILURE_ALREADY_ACTIVE)) {
            return false;
        }
        int done = attempts.containsKey(serviceId) ? attempts.remove(serviceId) : 0;
        if (done >= MAX_RETRIES) {
            attempts.put(serviceId, done); // stays counted: a service that keeps failing is not retried again
            evict();
            return false;
        }
        attempts.put(serviceId, done + 1);
        evict();
        return true;
    }

    /** The service was resolved: its next failure starts the count again. */
    public synchronized void succeeded(String serviceId) {
        attempts.remove(serviceId);
    }

    /** Forgets everything (the discovery stopped). */
    public synchronized void clear() {
        attempts.clear();
    }

    synchronized int size() {
        return attempts.size();
    }

    private void evict() {
        while (attempts.size() > MAX_TRACKED) {
            String oldest = attempts.keySet().iterator().next();
            attempts.remove(oldest);
        }
    }
}
