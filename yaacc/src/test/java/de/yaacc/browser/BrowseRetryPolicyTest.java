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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.DIDLContent;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.upnp.UpnpFailure;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

/**
 * The retry rules for a failed Browse request: what is retriable, how often, how long to wait and
 * when to stop early. Plain JVM, the waits are recorded instead of slept.
 */
public class BrowseRetryPolicyTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final BrowseRetryPolicy policy = new BrowseRetryPolicy(sleeps::add);

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static ContentDirectoryBrowseResult content() {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        result.setResult(new DIDLContent());
        return result;
    }

    private static ContentDirectoryBrowseResult transportFailure() {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        result.setUpnpFailure(new UpnpFailure(null, null, "no connection", true));
        return result;
    }

    private static ContentDirectoryBrowseResult serverError() {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        result.setUpnpFailure(new UpnpFailure(null, null, "709 unsupported sort criteria", false));
        return result;
    }

    // --- classification -------------------------------------------------------------------------------

    @Test
    public void noResultIsRetriable() {
        assertTrue(BrowseRetryPolicy.isRetriable(null));
    }

    @Test
    public void transportFailureIsRetriable() {
        assertTrue(BrowseRetryPolicy.isRetriable(transportFailure()));
    }

    @Test
    public void answerOfTheServerIsNotRetriable() {
        assertFalse(BrowseRetryPolicy.isRetriable(serverError()));
    }

    @Test
    public void contentIsNotRetriableNotEvenAnEmptyFolder() {
        assertFalse(BrowseRetryPolicy.isRetriable(content()));
    }

    @Test
    public void resultWithNeitherContentNorFailureIsNotRetriable() {
        assertFalse(BrowseRetryPolicy.isRetriable(new ContentDirectoryBrowseResult()));
    }

    @Test
    public void isFailureCoversNoResultAndEveryFailureButNotContent() {
        assertTrue(BrowseRetryPolicy.isFailure(null));
        assertTrue(BrowseRetryPolicy.isFailure(transportFailure()));
        assertTrue(BrowseRetryPolicy.isFailure(serverError()));
        assertFalse(BrowseRetryPolicy.isFailure(content()));
        assertFalse(BrowseRetryPolicy.isFailure(new ContentDirectoryBrowseResult()));
    }

    // --- attempts and backoff -------------------------------------------------------------------------

    @Test
    public void threeTriesInTotalWithTheDocumentedBackoff() {
        assertEquals(3, BrowseRetryPolicy.MAX_ATTEMPTS);
        assertEquals(0L, BrowseRetryPolicy.backoffMillisBefore(1));
        assertEquals(500L, BrowseRetryPolicy.backoffMillisBefore(2));
        assertEquals(1500L, BrowseRetryPolicy.backoffMillisBefore(3));
    }

    @Test
    public void successOnTheFirstTryNeitherRepeatsNorWaits() {
        AtomicInteger calls = new AtomicInteger();
        ContentDirectoryBrowseResult ok = content();

        ContentDirectoryBrowseResult result = policy.execute(() -> {
            calls.incrementAndGet();
            return ok;
        }, () -> false);

        assertSame(ok, result);
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void transportFailureIsTriedThreeTimesWaitingBetweenTheTries() {
        AtomicInteger calls = new AtomicInteger();
        ContentDirectoryBrowseResult last = transportFailure();

        ContentDirectoryBrowseResult result = policy.execute(() -> calls.incrementAndGet() == 3 ? last : transportFailure(), () -> false);

        assertSame("the result of the last try is returned", last, result);
        assertEquals(3, calls.get());
        assertEquals(Arrays.asList(500L, 1500L), sleeps);
    }

    @Test
    public void aTryThatSucceedsEndsTheRetries() {
        AtomicInteger calls = new AtomicInteger();
        ContentDirectoryBrowseResult ok = content();

        ContentDirectoryBrowseResult result = policy.execute(() -> calls.incrementAndGet() < 2 ? transportFailure() : ok, () -> false);

        assertSame(ok, result);
        assertEquals(2, calls.get());
        assertEquals(Arrays.asList(500L), sleeps);
    }

    @Test
    public void answerOfTheServerIsNotRepeated() {
        AtomicInteger calls = new AtomicInteger();

        ContentDirectoryBrowseResult result = policy.execute(() -> {
            calls.incrementAndGet();
            return serverError();
        }, () -> false);

        assertFalse(result == null);
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void noResultIsTriedThreeTimes() {
        AtomicInteger calls = new AtomicInteger();

        ContentDirectoryBrowseResult result = policy.execute(() -> {
            calls.incrementAndGet();
            return null;
        }, () -> false);

        assertNull(result);
        assertEquals(3, calls.get());
        assertEquals(Arrays.asList(500L, 1500L), sleeps);
    }

    // --- early stop -----------------------------------------------------------------------------------

    @Test
    public void cancelledBeforeTheFirstTryRunsNothing() {
        AtomicInteger calls = new AtomicInteger();

        ContentDirectoryBrowseResult result = policy.execute(() -> {
            calls.incrementAndGet();
            return transportFailure();
        }, () -> true);

        assertNull(result);
        assertEquals(0, calls.get());
    }

    @Test
    public void cancelledAfterTheFirstFailureStopsBeforeTheSecondTry() {
        AtomicInteger calls = new AtomicInteger();
        ContentDirectoryBrowseResult first = transportFailure();

        // the task is cancelled while the first try runs
        ContentDirectoryBrowseResult result = policy.execute(() -> {
            calls.incrementAndGet();
            return first;
        }, () -> calls.get() >= 1);

        assertSame(first, result);
        assertEquals(1, calls.get());
    }

    @Test
    public void interruptedWhileWaitingStopsAndKeepsTheInterruptForTheCaller() {
        AtomicInteger calls = new AtomicInteger();
        BrowseRetryPolicy interrupted = new BrowseRetryPolicy(millis -> {
            throw new InterruptedException("cancelled");
        });
        ContentDirectoryBrowseResult first = transportFailure();

        ContentDirectoryBrowseResult result = interrupted.execute(() -> {
            calls.incrementAndGet();
            return first;
        }, () -> false);

        assertSame(first, result);
        assertEquals(1, calls.get());
        assertTrue("the interrupt flag is set again", Thread.currentThread().isInterrupted());
    }
}
