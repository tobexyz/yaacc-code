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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The share lifetime rules behind {@link ShareLifetimeListener}, driven through the real
 * {@link PlayerListChangeNotifier} with a queue that stands for the main looper (the service
 * posts "added"/"removed" there) and in the order {@code PlayerService} calls it:
 * {@code addPlayer, setItems} and {@code removeAndNotify, onDestroy (items cleared)}.
 */
public class ShareLifetimeListenerTest {

    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBB";

    private final Map<Integer, Player> active = new HashMap<>();
    /** What each fake player "plays": the stand-in for its item list. */
    private final Map<Player, Set<String>> items = new IdentityHashMap<>();
    private final List<Runnable> mainLooper = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private PlayerListChangeNotifier notifier;
    private ShareLifetimeListener listener;
    private int nextId = 1;

    @Before
    public void setUp() {
        notifier = new PlayerListChangeNotifier(mainLooper::add);
        listener = new ShareLifetimeListener(
                player -> new HashSet<>(items.getOrDefault(player, new HashSet<>())),
                () -> new ArrayList<Player>(active.values()),
                key -> events.add("active:" + key),
                key -> events.add("release:" + key));
        notifier.addListener(listener);
    }

    private Player newPlayer() {
        Player player = mock(Player.class);
        when(player.getId()).thenReturn(nextId++);
        // PlayerService.shutdown: removeAndNotify first, then onDestroy clears the item list
        doAnswer(invocation -> {
            items.remove(player);
            return null;
        }).when(player).onDestroy();
        return player;
    }

    private void deliverMainLooper() {
        List<Runnable> copy = new ArrayList<>(mainLooper);
        mainLooper.clear();
        copy.forEach(Runnable::run);
    }

    /** PlayerService.createPlayer: addPlayer, then setItems on the same thread. */
    private Player addPlayerWithItems(String... keys) {
        Player player = newPlayer();
        notifier.putAndNotify(active, player);
        items.put(player, new HashSet<>(List.of(keys)));
        return player;
    }

    /** PlayerService.shutdown(Player): removeAndNotify, then onDestroy. */
    private void shutdown(Player player) {
        notifier.removeAndNotify(active, player);
        player.onDestroy();
    }

    @Test
    public void shutdownReleasesTheShareEvenThoughOnDestroyClearedTheItemsBeforeTheCallback() {
        Player player = addPlayerWithItems(KEY_A);
        deliverMainLooper();
        events.clear();

        shutdown(player);
        // by now the item list is empty, the removed callback has not run yet
        assertTrue(items.get(player) == null);
        deliverMainLooper();

        assertEquals(List.of("release:" + KEY_A), events);
    }

    @Test
    public void releasesTheShareWhenTheAddCallbackRanBeforeTheItemsWereSetAndTheItemsAreClearedLater() {
        // createPlayer ran on a worker: the main looper delivered "added" between addPlayer and setItems
        Player player = newPlayer();
        notifier.putAndNotify(active, player);
        deliverMainLooper();
        items.put(player, new HashSet<>(List.of(KEY_A)));

        shutdown(player);
        deliverMainLooper();

        assertEquals(List.of("release:" + KEY_A), events);
    }

    @Test
    public void markActiveRunsForTheKeysOfAnAddedPlayer() {
        addPlayerWithItems(KEY_A, KEY_B);
        deliverMainLooper();

        assertEquals(2, events.size());
        assertTrue(events.contains("active:" + KEY_A));
        assertTrue(events.contains("active:" + KEY_B));
    }

    @Test
    public void itemsSetAfterTheAddCallbackAreMarkedActiveWhenTheGlueReportsThem() {
        Player player = newPlayer();
        notifier.putAndNotify(active, player);
        deliverMainLooper();
        items.put(player, new HashSet<>(List.of(KEY_A)));

        listener.itemsSet(player);

        assertEquals(List.of("active:" + KEY_A), events);
    }

