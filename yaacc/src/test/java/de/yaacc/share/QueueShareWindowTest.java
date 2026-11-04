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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class QueueShareWindowTest {

    /** Binds nothing real: remembers what is bound. */
    private static final class FakeBinder implements QueueShareWindow.Binder {
        final List<Integer> bound = new ArrayList<>();
        final List<Integer> released = new ArrayList<>();
        int maxBoundAtOnce;
        final java.util.Set<Integer> failing = new java.util.HashSet<>();
        int limit = Integer.MAX_VALUE;

        @Override
        public QueueShareWindow.Bound bind(int index) {
            if (failing.contains(index) || bound.size() >= limit) {
                return null;
            }
            bound.add(index);
            maxBoundAtOnce = Math.max(maxBoundAtOnce, bound.size());
            return () -> {
                bound.remove(Integer.valueOf(index));
                released.add(index);
            };
        }
    }

    @Test
    public void theFirstAcquireBindsLaterOnesOnlyCount() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(10, binder);
        assertTrue(window.acquire(3));
        assertTrue(window.acquire(3));
        assertEquals(1, binder.bound.size());
        assertEquals(1, window.activeCount());
        window.release(3);
        assertEquals(1, binder.bound.size());
        window.release(3);
        assertEquals(0, binder.bound.size());
        assertEquals(0, window.activeCount());
    }

    @Test
    public void releaseWithoutAcquireOrTooOftenIsIgnored() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(5, binder);
        window.release(2);
        assertTrue(window.acquire(2));
        window.release(2);
        window.release(2);
        assertEquals(1, binder.released.size());
        assertFalse(window.acquire(-1));
        assertFalse(window.acquire(5));
    }

    @Test
    public void aFailedBindIsReportedAndTakesNoCount() {
        FakeBinder binder = new FakeBinder();
        binder.failing.add(1);
        QueueShareWindow window = new QueueShareWindow(5, binder);
        assertFalse(window.acquire(1));
        assertEquals(0, window.activeCount());
        binder.failing.clear();
        assertTrue(window.acquire(1));
    }

    @Test
    public void aLeaseHoldsAtMostTheCurrentAndTheNextItem() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(20, binder);
        QueueShareWindow.Lease lease = window.newLease();
        for (int i = 0; i < 20; i++) {
            assertTrue(lease.moveTo(i, i + 1 < 20 ? i + 1 : QueueShareWindow.NONE));
        }
        assertTrue(binder.maxBoundAtOnce <= 2);
        lease.close();
        assertEquals(0, binder.bound.size());
        assertEquals(0, window.activeCount());
    }

    @Test
    public void movingKeepsTheNextAsTheNewCurrentWithoutBindingItTwice() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(5, binder);
        QueueShareWindow.Lease lease = window.newLease();
        lease.moveTo(0, 1);
        lease.moveTo(1, 2);
        assertEquals(java.util.Arrays.asList(0, 1, 2), sorted(binder.released, binder.bound));
        assertEquals(java.util.Arrays.asList(1, 2), new ArrayList<>(sortedList(binder.bound)));
    }

    @Test
    public void twoLeasesOnDifferentIndexesShareOneBindingPerIndex() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(10, binder);
        QueueShareWindow.Lease a = window.newLease();
        QueueShareWindow.Lease b = window.newLease();
        a.moveTo(0, 1);
        b.moveTo(0, 1);
        assertEquals(2, binder.bound.size());
        a.moveTo(1, 2);
        assertEquals(java.util.Arrays.asList(0, 1, 2), sortedList(allEverBound(binder)));
        assertTrue(binder.bound.contains(0));
        b.moveTo(1, 2);
        assertFalse(binder.bound.contains(0));
        a.close();
        b.close();
        assertEquals(0, binder.bound.size());
    }

    @Test
    public void whenTheRegistryIsFullTheCurrentItemIsReportedAndNothingRunningIsEvicted() {
        FakeBinder binder = new FakeBinder();
        binder.limit = 1;
        QueueShareWindow window = new QueueShareWindow(5, binder);
        QueueShareWindow.Lease lease = window.newLease();
        assertTrue(lease.moveTo(0, 1));
        assertEquals(java.util.Arrays.asList(0), binder.bound);
        assertEquals(0, binder.released.size());
        binder.limit = Integer.MAX_VALUE;
        assertTrue(lease.moveTo(1, 2));
    }

    @Test
    public void theCurrentItemFailingToBindIsReportedByTheLease() {
        FakeBinder binder = new FakeBinder();
        binder.failing.add(0);
        QueueShareWindow window = new QueueShareWindow(5, binder);
        QueueShareWindow.Lease lease = window.newLease();
        assertFalse(lease.moveTo(0, 1));
        assertTrue(lease.moveTo(1, 2));
    }

    @Test
    public void closeIsIdempotentAndReleaseAllClearsEverything() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(5, binder);
        QueueShareWindow.Lease lease = window.newLease();
        lease.moveTo(0, 1);
        window.releaseAll();
        assertEquals(0, binder.bound.size());
        lease.close();
        lease.close();
        assertEquals(2, binder.released.size());
        assertFalse(window.acquire(0));
    }

    @Test
    public void itemsAreFoundByIdentityNotByPosition() {
        FakeBinder binder = new FakeBinder();
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        QueueShareWindow window = new QueueShareWindow(java.util.Arrays.asList(a, b, c), binder);
        QueueShareWindow.Lease lease = window.newLease();
        assertTrue(lease.moveToItems(c, null));
        assertEquals(java.util.Arrays.asList(2), binder.bound);
        assertTrue(lease.moveToItems(a, null));
        assertEquals(java.util.Arrays.asList(0), binder.bound);
    }

    @Test
    public void anItemOfNoQueueNeedsNoShareAndHoldsNothing() {
        FakeBinder binder = new FakeBinder();
        Object a = new Object();
        QueueShareWindow window = new QueueShareWindow(java.util.Arrays.asList(a), binder);
        QueueShareWindow.Lease lease = window.newLease();
        assertTrue(lease.moveToItems(a, null));
        assertTrue(lease.moveToItems(new Object(), null));
        assertEquals(0, binder.bound.size());
        assertTrue(lease.moveToItems(null, null));
    }

    @Test
    public void aDroppedLeaseCanBeUsedAgainAClosedOneCannot() {
        FakeBinder binder = new FakeBinder();
        QueueShareWindow window = new QueueShareWindow(3, binder);
        QueueShareWindow.Lease lease = window.newLease();
        assertTrue(lease.moveTo(0, QueueShareWindow.NONE));
        lease.drop();
        assertEquals(0, binder.bound.size());
        assertTrue(lease.moveTo(1, QueueShareWindow.NONE));
        assertEquals(1, binder.bound.size());
        lease.close();
        assertEquals(0, binder.bound.size());
        assertFalse(lease.moveTo(2, QueueShareWindow.NONE));
        assertEquals(0, binder.bound.size());
    }

    @Test(timeout = 10000)
    public void closeDoesNotWaitForAShareThatIsBeingCreatedAndReleasesItWhenItIsDone() throws Exception {
        final java.util.concurrent.CountDownLatch binding = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        final List<Integer> released = new ArrayList<>();
        QueueShareWindow window = new QueueShareWindow(3, index -> {
            binding.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return () -> released.add(index);
        });
        final QueueShareWindow.Lease lease = window.newLease();
        final boolean[] moved = new boolean[1];
        Thread mover = new Thread(() -> moved[0] = lease.moveTo(0, QueueShareWindow.NONE));
        mover.start();
        assertTrue(binding.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // the bind is in progress: close must return at once
        lease.close();
        finish.countDown();
        mover.join(5000);
        assertFalse(moved[0]);
        assertEquals(java.util.Arrays.asList(0), released);
        assertEquals(0, window.activeCount());
    }

    private static List<Integer> allEverBound(FakeBinder binder) {
        List<Integer> all = new ArrayList<>(binder.released);
        all.addAll(binder.bound);
        return all;
    }

    private static List<Integer> sortedList(List<Integer> list) {
        List<Integer> copy = new ArrayList<>(list);
        java.util.Collections.sort(copy);
        return copy;
    }

    private static List<Integer> sorted(List<Integer> a, List<Integer> b) {
        List<Integer> all = new ArrayList<>(a);
        all.addAll(b);
        java.util.Collections.sort(all);
        return all;
    }
}
