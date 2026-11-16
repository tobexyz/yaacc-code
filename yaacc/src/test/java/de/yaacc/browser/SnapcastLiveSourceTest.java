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

import de.yaacc.multiroom.SnapcastReceivers;

/**
 * The live source of the Snapcast groups: changes are coalesced at 300 ms, the source starts and stops with the page,
 * nothing arrives after stop, and a change from a connection thread reaches the main thread. Plain JVM with a hub and
 * a main-thread poster that the test drives by hand.
 */
public class SnapcastLiveSourceTest {

    /** A main thread with a virtual clock. */
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

        int pending() {
            return queue.size();
        }

        /** Moves the clock and runs what is due, in order of time. */
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

    private static final class FakeHub implements SnapcastLiveSource.Hub {
        int started;
        int stopped;
        final List<SnapcastReceivers.ChangeListener> listeners = new ArrayList<>();

        @Override
        public void pageStarted() {
            started++;
        }

        @Override
        public void pageStopped() {
            stopped++;
        }

        @Override
        public void addChangeListener(SnapcastReceivers.ChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeChangeListener(SnapcastReceivers.ChangeListener listener) {
            listeners.remove(listener);
        }

        void change() {
            for (SnapcastReceivers.ChangeListener listener : new ArrayList<>(listeners)) {
                listener.onGroupsChanged();
            }
        }
    }

    private FakeHub hub;
    private FakePoster poster;
    private int refreshes;
    private SnapcastLiveSource source;

    @Before
    public void setUp() {
        hub = new FakeHub();
        poster = new FakePoster();
        refreshes = 0;
        source = new SnapcastLiveSource(hub, poster, () -> refreshes++);
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
    public void nothingHappensBeforeTheStart() {
        hub.change();
        poster.advance(10_000);

        assertEquals(0, hub.started);
        assertEquals(0, refreshes);
    }

    @Test
    public void aBurstOfChangesIsOneRefreshAfter300Ms() {
        source.start();

        for (int i = 0; i < 100; i++) {
            hub.change();
        }
        poster.advance(0);
        assertEquals("not at once", 0, refreshes);
        poster.advance(299);
        assertEquals(0, refreshes);
        poster.advance(1);
        assertEquals(1, refreshes);
        poster.advance(10_000);
        assertEquals("one for the whole burst", 1, refreshes);
    }

    @Test
    public void aChangeAfterARefreshStartsTheNextOne() {
        source.start();
        hub.change();
        poster.advance(300);
        assertEquals(1, refreshes);

        hub.change();
        poster.advance(299);
        assertEquals(1, refreshes);
        poster.advance(1);
        assertEquals(2, refreshes);
    }

    @Test
    public void changesInsideTheWindowDoNotPostponeTheRefresh() {
        source.start();
        hub.change();
        poster.advance(200);
        hub.change();
        poster.advance(100);

        assertEquals("a continuous flood still refreshes every 300 ms", 1, refreshes);
    }

    @Test
    public void rowsFollowAChangeWellWithinTwoSeconds() {
        source.start();
        hub.change();

        poster.advance(2_000);

        assertEquals(1, refreshes);
    }

    @Test
    public void stopCancelsAWaitingRefreshAndLetsTheHubKnow() {
        source.start();
        hub.change();
        poster.advance(100);

        source.stop();
        poster.advance(10_000);

        assertEquals(0, refreshes);
        assertEquals(1, hub.stopped);
        assertTrue(hub.listeners.isEmpty());
        assertFalse(source.isRunning());
        assertEquals("nothing left on the main thread", 0, poster.pending());
    }

    @Test
    public void aChangeAfterStopIsIgnored() {
        source.start();
        source.stop();

        hub.change();
        poster.advance(10_000);

        assertEquals(0, refreshes);
        assertEquals(0, poster.pending());
    }

    @Test
    public void stopWithoutStartAndStopTwiceDoNothing() {
        source.stop();
        source.start();
        source.stop();
        source.stop();

        assertEquals(1, hub.started);
        assertEquals(1, hub.stopped);
    }

    @Test
    public void aRestartListensAgain() {
        source.start();
        source.stop();

        source.start();
        hub.change();
        poster.advance(300);

        assertEquals(2, hub.started);
        assertEquals(1, refreshes);
    }

    @Test
    public void aRestartAfterAStopWithAHopWaitingStillDelivers() {
        source.start();
        hub.change(); // the hop to the main thread is queued
        source.stop();
        source.start();

        hub.change();
        poster.advance(300);

        assertEquals(1, refreshes);
    }

    @Test
    public void aChangeFromAConnectionThreadIsMovedToTheMainThread() throws Exception {
        source.start();
        final List<String> threadOfRefresh = new ArrayList<>();
        SnapcastLiveSource threaded = new SnapcastLiveSource(hub, poster,
                () -> threadOfRefresh.add(Thread.currentThread().getName()));
        threaded.start();

        Thread connection = new Thread(hub::change, "connection-thread");
        connection.start();
        connection.join();
        assertEquals("only queued, nothing ran on the connection thread", 0, threadOfRefresh.size());

        poster.advance(300);

        assertEquals(1, threadOfRefresh.size());
        assertEquals(Thread.currentThread().getName(), threadOfRefresh.get(0));
    }
}
