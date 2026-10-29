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

package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How long a play action waits for a selected receiver that the registry lost: three looks, 2.5 s
 * apart, a chance to search before each wait. Plain JVM, the waits are recorded instead of slept.
 */
public class ReceiverAvailabilityPolicyTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final List<Integer> searches = new ArrayList<>();
    private final ReceiverAvailabilityPolicy policy = new ReceiverAvailabilityPolicy(sleeps::add);

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void doesNotWaitWhenNothingIsMissing() {
        AtomicInteger looks = new AtomicInteger();

        int missing = policy.await(() -> {
            looks.incrementAndGet();
            return 0;
        }, searches::add);

        assertEquals(0, missing);
        assertEquals(1, looks.get());
        assertTrue(sleeps.isEmpty());
        assertTrue(searches.isEmpty());
    }

    @Test
    public void stopsAsSoonAsTheReceiverIsBack() {
        AtomicInteger looks = new AtomicInteger();

        int missing = policy.await(() -> looks.incrementAndGet() < 2 ? 1 : 0, searches::add);

        assertEquals(0, missing);
        assertEquals(2, looks.get());
        assertEquals(Arrays.asList(2500L), sleeps);
        assertEquals("a search before the wait", Arrays.asList(1), searches);
    }

    @Test
    public void givesUpAfterThreeLooksAboutFiveSecondsLater() {
        AtomicInteger looks = new AtomicInteger();

        int missing = policy.await(() -> {
            looks.incrementAndGet();
            return 2;
        }, searches::add);

        assertEquals("what is still missing is reported", 2, missing);
        assertEquals(ReceiverAvailabilityPolicy.MAX_ATTEMPTS, looks.get());
        assertEquals(Arrays.asList(2500L, 2500L), sleeps);
        assertEquals("no search after the last look", Arrays.asList(1, 2), searches);
    }

    @Test
    public void backAtTheThirdLookIsStillInTime() {
        AtomicInteger looks = new AtomicInteger();

        int missing = policy.await(() -> looks.incrementAndGet() < 3 ? 1 : 0, searches::add);

        assertEquals(0, missing);
        assertEquals(2, sleeps.size());
    }

    @Test
    public void anInterruptedWaitStopsAndKeepsTheInterrupt() {
        ReceiverAvailabilityPolicy interrupted = new ReceiverAvailabilityPolicy(millis -> {
            throw new InterruptedException("cancelled");
        });
        AtomicInteger looks = new AtomicInteger();

        int missing = interrupted.await(() -> {
            looks.incrementAndGet();
            return 1;
        }, searches::add);

        assertEquals(1, missing);
        assertEquals(1, looks.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    public void theWholeWaitIsAboutFiveSeconds() {
        assertEquals(5000L, (ReceiverAvailabilityPolicy.MAX_ATTEMPTS - 1) * ReceiverAvailabilityPolicy.WAIT_MILLIS);
    }
}
