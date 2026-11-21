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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.yaacc.fcast.discovery.FCastDirectory;

/** The live source of the FCast receivers: coalesced at 300 ms, started and stopped with the page, nothing after stop. Plain JVM. */
public class FCastLiveSourceTest {

    private static final class FakePoster implements Coalescer.Poster {
        private final List<Object[]> queue = Collections.synchronizedList(new ArrayList<Object[]>());
        long now;

        @Override
        public void postDelayed(Runnable runnable, long delayMillis) {
            queue.add(new Object[]{now + delayMillis, runnable});
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            synchronized (queue) {
                queue.removeIf(entry -> entry[1] == runnable);
            }
        }

        void advance(long millis) {
            long target = now + millis;
            while (true) {
                Object[] next = null;
                synchronized (queue) {
                    for (Object[] entry : queue) {
                        if ((Long) entry[0] <= target && (next == null || (Long) entry[0] < (Long) next[0])) {
                            next = entry;
                        }
                    }
                    if (next != null) {
                        queue.remove(next);
                    }
                }
                if (next == null) {
                    break;
                }
                now = Math.max(now, (Long) next[0]);
                ((Runnable) next[1]).run();
            }
            now = target;
        }
    }

    private static final class FakeHub implements FCastLiveSource.Hub {
        int started;
        int stopped;
        final List<FCastDirectory.Listener> listeners = new ArrayList<>();

        @Override
        public void pageStarted() {
            started++;
        }

        @Override
        public void pageStopped() {
            stopped++;
        }

        @Override
        public void addChangeListener(FCastDirectory.Listener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeChangeListener(FCastDirectory.Listener listener) {
            listeners.remove(listener);
        }

        void change() {
            for (FCastDirectory.Listener listener : new ArrayList<>(listeners)) {
                listener.changed();
            }
        }
    }

    private FakeHub hub;
    private FakePoster poster;
    private int refreshes;
    private FCastLiveSource source;

    @Before
    public void setUp() {
        hub = new FakeHub();
        poster = new FakePoster();
        refreshes = 0;
        source = new FCastLiveSource(hub, poster, () -> refreshes++);
    }

    @Test
    public void startingOpensThePageOnceAndListens() {
        source.start();
        source.start();

        assertTrue(source.isRunning());
        assertEquals(1, hub.started);
        assertEquals(1, hub.listeners.size());
    }

    @Test
    public void aBurstOfChangesIsOneRefreshAfterTheCoalesceTime() {
        source.start();

        hub.change();
        hub.change();
        hub.change();
        poster.advance(0);
        assertEquals(0, refreshes);
        poster.advance(FCastLiveSource.COALESCE_MILLIS);

        assertEquals(1, refreshes);
    }

    @Test
    public void stoppingClosesThePageAndDropsWhatWasQueued() {
        source.start();
        hub.change();
        poster.advance(0);

        source.stop();
        source.stop();
        poster.advance(10_000);

        assertFalse(source.isRunning());
        assertEquals(1, hub.stopped);
        assertTrue(hub.listeners.isEmpty());
        assertEquals("a change that was queued before the stop does not refresh", 0, refreshes);
    }

    @Test
    public void aChangeAfterStopIsIgnoredAndAFreshStartWorksAgain() {
        source.start();
        source.stop();
        hub.change();
        poster.advance(10_000);
        assertEquals(0, refreshes);

        source.start();
        hub.change();
        poster.advance(10_000);

        assertEquals(1, refreshes);
        assertEquals(2, hub.started);
    }

    @Test
    public void aChangeThatWasQueuedBeforeAStopDoesNotBlockTheNextStart() {
        source.start();
        hub.change(); // a hop is queued
        source.stop(); // it is dropped: the flag that says "a hop is queued" must be reset with it
        source.start();

        hub.change();
        poster.advance(10_000);

        assertEquals("the page refreshes after a stop and a start", 1, refreshes);
    }
}
