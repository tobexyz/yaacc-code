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

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Tests for {@link ReadinessWait}: bounded polling with an injectable clock and sleeper. */
public class ReadinessWaitTest {

    private final AtomicLong now = new AtomicLong(0L);
    private final List<Long> sleeps = new ArrayList<>();
    private final ReadinessWait.Sleeper sleeper = millis -> {
        sleeps.add(millis);
        now.addAndGet(millis);
    };

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void returnsAtOnceWhenAlreadyReady() {
        assertTrue(ReadinessWait.await(() -> true, 3_000L, 200L, now::get, sleeper));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void pollsUntilReady() {
        AtomicInteger calls = new AtomicInteger();

        boolean ready = ReadinessWait.await(() -> calls.incrementAndGet() >= 4, 3_000L, 200L, now::get, sleeper);

        assertTrue(ready);
        assertEquals(4, calls.get());
        assertEquals(List.of(200L, 200L, 200L), sleeps);
    }

    @Test
    public void givesUpAfterTheMaximumAndChecksOnceMoreAtTheEnd() {
        AtomicInteger calls = new AtomicInteger();

        boolean ready = ReadinessWait.await(() -> {
            calls.incrementAndGet();
            return false;
        }, 3_000L, 200L, now::get, sleeper);

        assertFalse(ready);
        assertEquals(3_000L, now.get());
        assertEquals(16, calls.get());
    }

    @Test
    public void theLastSleepIsShortenedToTheRemainingTime() {
        ReadinessWait.await(() -> false, 500L, 200L, now::get, sleeper);

        assertEquals(List.of(200L, 200L, 100L), sleeps);
    }

    @Test
    public void aNonPositiveMaximumChecksExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();

        assertFalse(ReadinessWait.await(() -> {
            calls.incrementAndGet();
            return false;
        }, 0L, 200L, now::get, sleeper));

        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void aNonPositivePollIntervalDoesNotSpin() {
        AtomicInteger calls = new AtomicInteger();

        ReadinessWait.await(() -> {
            calls.incrementAndGet();
            return false;
        }, 1_000L, 0L, now::get, sleeper);

        assertTrue("bounded number of checks", calls.get() <= 1_001);
        assertTrue(now.get() >= 1_000L);
    }

    @Test
    public void aThrowingCheckCountsAsNotReady() {
        AtomicInteger calls = new AtomicInteger();

        boolean ready = ReadinessWait.await(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return true;
        }, 3_000L, 200L, now::get, sleeper);

        assertTrue(ready);
    }

    @Test
    public void anInterruptStopsTheWaitAndKeepsTheFlag() {
        ReadinessWait.Sleeper interrupting = millis -> {
            throw new InterruptedException();
        };

        boolean ready = ReadinessWait.await(() -> false, 3_000L, 200L, now::get, interrupting);

        assertFalse(ready);
        assertTrue(Thread.currentThread().isInterrupted());
    }
}
