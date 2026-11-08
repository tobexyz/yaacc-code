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
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * A burst of triggers (device announcements come in floods) runs the action once, a little later. Plain JVM with a fake clock.
 */
public class CoalescerTest {

    private static final class FakePoster implements Coalescer.Poster {
        final List<Runnable> posted = new ArrayList<>();
        long lastDelay;

        @Override
        public void postDelayed(Runnable runnable, long delayMillis) {
            posted.add(runnable);
            lastDelay = delayMillis;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            posted.remove(runnable);
        }

        void tick() {
            List<Runnable> due = new ArrayList<>(posted);
            posted.clear();
            for (Runnable runnable : due) {
                runnable.run();
            }
        }
    }

    private FakePoster poster;
    private int runs;
    private Coalescer coalescer;

    @Before
    public void setUp() {
        poster = new FakePoster();
        runs = 0;
        coalescer = new Coalescer(poster, () -> runs++, 300);
    }

    @Test
    public void aBurstRunsTheActionOnceAfterTheDelay() {
        for (int i = 0; i < 1000; i++) {
            coalescer.trigger();
        }

        assertEquals(1, poster.posted.size());
        assertEquals(300, poster.lastDelay);
        assertEquals(0, runs);

        poster.tick();

        assertEquals(1, runs);
        assertTrue(poster.posted.isEmpty());
    }

    @Test
    public void aTriggerAfterTheRunSchedulesTheNextRun() {
        coalescer.trigger();
        poster.tick();
        coalescer.trigger();
        coalescer.trigger();
        poster.tick();

        assertEquals(2, runs);
    }

    @Test
    public void cancelDropsThePendingRun() {
        coalescer.trigger();

        coalescer.cancel();
        poster.tick();

        assertEquals(0, runs);
        assertTrue(poster.posted.isEmpty());
    }

    @Test
    public void cancelWithoutATriggerDoesNothing() {
        coalescer.cancel();
        coalescer.trigger();
        poster.tick();

        assertEquals(1, runs);
    }
}
