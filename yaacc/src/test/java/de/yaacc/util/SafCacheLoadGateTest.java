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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class SafCacheLoadGateTest {

    @Test
    public void theMainThreadNeverWaits() {
        SafCacheLoadGate gate = new SafCacheLoadGate();
        long start = System.nanoTime();
        assertFalse(gate.await(true, 10_000));
        assertTrue("returned at once", (System.nanoTime() - start) < 1_000_000_000L);
    }

    @Test
    public void otherThreadsWaitUntilItOpensOrTheLimit() throws Exception {
        SafCacheLoadGate gate = new SafCacheLoadGate();
        assertFalse("limit", gate.await(false, 20));

        AtomicBoolean result = new AtomicBoolean();
        Thread waiter = new Thread(() -> result.set(gate.await(false, 10_000)));
        waiter.start();
        Thread.sleep(50);
        gate.open();
        waiter.join(5000);
        assertTrue(result.get());
    }

    @Test
    public void anOpenGateAnswersEveryoneAtOnce() {
        SafCacheLoadGate gate = new SafCacheLoadGate();
        gate.open();
        assertTrue(gate.isOpen());
        assertTrue(gate.await(true, 0));
        assertTrue(gate.await(false, 0));
    }
}