    @Test
    public void aShareThatAnotherPlayerStillPlaysIsReleasedOnlyWithTheLastPlayer() {
        Player first = addPlayerWithItems(KEY_A);
        Player second = addPlayerWithItems(KEY_A, KEY_B);
        deliverMainLooper();
        events.clear();

        shutdown(first);
        deliverMainLooper();
        assertTrue(events.isEmpty());

        shutdown(second);
        deliverMainLooper();
        assertEquals(2, events.size());
        assertTrue(events.contains("release:" + KEY_A));
        assertTrue(events.contains("release:" + KEY_B));
    }

    @Test
    public void bothPlayersShutDownBeforeTheCallbacksRunStillReleaseEachShareOnce() {
        Player first = addPlayerWithItems(KEY_A);
        Player second = addPlayerWithItems(KEY_A);
        deliverMainLooper();
        events.clear();

        shutdown(first);
        shutdown(second);
        deliverMainLooper();

        assertEquals(List.of("release:" + KEY_A), events);
    }

    @Test
    public void aPlayerWithoutSharesReleasesNothing() {
        Player player = addPlayerWithItems();
        deliverMainLooper();

        shutdown(player);
        deliverMainLooper();

        assertTrue(events.isEmpty());
    }

    @Test
    public void aReplacedPlayerReleasesItsShareAndTheReplacementKeepsItsOwn() {
        // local players have fixed ids: the new instance replaces the old one in the map
        Player old = newPlayer();
        when(old.getId()).thenReturn(99);
        Player replacement = newPlayer();
        when(replacement.getId()).thenReturn(99);
        notifier.putAndNotify(active, old);
        items.put(old, new HashSet<>(List.of(KEY_A)));
        deliverMainLooper();
        events.clear();

        notifier.putAndNotify(active, replacement);
        items.put(replacement, new HashSet<>(List.of(KEY_B)));
        old.onDestroy();
        deliverMainLooper();

        assertTrue(events.contains("release:" + KEY_A));
        assertTrue(events.contains("active:" + KEY_B));
        assertTrue(!events.contains("release:" + KEY_B));
    }

    @Test
    public void aFailingKeyLookupDoesNotBreakTheRemoval() {
        Player player = newPlayer();
        ShareLifetimeListener failing = new ShareLifetimeListener(
                p -> {
                    throw new IllegalStateException("list changed");
                },
                () -> new ArrayList<Player>(active.values()),
                key -> events.add("active:" + key),
                key -> events.add("release:" + key));
        PlayerListChangeNotifier own = new PlayerListChangeNotifier(Runnable::run);
        own.addListener(failing);
        own.putAndNotify(active, player);

        assertTrue(own.removeAndNotify(active, player));
        assertTrue(events.isEmpty());
    }

    @Test
    public void theRemovingHookRunsBeforeTheCallbackAndBeforeOnDestroy() {
        List<String> order = new ArrayList<>();
        PlayerListChangeNotifier own = new PlayerListChangeNotifier(task -> {
            order.add("posted");
            task.run();
        });
        own.addListener(new PlayerListChangeNotifier.Listener() {
            @Override
            public void onPlayerAdded(Player player) {
            }

            @Override
            public void onPlayerRemoving(Player player) {
                order.add("removing");
            }

            @Override
            public void onPlayerRemoved(Player player) {
                order.add("removed");
            }
        });
        Player player = newPlayer();
        own.putAndNotify(active, player);
        order.clear();

        own.removeAndNotify(active, player);

        assertEquals(List.of("removing", "posted", "removed"), order);
    }

    @Test
    public void theRemovingHookDoesNotFireForAPlayerThatWasNotActive() {
        List<String> order = new ArrayList<>();
        PlayerListChangeNotifier own = new PlayerListChangeNotifier(Runnable::run);
        own.addListener(new PlayerListChangeNotifier.Listener() {
            @Override
            public void onPlayerAdded(Player player) {
            }

            @Override
            public void onPlayerRemoving(Player player) {
                order.add("removing");
            }

            @Override
            public void onPlayerRemoved(Player player) {
            }
        });

        own.removeAndNotify(active, newPlayer());

        assertTrue(order.isEmpty());
    }
}
