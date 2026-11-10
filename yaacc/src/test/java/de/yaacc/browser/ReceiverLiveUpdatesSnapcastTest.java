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
import java.util.List;
import java.util.function.Consumer;

import de.yaacc.player.PlayerListChangeNotifier;

/**
 * The Snapcast source inside {@link ReceiverLiveUpdates}: started and stopped with everything else, a change of the
 * groups rebuilds the rows while the updates run and never after they stopped. Plain JVM with a fake world.
 */
public class ReceiverLiveUpdatesSnapcastTest {

    private final List<String> calls = new ArrayList<>();

    private final ReceiverLiveUpdates.Target target = new ReceiverLiveUpdates.Target() {
        @Override
        public void refresh() {
            calls.add("refresh");
        }

        @Override
        public void onStatusChanged(RendererStatus status) {
        }

        @Override
        public void onGroupStateChanged(String udn) {
        }

        @Override
        public void refreshLiveState() {
        }

        @Override
        public void startLiveVolume() {
        }

        @Override
        public void stopLiveVolume() {
        }
    };

    private static final class FakeSources implements ReceiverLiveUpdates.Sources {
        Runnable snapcastCallback;
        int snapcastStarts;
        int snapcastStops;
        final List<Runnable> scheduled = new ArrayList<>();

        @Override
        public void subscribeStatus(RendererStatusMonitor.StatusListener listener) {
        }

        @Override
        public void unsubscribeStatus(RendererStatusMonitor.StatusListener listener) {
        }

        @Override
        public void startGroupFeed(Consumer<String> onChanged) {
        }

        @Override
        public void stopGroupFeed() {
        }

        @Override
        public void addPlayerListener(PlayerListChangeNotifier.Listener listener) {
        }

        @Override
        public void removePlayerListener(PlayerListChangeNotifier.Listener listener) {
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMillis) {
            scheduled.add(runnable);
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            scheduled.remove(runnable);
        }

        @Override
        public void startSnapcast(Runnable onChanged) {
            snapcastStarts++;
            snapcastCallback = onChanged;
        }

        @Override
        public void stopSnapcast() {
            snapcastStops++;
            snapcastCallback = null;
        }
    }

    private FakeSources sources;
    private ReceiverLiveUpdates live;

    @Before
    public void setUp() {
        sources = new FakeSources();
        live = new ReceiverLiveUpdates(sources, target);
    }

    @Test
    public void startFollowsTheGroupsOnceAndStopLetsGo() {
        live.start(false);
        live.start(true);
        assertEquals(1, sources.snapcastStarts);

        live.stop();
        live.stop();

        assertEquals(1, sources.snapcastStops);
    }

    @Test
    public void aChangeOfTheGroupsRebuildsTheRowsWhileRunning() {
        live.start(false);
        calls.clear();

        sources.snapcastCallback.run();

        assertEquals(List.of("refresh"), calls);
    }

    @Test
    public void aLateChangeAfterStopIsIgnored() {
        live.start(false);
        Runnable callback = sources.snapcastCallback;
        live.stop();
        calls.clear();

        callback.run();

        assertTrue(calls.isEmpty());
        assertFalse(live.isRunning());
    }

    @Test
    public void aRestartFollowsAgain() {
        live.start(false);
        live.stop();

        live.start(false);

        assertEquals(2, sources.snapcastStarts);
    }

    @Test
    public void aSourceWithoutSnapcastSupportStillWorks() {
        ReceiverLiveUpdates.Sources plain = new ReceiverLiveUpdates.Sources() {
            @Override
            public void subscribeStatus(RendererStatusMonitor.StatusListener listener) {
            }

            @Override
            public void unsubscribeStatus(RendererStatusMonitor.StatusListener listener) {
            }

            @Override
            public void startGroupFeed(Consumer<String> onChanged) {
            }

            @Override
            public void stopGroupFeed() {
            }

            @Override
            public void addPlayerListener(PlayerListChangeNotifier.Listener listener) {
            }

            @Override
            public void removePlayerListener(PlayerListChangeNotifier.Listener listener) {
            }

            @Override
            public void postDelayed(Runnable runnable, long delayMillis) {
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
            }
        };
        ReceiverLiveUpdates updates = new ReceiverLiveUpdates(plain, target);

        updates.start(false);
        updates.stop();

        assertFalse(updates.isRunning());
    }
}
