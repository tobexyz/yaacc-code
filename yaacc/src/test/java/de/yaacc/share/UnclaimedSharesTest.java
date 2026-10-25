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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Unit tests for {@link UnclaimedShares}: a share that no player claimed within the claim window
 * is reported as due, so the caller removes it from the registry (and a file share closes its
 * descriptor) instead of letting it linger for hours. Plain JVM, time passed in.
 */
public class UnclaimedSharesTest {

    private static final long WINDOW = ShareLimits.CLAIM_WINDOW_MILLIS;

    private static UnclaimedShares tracker() {
        return new UnclaimedShares();
    }

    private static Set<String> asSet(List<String> keys) {
        return new HashSet<>(keys);
    }

    // ---- defaults

    @Test
    public void theDefaultWindowIsTwoMinutes() {
        assertEquals(120_000L, ShareLimits.CLAIM_WINDOW_MILLIS);
        assertEquals(ShareLimits.CLAIM_WINDOW_MILLIS, UnclaimedShares.DEFAULT_CLAIM_WINDOW_MILLIS);
        assertTrue(UnclaimedShares.DEFAULT_MAX_ENTRIES >= ShareRegistry.DEFAULT_MAX_ACTIVE);
    }

    @Test
    public void nothingIsDueWhenNothingIsTracked() {
        assertTrue(tracker().dueForRemoval(1_000_000L).isEmpty());
        assertEquals(0, tracker().size());
    }

    // ---- the window

