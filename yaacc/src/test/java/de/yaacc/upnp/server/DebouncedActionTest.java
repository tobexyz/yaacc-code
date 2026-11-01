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
package de.yaacc.upnp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DebouncedActionTest {

    private DebouncedAction debounced;

    @After
    public void tearDown() {
        if (debounced != null) {
            debounced.shutdown();
        }
    }

    @Test
    public void manyRequestsWithinTheDelayRunTheActionOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        debounced = new DebouncedAction(() -> {
            runs.incrementAndGet();
            first.countDown();
        }, 300, "test-debounce");
        for (int i = 0; i < 20; i++) {
            debounced.request();
        }
        assertTrue(first.await(5, TimeUnit.SECONDS));
        Thread.sleep(500); // a second run would have happened by now
        assertEquals(1, runs.get());
    }

    @Test
    public void aRequestAfterTheRunStartsANewRound() throws Exception {
        CountDownLatch twice = new CountDownLatch(2);
        debounced = new DebouncedAction(twice::countDown, 50, "test-debounce");
        debounced.request();
        Thread.sleep(400);
        debounced.request();
        assertTrue(twice.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void aRequestAfterShutdownIsDroppedWithoutError() {
        debounced = new DebouncedAction(() -> { }, 10, "test-debounce");
        debounced.shutdown();
        debounced.request();
        debounced.request();
    }
}
