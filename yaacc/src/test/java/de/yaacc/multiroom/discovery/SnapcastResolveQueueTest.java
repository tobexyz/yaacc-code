/*
 *
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;

/** One resolution at a time, bounded, deduplicated by name, and an unanswered one is given up. */
public class SnapcastResolveQueueTest {

    private final AtomicLong now = new AtomicLong();
    private final MonotonicClock clock = now::get;
    private SnapcastResolveQueue<String> queue;

    @Before
    public void setUp() {
        queue = new SnapcastResolveQueue<>(clock, 10_000L, 4);
    }

    @Test
    public void servicesAreResolvedOneAfterTheOther() {
        queue.offer("a", "A");
        queue.offer("b", "B");

        assertEquals("a", queue.next().getName());
        assertNull("a is being resolved", queue.next());
        queue.done("a");
        assertEquals("b", queue.next().getName());
        queue.done("b");
        assertNull(queue.next());
    }

    @Test
    public void aNameIsQueuedOnceAndTheNewerItemWins() {
        queue.offer("a", "old");
        queue.offer("a", "new");

        assertEquals(1, queue.size());
        assertEquals("new", queue.next().getItem());
    }

    @Test
    public void aNameThatIsBeingResolvedIsNotQueuedAgain() {
        queue.offer("a", "A");
        queue.next();

        assertTrue(queue.offer("a", "A2"));

        assertEquals(1, queue.size());
    }

    @Test
    public void theQueueIsBounded() {
        for (int i = 0; i < 4; i++) {
            assertTrue(queue.offer("n" + i, "x"));
        }
        assertFalse(queue.offer("n4", "x"));
        assertFalse(queue.offer("", "x"));
        assertFalse(queue.offer(null, "x"));
        assertEquals(4, queue.size());
    }

    @Test
    public void anUnansweredResolutionIsGivenUpAfterTheTimeout() {
        queue.offer("a", "A");
        queue.offer("b", "B");
        queue.next();

        now.addAndGet(9_999);
        assertNull(queue.next());
        now.addAndGet(1);

        assertEquals("b", queue.next().getName());
    }

    @Test
    public void anAnswerForAnotherNameIsIgnored() {
        queue.offer("a", "A");
        queue.offer("b", "B");
        queue.next();

        queue.done("b");

        assertNull("a is still being resolved", queue.next());
    }

    @Test
    public void clearForgetsEverything() {
        queue.offer("a", "A");
        queue.next();
        queue.offer("b", "B");

        queue.clear();

        assertEquals(0, queue.size());
        assertNull(queue.next());
    }
}
