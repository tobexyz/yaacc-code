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
package de.yaacc.upnp.server.renderingcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The watcher of the RenderingControl service while the renderer can play into a group (task 9.1): the system's volume
 * broadcast as before, plus the changes of the groups and of the output setting, all turned into the one "volume may have
 * changed" check, which only reads. Plain JVM.
 */
public class RoutedVolumeWatcherTest {

    private static final class FakeSystem implements VolumeWatcher {
        Runnable onChange;
        int starts;
        int stops;
        boolean failToStart;

        @Override
        public void start(Runnable callback) {
            starts++;
            if (failToStart) {
                throw new IllegalStateException("no broadcast");
            }
            onChange = callback;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static final class FakeOthers implements RoutedVolumeWatcher.ChangeSource {
        Runnable onChange;
        int subscriptions;
        int unsubscriptions;

        @Override
        public Runnable subscribe(Runnable callback) {
            subscriptions++;
            onChange = callback;
            return () -> unsubscriptions++;
        }
    }

    @Test
    public void bothSourcesTellTheSameCallbackAndBothAreStoppedTogether() {
        FakeSystem system = new FakeSystem();
        FakeOthers others = new FakeOthers();
        RoutedVolumeWatcher watcher = new RoutedVolumeWatcher(system, others);
        int[] calls = {0};

        watcher.start(() -> calls[0]++);
        system.onChange.run();
        others.onChange.run();
        others.onChange.run();
        watcher.stop();

        assertEquals(3, calls[0]);
        assertEquals(1, system.starts);
        assertEquals(1, system.stops);
        assertEquals(1, others.subscriptions);
        assertEquals(1, others.unsubscriptions);
    }

    @Test
    public void aBroadcastThatCanNotBeWatchedDoesNotStopTheGroupChanges() {
        FakeSystem system = new FakeSystem();
        system.failToStart = true;
        FakeOthers others = new FakeOthers();
        RoutedVolumeWatcher watcher = new RoutedVolumeWatcher(system, others);
        int[] calls = {0};

        watcher.start(() -> calls[0]++);
        others.onChange.run();

        assertEquals(1, calls[0]);
        assertEquals(1, others.subscriptions);
    }

    @Test
    public void stoppingTwiceOrWithoutAStartDoesNothingMore() {
        FakeSystem system = new FakeSystem();
        FakeOthers others = new FakeOthers();
        RoutedVolumeWatcher watcher = new RoutedVolumeWatcher(system, others);

        watcher.stop();
        watcher.start(() -> {
        });
        watcher.stop();
        watcher.stop();

        assertEquals("the group side is let go once", 1, others.unsubscriptions);
        assertTrue(system.stops >= 1);
    }

    @Test
    public void aSecondStartLetsGoOfTheFirstSubscription() {
        FakeSystem system = new FakeSystem();
        FakeOthers others = new FakeOthers();
        RoutedVolumeWatcher watcher = new RoutedVolumeWatcher(system, others);

        watcher.start(() -> {
        });
        watcher.start(() -> {
        });

        assertEquals(2, others.subscriptions);
        assertEquals(1, others.unsubscriptions);
    }
}
