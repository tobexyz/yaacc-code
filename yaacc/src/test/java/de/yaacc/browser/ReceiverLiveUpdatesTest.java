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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.yaacc.player.PlayerListChangeNotifier;

/**
 * The live subscriptions of a receiver list (device sheet, receivers on the top-level page): started once, dropped completely
 * on stop, nothing is delivered after stop, a stale list is rebuilt on start. Plain JVM with a fake world.
 */
public class ReceiverLiveUpdatesTest {

    private final List<String> calls = new ArrayList<>();

    private final ReceiverLiveUpdates.Target target = new ReceiverLiveUpdates.Target() {
        @Override
        public void refresh() {
            calls.add("refresh");
        }

        @Override
        public void onStatusChanged(RendererStatus status) {
            calls.add("status");
        }

        @Override
        public void onGroupStateChanged(String udn) {
            calls.add("group:" + udn);
        }

        @Override
        public void refreshLiveState() {
            calls.add("poll");
        }

        @Override
        public void startLiveVolume() {
            calls.add("volume on");
        }

        @Override
        public void stopLiveVolume() {
            calls.add("volume off");
        }
    };

    /** The fake world: remembers who is subscribed and what is scheduled. */
    private static final class FakeSources implements ReceiverLiveUpdates.Sources {
        final List<RendererStatusMonitor.StatusListener> statusListeners = new ArrayList<>();
        final List<PlayerListChangeNotifier.Listener> playerListeners = new ArrayList<>();
        Consumer<String> groupCallback;
        final List<Runnable> scheduled = new ArrayList<>();
        int groupStarts;
        int statusSubscribes;
        int groupStops;

        @Override
        public void subscribeStatus(RendererStatusMonitor.StatusListener listener) {
            statusSubscribes++;
            if (!statusListeners.contains(listener)) {
                statusListeners.add(listener); // like the real monitor: one registration per listener
            }
        }

        @Override
        public void unsubscribeStatus(RendererStatusMonitor.StatusListener listener) {
            statusListeners.remove(listener);
        }

        @Override
        public void startGroupFeed(Consumer<String> onChanged) {
            groupStarts++;
            groupCallback = onChanged;
        }

        @Override
        public void stopGroupFeed() {
            groupStops++;
            groupCallback = null;
        }

        @Override
        public void addPlayerListener(PlayerListChangeNotifier.Listener listener) {
            playerListeners.add(listener);
        }

