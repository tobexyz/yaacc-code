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
package de.yaacc.browser;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

/**
 * Decides whether and how a failed Browse request is repeated, so a short network drop does not
 * show up as an empty folder. No Android classes: unit-testable on the JVM.
 * <p>
 * Rules:
 * <ul>
 *     <li>At most {@link #MAX_ATTEMPTS} tries in total; the waits before try 2 and 3 are
 *     {@link #BACKOFF_MILLIS} (500 ms, 1500 ms).</li>
 *     <li>A result is repeated when there is no result at all (no provider device right now,
 *     discovery may bring it back), or when it carries a transport failure (no connection, timeout,
 *     no answer, device missing from the registry).</li>
 *     <li>It is not repeated when the server answered with a UPnP error (for example an
 *     unsupported sort criterion: the same request would fail the same way), when it has content
 *     (also an empty folder), or when it has neither content nor failure.</li>
 *     <li>It stops early when the task is cancelled or the waiting thread is interrupted.</li>
 * </ul>
 * Meant for the worker thread only: {@link #execute} sleeps.
 */
public final class BrowseRetryPolicy {

    /** Total number of tries, the first one included. */
    public static final int MAX_ATTEMPTS = 3;

    /** Wait before try 2 and before try 3. */
    public static final long[] BACKOFF_MILLIS = {500L, 1500L};

    /** Sleeps; replaceable so tests do not wait. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Sleeper sleeper;

    public BrowseRetryPolicy() {
        this(Thread::sleep);
    }

    BrowseRetryPolicy(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * @return true if {@code result} is no answer of the server, so another try can help
     */
    public static boolean isRetriable(ContentDirectoryBrowseResult result) {
        if (result == null) {
            return true;
        }
        if (result.getUpnpFailure() != null) {
            return result.getUpnpFailure().isTransportFailure();
        }
        return false;
    }

    /**
     * @return true if {@code result} is a failed load: no result at all or a result with a failure
     * (retriable or not); a result without content and without failure is not
     */
    public static boolean isFailure(ContentDirectoryBrowseResult result) {
        return result == null || result.getUpnpFailure() != null;
    }

    /**
     * @param attemptNumber 2 or 3 (the try that follows the wait)
     * @return the wait before that try in milliseconds, 0 for the first try
     */
    public static long backoffMillisBefore(int attemptNumber) {
        if (attemptNumber < 2) {
            return 0L;
        }
        int index = Math.min(attemptNumber - 2, BACKOFF_MILLIS.length - 1);
        return BACKOFF_MILLIS[index];
    }

    /**
     * Runs {@code attempt} until it gives a result that is not retriable, the tries are used up,
     * {@code cancelled} turns true or the thread is interrupted while waiting.
     *
     * @return the result of the last try that ran; null if cancelled before the first try or the
     * last try gave no result
     */
    public ContentDirectoryBrowseResult execute(Supplier<ContentDirectoryBrowseResult> attempt, BooleanSupplier cancelled) {
        ContentDirectoryBrowseResult result = null;
        for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
            if (cancelled.getAsBoolean()) {
                return result;
            }
            result = attempt.get();
            if (!isRetriable(result) || attemptNumber == MAX_ATTEMPTS) {
                return result;
            }
            try {
                sleeper.sleep(backoffMillisBefore(attemptNumber + 1));
            } catch (InterruptedException e) {
                // keep the interrupt for the caller (a cancelled AsyncTask interrupts its thread)
                Thread.currentThread().interrupt();
                return result;
            }
        }
        return result;
    }
}
