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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Behavior of the player-list change listener behind
 * {@link PlayerService#addPlayerListChangeListener}. {@code PlayerService} is an
 * Android {@code Service} and can't run on the plain JVM, so these tests drive the
 * {@link PlayerListChangeNotifier} it delegates to, mirroring the service's call
 * sequences.
 */
public class PlayerServiceListenerTest {

    private final Map<Integer, Player> players = new HashMap<>();
    private final List<Runnable> pendingDeliveries = new ArrayList<>();
    private PlayerListChangeNotifier notifier;

    @Before
    public void setUp() {
        notifier = new PlayerListChangeNotifier(Runnable::run);
    }

    @Test
    public void addedFiresOnceWhenSamePlayerIsAddedTwice() {
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);
        Player player = player(7);

        notifier.putAndNotify(players, player);
        notifier.putAndNotify(players, player);

        assertEquals(List.of("added:7"), listener.events);
    }

    @Test
    public void removedFiresOnceWhenOnDestroyCallsBackIntoRemovePath() {
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);
        Player player = player(7);
        // Mirrors AbstractPlayer.onDestroy() calling PlayerService.removePlayer(this).
        doAnswer(invocation -> notifier.removeAndNotify(players, player)).when(player).onDestroy();
        notifier.putAndNotify(players, player);

        // Mirrors PlayerService.shutdown(Player): remove, then onDestroy().
        notifier.removeAndNotify(players, player);
        player.onDestroy();

        assertEquals(List.of("added:7", "removed:7"), listener.events);
        assertTrue(players.isEmpty());
    }

    @Test
    public void removingUnknownPlayerDoesNotFire() {
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);

        assertFalse(notifier.removeAndNotify(players, player(42)));

        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void removedListenerStopsFiring() {
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);
        notifier.putAndNotify(players, player(1));

        Player first = players.get(1);
        notifier.removeListener(listener);
        notifier.putAndNotify(players, player(2));
        notifier.removeAndNotify(players, first);

        assertEquals(List.of("added:1"), listener.events);
    }

    @Test
    public void listenerRegisteredTwiceFiresOnce() {
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);
        notifier.addListener(listener);

        notifier.putAndNotify(players, player(1));

        assertEquals(List.of("added:1"), listener.events);
    }

    @Test
    public void listenerUnregisteringItselfMidCallbackDoesNotSkipOthers() {
        RecordingListener first = new RecordingListener();
        RecordingListener selfRemoving = new RecordingListener() {
            @Override
            public void onPlayerAdded(Player player) {
                super.onPlayerAdded(player);
                notifier.removeListener(this);
            }
        };
        RecordingListener last = new RecordingListener();
        notifier.addListener(first);
        notifier.addListener(selfRemoving);
        notifier.addListener(last);

        notifier.putAndNotify(players, player(1));
        notifier.putAndNotify(players, player(2));

        assertEquals(List.of("added:1", "added:2"), first.events);
        assertEquals(List.of("added:1"), selfRemoving.events);
        assertEquals(List.of("added:1", "added:2"), last.events);
    }

    @Test
    public void notificationIsPostedAndDecidedAtMutation() {
        notifier = new PlayerListChangeNotifier(queuingExecutor());
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);
        Player player = player(3);

        notifier.putAndNotify(players, player);
        notifier.removeAndNotify(players, player);

        // Nothing delivered until the poster runs; the map changed immediately.
        assertTrue(listener.events.isEmpty());
        assertTrue(players.isEmpty());
        runPendingDeliveries();
        assertEquals(List.of("added:3", "removed:3"), listener.events);
        assertSame(player, listener.lastPlayer);
    }

    @Test
    public void listenerUnregisteredBeforeQueuedDeliveryIsNotCalled() {
        notifier = new PlayerListChangeNotifier(queuingExecutor());
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);

        notifier.putAndNotify(players, player(1));
        notifier.removeListener(listener);
        runPendingDeliveries();

        assertTrue(listener.events.isEmpty());
    }

    // --- same-id replacement (review cycle 1 of Group 2) ---------------------

    @Test
    public void replacingPlayerWithSameIdFiresRemovedForOldThenAddedForNew() {
        RecordingListener listener = new RecordingListener();
        notifier.addListener(listener);
        Player oldMulti = player(4);
        Player newMulti = player(4);
        notifier.putAndNotify(players, oldMulti);

        notifier.putAndNotify(players, newMulti);

        assertEquals(List.of("added:4", "removed:4", "added:4"), listener.events);
        assertEquals(List.of(oldMulti, oldMulti, newMulti), listener.players);
        assertSame(newMulti, players.get(4));
    }

    @Test
    public void removingReplacedPlayerKeepsItsReplacementAndDoesNotFire() {
        RecordingListener listener = new RecordingListener();
        Player oldMulti = player(4);
        Player newMulti = player(4);
        notifier.putAndNotify(players, oldMulti);
        notifier.putAndNotify(players, newMulti);
        notifier.addListener(listener);

        // The orphaned old player exits later: shutdown, then onDestroy's removePlayer.
        assertFalse(notifier.removeAndNotify(players, oldMulti));
        assertFalse(notifier.removeAndNotify(players, oldMulti));

        assertSame(newMulti, players.get(4));
        assertTrue(listener.events.isEmpty());
        assertTrue(notifier.creationSequence(newMulti) > 0);
    }

    // --- creation sequence --------------------------------------------------

    @Test
    public void creationSequenceFollowsAddOrderNotPlayerId() {
        Player remote = player(900);
        Player local = player(3);

        notifier.putAndNotify(players, remote);
        notifier.putAndNotify(players, local);

        assertTrue(notifier.creationSequence(remote) > 0);
        assertTrue(notifier.creationSequence(local) > notifier.creationSequence(remote));
    }

    @Test
    public void reAddingSameInstanceKeepsItsSequence() {
        Player player = player(7);
        notifier.putAndNotify(players, player);
        long first = notifier.creationSequence(player);
        notifier.putAndNotify(players, player(8));

        notifier.putAndNotify(players, player);

        assertEquals(first, notifier.creationSequence(player));
    }

    @Test
    public void removedOrReplacedOrUnknownPlayerHasNoSequence() {
        Player removed = player(1);
        Player replaced = player(4);
        notifier.putAndNotify(players, removed);
        notifier.putAndNotify(players, replaced);
        notifier.removeAndNotify(players, removed);
        notifier.putAndNotify(players, player(4));

        assertEquals(0, notifier.creationSequence(removed));
        assertEquals(0, notifier.creationSequence(replaced));
        assertEquals(0, notifier.creationSequence(player(5)));
        assertEquals(0, notifier.creationSequence(null));
    }

    @Test
    public void playerAddedAgainAfterRemovalGetsANewerSequence() {
        Player player = player(7);
        notifier.putAndNotify(players, player);
        long first = notifier.creationSequence(player);
        notifier.removeAndNotify(players, player);

        notifier.putAndNotify(players, player);

        assertTrue(notifier.creationSequence(player) > first);
    }

    private Executor queuingExecutor() {
        return pendingDeliveries::add;
    }

    private void runPendingDeliveries() {
        List<Runnable> toRun = new ArrayList<>(pendingDeliveries);
        pendingDeliveries.clear();
        toRun.forEach(Runnable::run);
    }

    private static Player player(int id) {
        Player player = mock(Player.class);
        when(player.getId()).thenReturn(id);
        return player;
    }

    private static class RecordingListener implements PlayerListChangeNotifier.Listener {
        final List<String> events = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        Player lastPlayer;

        @Override
        public void onPlayerAdded(Player player) {
            events.add("added:" + player.getId());
            players.add(player);
            lastPlayer = player;
        }

        @Override
        public void onPlayerRemoved(Player player) {
            events.add("removed:" + player.getId());
            players.add(player);
            lastPlayer = player;
        }
    }
}