        @Override
        public void removePlayerListener(PlayerListChangeNotifier.Listener listener) {
            playerListeners.remove(listener);
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMillis) {
            scheduled.add(runnable);
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            scheduled.remove(runnable);
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
    public void startSubscribesEverythingOnceAndTurnsTheVolumeOn() {
        live.start(false);

        assertEquals(1, sources.statusListeners.size());
        assertEquals(1, sources.playerListeners.size());
        assertEquals(1, sources.groupStarts);
        assertEquals(1, sources.scheduled.size());
        assertEquals(List.of("volume on"), calls);
        assertTrue(live.isRunning());
    }

    @Test
    public void startingTwiceDoesNotSubscribeTwice() {
        live.start(false);
        live.start(true);

        assertEquals(1, sources.statusListeners.size());
        assertEquals(1, sources.playerListeners.size());
        assertEquals(1, sources.groupStarts);
        assertEquals(1, sources.scheduled.size());
        assertFalse(calls.contains("refresh"));
    }

    @Test
    public void aStaleListIsRebuiltOnStartAndAFreshOneIsNot() {
        live.start(true);
        assertEquals(List.of("refresh", "volume on"), calls);

        calls.clear();
        live.stop();
        calls.clear();
        live.start(false);
        assertFalse(calls.contains("refresh"));
    }

    @Test
    public void stopDropsEverything() {
        live.start(false);
        calls.clear();

        live.stop();

        assertTrue(sources.statusListeners.isEmpty());
        assertTrue(sources.playerListeners.isEmpty());
        assertEquals(1, sources.groupStops);
        assertTrue(sources.scheduled.isEmpty());
        assertEquals(List.of("volume off"), calls);
        assertFalse(live.isRunning());
    }

    @Test
    public void stopWithoutStartAndStopTwiceDoNothing() {
        live.stop();
        live.start(false);
        live.stop();
        calls.clear();
        live.stop();

        assertEquals(1, sources.groupStops);
        assertTrue(calls.isEmpty());
    }

    @Test
    public void nothingIsDeliveredAfterStop() {
        live.start(false);
        RendererStatusMonitor.StatusListener status = sources.statusListeners.get(0);
        PlayerListChangeNotifier.Listener players = sources.playerListeners.get(0);
        Consumer<String> group = sources.groupCallback;
        Runnable poll = sources.scheduled.get(0);
        live.stop();
        calls.clear();

        status.onStatusChanged(null);
        players.onPlayerAdded(null);
        players.onPlayerRemoved(null);
        group.accept("uuid");
        poll.run();

        assertTrue(calls.toString(), calls.isEmpty());
        assertTrue("a poll after stop must not schedule itself again", sources.scheduled.isEmpty());
    }

    @Test
    public void whileRunningTheCallbacksReachTheTarget() {
        live.start(false);
        calls.clear();

        sources.statusListeners.get(0).onStatusChanged(null);
        sources.groupCallback.accept("uuid-1");
        sources.playerListeners.get(0).onPlayerAdded(null);
        sources.playerListeners.get(0).onPlayerRemoved(null);

        assertEquals(List.of("status", "group:uuid-1", "refresh", "refresh"), calls);
    }

    @Test
    public void thePollRefreshesTheLiveStateAndSchedulesItselfAgain() {
        live.start(false);
        calls.clear();
        Runnable poll = sources.scheduled.get(0);

        poll.run();

        assertEquals(List.of("poll"), calls);
        assertEquals("the next poll is scheduled", 2, sources.scheduled.size());
        assertNotNull(sources.scheduled.get(1));
    }

    @Test
    public void restartSubscribesAgain() {
        live.start(false);
        live.stop();
        calls.clear();

        live.start(true);

        assertEquals(1, sources.statusListeners.size());
        assertEquals(1, sources.playerListeners.size());
        assertEquals(2, sources.groupStarts);
        assertEquals(List.of("refresh", "volume on"), calls);
    }

    @Test
    public void receiversThatCameAfterTheStartAreWatchedToo() {
        live.start(false);
        assertEquals(1, sources.statusSubscribes);
        assertEquals(1, sources.groupStarts);

        live.devicesChanged();

        // the monitor and the group feed took the receivers of the start as a snapshot: they get the current ones
        assertEquals(2, sources.statusSubscribes);
        assertEquals(1, sources.statusListeners.size());
        assertEquals(2, sources.groupStarts);
        assertEquals(1, sources.playerListeners.size());
        assertEquals(1, sources.scheduled.size());
    }

    @Test
    public void aGroupFeedThatWasRestartedForNewReceiversStillDeliversToTheTarget() {
        live.start(false);
        live.devicesChanged();
        calls.clear();

        sources.groupCallback.accept("uuid-2");

        assertEquals(List.of("group:uuid-2"), calls);
    }

    @Test
    public void newReceiversAreIgnoredWhileStopped() {
        live.devicesChanged();
        live.start(false);
        live.stop();
        int subscribes = sources.statusSubscribes;
        int starts = sources.groupStarts;

        live.devicesChanged();

        assertEquals(subscribes, sources.statusSubscribes);
        assertEquals(starts, sources.groupStarts);
    }

    @Test
    public void onlyTheFirstReceiversAreWatchedAndListed() {
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            many.add(i);
        }

        List<Integer> watched = ReceiverLiveUpdates.watched(many);

        // a host that announces hundreds of receivers cannot make the page poll all of them
        assertEquals(ReceiverLiveUpdates.MAX_RECEIVERS, watched.size());
        assertEquals(Integer.valueOf(0), watched.get(0));
        assertEquals(Integer.valueOf(ReceiverLiveUpdates.MAX_RECEIVERS - 1), watched.get(watched.size() - 1));
    }

    @Test
    public void fewReceiversAreAllWatchedInOrder() {
        List<String> few = List.of("local", "a", "b");

        assertEquals(few, ReceiverLiveUpdates.watched(few));
        assertTrue(ReceiverLiveUpdates.watched(new ArrayList<String>()).isEmpty());
    }
}
