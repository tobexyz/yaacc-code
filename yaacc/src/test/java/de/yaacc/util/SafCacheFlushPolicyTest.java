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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class SafCacheFlushPolicyTest {

    private final AtomicLong now = new AtomicLong(1000);
    private final SafCacheFlushPolicy policy = new SafCacheFlushPolicy(3, 5000, now::get);

    @Test
    public void nothingIsDueWithoutChanges() {
        assertFalse(policy.isDue());
        assertFalse(policy.hasChanges());
        assertEquals(-1, policy.millisUntilDue());
        now.addAndGet(60_000);
        assertFalse(policy.isDue());
    }

    @Test
    public void dueAfterTheMaximumNumberOfChanges() {
        policy.recordChange();
        policy.recordChange();
        assertFalse(policy.isDue());
        policy.recordChange();
        assertTrue(policy.isDue());
        assertEquals(0, policy.millisUntilDue());
    }

    @Test
    public void dueWhenTheOldestChangeIsOldEnough() {
        policy.recordChange();
        now.addAndGet(2000);
        policy.recordChange();
        assertFalse(policy.isDue());
        assertEquals(3000, policy.millisUntilDue());
        now.addAndGet(2999);
        assertFalse(policy.isDue());
        now.addAndGet(1);
        assertTrue("5000 ms after the first change, not the last", policy.isDue());
        assertEquals(0, policy.millisUntilDue());
    }

    @Test
    public void flushingStartsAFreshWindow() {
        policy.recordChange();
        now.addAndGet(6000);
        assertTrue(policy.isDue());
        policy.flushed();
        assertFalse(policy.isDue());
        assertFalse(policy.hasChanges());

        now.addAndGet(10_000);
        policy.recordChange();
        assertFalse("the window starts with the first change after the flush", policy.isDue());
        assertEquals(5000, policy.millisUntilDue());
    }

    @Test
    public void manyChangesWithinTheWindowAreOneFlushNotOnePerChange() {
        int flushes = 0;
        for (int i = 0; i < 10; i++) {
            policy.recordChange();
            if (policy.isDue()) {
                flushes++;
                policy.flushed();
            }
        }
        assertEquals(3, flushes); // 9 of the 10 changes, in three batches of 3
        assertEquals(1, policy.changes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonsenseLimits() {
        new SafCacheFlushPolicy(0, 1000, now::get);
    }
}
