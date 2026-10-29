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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Policy of the folder listing cache: ttl, bounds, single flight, failures. Plain JVM, fake clock. */
public class SafListingCacheTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger loads = new AtomicInteger();

    /** Values are lists of entries; the weight of a value is its length. */
    private SafListingCache<List<String>> cache(int maxFolders, long maxWeight, long ttl) {
        return new SafListingCache<>(maxFolders, maxWeight, ttl, 5_000, now::get, List::size, value -> !value.contains("failed"));
    }

    private SafListingCache.Loader<List<String>> loader(String... values) {
        return () -> {
            loads.incrementAndGet();
            List<String> list = new ArrayList<>();
            for (String value : values) {
                list.add(value);
            }
            return list;
        };
    }

    @Test
    public void secondRequestOfTheSameFolderReusesTheListing() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);

        SafListingCache.Result<List<String>> first = cache.get("folder", loader("a", "b"));
        SafListingCache.Result<List<String>> second = cache.get("folder", loader("other"));

        assertEquals(1, loads.get());
        assertFalse(first.fromCache);
        assertTrue(second.fromCache);
        assertSame(first.value, second.value);
    }

    @Test
    public void aListingIsReloadedOnceItIsOlderThanTheTtl() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);
        cache.get("folder", loader("a"));

        now.addAndGet(29_999);
        assertTrue(cache.get("folder", loader("a")).fromCache);

        now.addAndGet(1);
        SafListingCache.Result<List<String>> reloaded = cache.get("folder", loader("a", "new"));

        assertFalse(reloaded.fromCache);
        assertEquals(2, loads.get());
        assertEquals(2, reloaded.value.size());
    }

    @Test
    public void theTtlCountsFromTheEndOfTheListingNotItsStart() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);

        cache.get("folder", () -> {
            now.addAndGet(20_000);
            return new ArrayList<>(List.of("slow"));
        });
        now.addAndGet(25_000);

        assertTrue(cache.get("folder", loader("x")).fromCache);
    }

    @Test
    public void differentFoldersAreCachedSeparately() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);

        cache.get("one", loader("a"));
        cache.get("two", loader("b"));

        assertEquals(2, loads.get());
        assertEquals(2, cache.size());
    }

    @Test
    public void theLeastRecentlyUsedFolderIsEvictedBeyondTheFolderLimit() throws Exception {
        SafListingCache<List<String>> cache = cache(2, 1000, 30_000);
        cache.get("one", loader("a"));
        cache.get("two", loader("b"));
        cache.get("one", loader("a")); // one is more recent than two now

        cache.get("three", loader("c"));

        assertEquals(2, cache.size());
        loads.set(0);
        assertTrue(cache.get("one", loader("a")).fromCache);
        assertFalse(cache.get("two", loader("b")).fromCache); // was evicted
        assertEquals(1, loads.get());
    }

    @Test
    public void listingsAreEvictedWhenTheirSummedWeightExceedsTheLimit() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 5, 30_000);
        cache.get("one", loader("a", "b", "c"));

        cache.get("two", loader("d", "e", "f"));

        assertEquals(1, cache.size());
        loads.set(0);
        assertFalse(cache.get("one", loader("a", "b", "c")).fromCache);
    }

    @Test
    public void aSingleListingHeavierThanTheLimitStillServesItsOwnRequest() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 2, 30_000);

        SafListingCache.Result<List<String>> result = cache.get("big", loader("a", "b", "c", "d"));

        assertEquals(4, result.value.size());
        // the newest listing is never the victim, so the next chunk request does not list again
        assertTrue(cache.get("big", loader("x")).fromCache);
    }

    @Test
    public void clearForgetsEverything() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);
        cache.get("one", loader("a"));

        cache.clear();

        assertEquals(0, cache.size());
        assertFalse(cache.get("one", loader("a")).fromCache);
    }

    @Test
    public void aFailedLoadIsNotCachedAndTheNextRequestTriesAgain() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);

        assertThrows(IOException.class, () -> cache.get("folder", () -> {
            loads.incrementAndGet();
            throw new IOException("provider gone");
        }));
        SafListingCache.Result<List<String>> retry = cache.get("folder", loader("a"));

        assertFalse(retry.fromCache);
        assertEquals(2, loads.get());
    }

    @Test
    public void aValueTheCachePolicyRejectsIsReturnedButNotKept() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);

        SafListingCache.Result<List<String>> failedListing = cache.get("folder", loader("failed"));
        SafListingCache.Result<List<String>> next = cache.get("folder", loader("a"));

        assertEquals("failed", failedListing.value.get(0));
        assertFalse(next.fromCache);
        assertEquals("a", next.value.get(0));
    }

    @Test
    public void aRequestForAFolderBeingListedWaitsForThatListingInsteadOfListingAgain() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicReference<SafListingCache.Result<List<String>>> ownerResult = new AtomicReference<>();
        AtomicReference<SafListingCache.Result<List<String>>> waiterResult = new AtomicReference<>();

        Thread owner = new Thread(() -> {
            try {
                ownerResult.set(cache.get("folder", () -> {
                    loads.incrementAndGet();
                    loaderStarted.countDown();
                    releaseLoader.await(10, TimeUnit.SECONDS);
                    return new ArrayList<>(List.of("a", "b"));
                }));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        owner.start();
        assertTrue(loaderStarted.await(10, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> {
            try {
                waiterResult.set(cache.get("folder", loader("must not run")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waiter.start();
        awaitWaiting(waiter);

        releaseLoader.countDown();
        owner.join(10_000);
        waiter.join(10_000);

        assertEquals(1, loads.get());
        assertNotNull(waiterResult.get());
        assertFalse(ownerResult.get().fromCache);
        assertTrue(waiterResult.get().fromCache);
        assertSame(ownerResult.get().value, waiterResult.get().value);
    }

    @Test
    public void aWaitingRequestSeesTheFailureOfTheListingItWaitedFor() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();

        Thread owner = new Thread(() -> {
            try {
                cache.get("folder", () -> {
                    loaderStarted.countDown();
                    releaseLoader.await(10, TimeUnit.SECONDS);
                    throw new IOException("provider gone");
                });
            } catch (Exception expected) {
                // the owner gets it too
            }
        });
        owner.start();
        assertTrue(loaderStarted.await(10, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> {
            try {
                cache.get("folder", loader("must not run"));
            } catch (Exception e) {
                waiterFailure.set(e);
            }
        });
        waiter.start();
        awaitWaiting(waiter);

        releaseLoader.countDown();
        owner.join(10_000);
        waiter.join(10_000);

        assertTrue(waiterFailure.get() instanceof IOException);
        assertEquals(0, loads.get());
    }

    @Test
    public void aWaitingRequestGivesUpAfterTheMaximumWait() throws Exception {
        SafListingCache<List<String>> shortWait = new SafListingCache<>(8, 1000, 30_000, 50, now::get, List::size, value -> true);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            try {
                shortWait.get("folder", () -> {
                    loaderStarted.countDown();
                    releaseLoader.await(10, TimeUnit.SECONDS);
                    return new ArrayList<>();
                });
            } catch (Exception ignored) {
                // not under test
            }
        });
        owner.start();
        assertTrue(loaderStarted.await(10, TimeUnit.SECONDS));

        try {
            assertThrows(TimeoutException.class, () -> shortWait.get("folder", () -> new ArrayList<>()));
        } finally {
            releaseLoader.countDown();
            owner.join(10_000);
        }
    }

    @Test
    public void aListingClearedWhileItLoadsIsDeliveredButNotKept() throws Exception {
        SafListingCache<List<String>> cache = cache(8, 1000, 30_000);

        SafListingCache.Result<List<String>> result = cache.get("folder", () -> {
            cache.clear(); // e.g. the SAF permission changed during the listing
            return new ArrayList<>(List.of("stale"));
        });

        assertEquals("stale", result.value.get(0));
        assertEquals(0, cache.size());
        assertFalse(cache.get("folder", loader("fresh")).fromCache);
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }
}