    @Test
    public void aShareIsNotDueInsideTheWindow() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 1_000L);

        assertTrue(tracker.dueForRemoval(1_000L).isEmpty());
        assertTrue(tracker.dueForRemoval(1_000L + WINDOW - 1).isEmpty());
        assertEquals(1, tracker.size());
    }

    @Test
    public void aShareIsNotDueExactlyAtTheWindow() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 1_000L);

        assertTrue(tracker.dueForRemoval(1_000L + WINDOW).isEmpty());
    }

    @Test
    public void aShareIsDueMoreThanTheWindowAfterItsRegistration() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 1_000L);

        assertEquals(Collections.singletonList("a"), tracker.dueForRemoval(1_000L + WINDOW + 1));
    }

    @Test
    public void aDueShareIsReportedOnceAndForgotten() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);

        assertEquals(Collections.singletonList("a"), tracker.dueForRemoval(WINDOW + 1));
        assertTrue(tracker.dueForRemoval(WINDOW + 2).isEmpty());
        assertEquals(0, tracker.size());
    }

    @Test
    public void onlyTheExpiredSharesAreDue() {
        UnclaimedShares tracker = tracker();
        tracker.registered("old", 0L);
        tracker.registered("new", 100_000L);

        assertEquals(Collections.singletonList("old"), tracker.dueForRemoval(WINDOW + 1));
        assertEquals(1, tracker.size());
        assertEquals(Collections.singletonList("new"), tracker.dueForRemoval(100_000L + WINDOW + 1));
    }

    @Test
    public void aCustomWindowIsHonoured() {
        UnclaimedShares tracker = new UnclaimedShares(1_000L, 8);
        tracker.registered("a", 0L);

        assertTrue(tracker.dueForRemoval(1_000L).isEmpty());
        assertEquals(Collections.singletonList("a"), tracker.dueForRemoval(1_001L));
    }

    @Test
    public void aClockThatRunsBackwardsDoesNotMakeAnythingDue() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 50_000L);

        assertTrue(tracker.dueForRemoval(0L).isEmpty());
        assertEquals(1, tracker.size());
    }

    @Test
    public void aWindowThatCannotOverflowEvenWithHugeTimes() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", Long.MIN_VALUE / 2);

        assertEquals(Collections.singletonList("a"), tracker.dueForRemoval(Long.MAX_VALUE / 2));
    }

    // ---- claimed / released

    @Test
    public void aClaimedShareIsNeverDue() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);
        tracker.claimed("a");

        assertTrue(tracker.dueForRemoval(10 * WINDOW).isEmpty());
        assertEquals(0, tracker.size());
    }

    @Test
    public void aReleasedShareIsNeverDue() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);
        tracker.released("a");

        assertTrue(tracker.dueForRemoval(10 * WINDOW).isEmpty());
        assertEquals(0, tracker.size());
    }

    @Test
    public void claimingOneShareLeavesTheOthersTracked() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);
        tracker.registered("b", 0L);
        tracker.claimed("a");

        assertEquals(Collections.singletonList("b"), tracker.dueForRemoval(WINDOW + 1));
    }

    @Test
    public void aClaimThatArrivesBeforeTheRegistrationIsRemembered() {
        // the player may claim between ShareRegistry.register and UnclaimedShares.registered
        UnclaimedShares tracker = tracker();
        tracker.claimed("a");
        tracker.registered("a", 0L);

        assertTrue(tracker.dueForRemoval(10 * WINDOW).isEmpty());
        assertEquals(0, tracker.size());
    }

    @Test
    public void aReleaseThatArrivesBeforeTheRegistrationIsRemembered() {
        UnclaimedShares tracker = tracker();
        tracker.released("a");
        tracker.registered("a", 0L);

        assertTrue(tracker.dueForRemoval(10 * WINDOW).isEmpty());
    }

    @Test
    public void unknownKeysAreIgnored() {
        UnclaimedShares tracker = tracker();
        tracker.claimed("nothing");
        tracker.released("nothing");
        tracker.claimed(null);
        tracker.released(null);
        tracker.registered(null, 0L);
        tracker.registered("", 0L);

        assertEquals(0, tracker.size());
        assertTrue(tracker.dueForRemoval(10 * WINDOW).isEmpty());
    }

    // ---- idempotent

    @Test
    public void registeringTheSameKeyAgainKeepsTheFirstTime() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);
        tracker.registered("a", 100_000L);

        assertEquals(1, tracker.size());
        assertEquals(Collections.singletonList("a"), tracker.dueForRemoval(WINDOW + 1));
    }

    @Test
    public void claimingOrReleasingTwiceIsHarmless() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);
        tracker.registered("b", 0L);
        tracker.claimed("a");
        tracker.claimed("a");
        tracker.released("b");
        tracker.released("b");
        tracker.released("a");
        tracker.claimed("b");

        assertTrue(tracker.dueForRemoval(10 * WINDOW).isEmpty());
    }

    @Test
    public void askingForDueSharesTwiceReturnsThemOnlyOnce() {
        UnclaimedShares tracker = tracker();
        tracker.registered("a", 0L);
        tracker.registered("b", 0L);

        assertEquals(new HashSet<>(Arrays.asList("a", "b")), asSet(tracker.dueForRemoval(WINDOW + 1)));
        assertTrue(tracker.dueForRemoval(WINDOW + 1).isEmpty());
    }

    // ---- bounded

    @Test
    public void theCapReturnsTheOldestEntriesAsDue() {
        UnclaimedShares tracker = new UnclaimedShares(WINDOW, 3);
        tracker.registered("k1", 0L);
        tracker.registered("k2", 1L);
        tracker.registered("k3", 2L);
        tracker.registered("k4", 3L);
        tracker.registered("k5", 4L);

        // nothing is old enough, but two entries are over the cap: the oldest two are due
        List<String> due = tracker.dueForRemoval(10L);

        assertEquals(Arrays.asList("k1", "k2"), due);
        assertEquals(3, tracker.size());
        assertTrue(tracker.dueForRemoval(10L).isEmpty());
    }

    @Test
    public void theTrackerNeverHoldsMoreThanTheCap() {
        UnclaimedShares tracker = new UnclaimedShares(WINDOW, 4);
        for (int i = 0; i < 100; i++) {
            tracker.registered("k" + i, i);
            assertTrue(tracker.size() <= 4);
        }
    }

    @Test
    public void everyKeyPushedOutByTheCapIsReportedAsDue() {
        UnclaimedShares tracker = new UnclaimedShares(WINDOW, 2);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            tracker.registered("k" + i, i);
            if (i < 4) {
                expected.add("k" + i);
            }
        }

        assertEquals(expected, asSet(tracker.dueForRemoval(10L)));
    }

    @Test
    public void aClaimedKeyPushedOutByNothingIsNotReportedAsDue() {
        UnclaimedShares tracker = new UnclaimedShares(WINDOW, 2);
        tracker.registered("k1", 0L);
        tracker.claimed("k1");
        tracker.registered("k2", 1L);
        tracker.registered("k3", 2L);

        assertTrue(tracker.dueForRemoval(10L).isEmpty());
        assertEquals(2, tracker.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNonPositiveWindowIsRefused() {
        new UnclaimedShares(0L, 8);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNonPositiveCapIsRefused() {
        new UnclaimedShares(WINDOW, 0);
    }

    // ---- threads

    @Test
    public void concurrentUseLosesNoKeyAndReportsEachDueKeyOnce() throws Exception {
        final int threads = 8;
        final int perThread = 100;
        UnclaimedShares tracker = new UnclaimedShares(WINDOW, threads * perThread);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<List<String>>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                results.add(executor.submit(() -> {
                    go.await();
                    List<String> seen = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        tracker.registered("t" + thread + "-" + i, 0L);
                        seen.addAll(tracker.dueForRemoval(WINDOW + 1));
                    }
                    return seen;
                }));
            }
            go.countDown();
            List<String> all = new ArrayList<>();
            for (Future<List<String>> result : results) {
                all.addAll(result.get(20, TimeUnit.SECONDS));
            }
            all.addAll(tracker.dueForRemoval(WINDOW + 1));

            assertEquals(threads * perThread, all.size());
            assertEquals(threads * perThread, new HashSet<>(all).size());
            assertEquals(0, tracker.size());
        } finally {
            executor.shutdownNow();
        }
    }
}
