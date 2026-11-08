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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

/**
 * Tests for {@link ShareKeyTracker}: which share keys a player used, remembered independently of
 * the player's item list (the list is cleared when the player is destroyed, before the "removed"
 * notification reaches the main thread).
 */
public class ShareKeyTrackerTest {

    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBB";

    /** Players are plain objects: tracked by identity, never by equals/id. */
    private static final class FakePlayer {
    }

    @Test
    public void addThenItemsClearedThenRemovedStillReleasesTheShare() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer player = new FakePlayer();

        tracker.onPlayerAdded(player, Set.of(KEY_A));
        // the player was destroyed: its item list is empty now, the glue refreshes with nothing
        tracker.refresh(player, Collections.emptySet());

        assertEquals(Set.of(KEY_A), tracker.onPlayerRemoved(player));
    }

    @Test
    public void itemsSetAfterTheAddAreRememberedByRefresh() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer player = new FakePlayer();

        tracker.onPlayerAdded(player, Collections.emptySet());
        tracker.refresh(player, Set.of(KEY_A));

        assertEquals(Set.of(KEY_A), tracker.keysOf(player));
        assertEquals(Set.of(KEY_A), tracker.onPlayerRemoved(player));
    }

    @Test
    public void refreshAddsKeysAndNeverForgetsEarlierOnes() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer player = new FakePlayer();
        tracker.onPlayerAdded(player, Set.of(KEY_A));

        tracker.refresh(player, Set.of(KEY_B));

        assertEquals(Set.of(KEY_A, KEY_B), tracker.onPlayerRemoved(player));
    }

    @Test
    public void aKeyStillUsedByAnotherTrackedPlayerIsNotReleased() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer first = new FakePlayer();
        FakePlayer second = new FakePlayer();
        tracker.onPlayerAdded(first, Set.of(KEY_A, KEY_B));
        tracker.onPlayerAdded(second, Set.of(KEY_A));

        assertEquals(Set.of(KEY_B), tracker.onPlayerRemoved(first));
        assertEquals(Set.of(KEY_A), tracker.onPlayerRemoved(second));
    }

    @Test
    public void removingTwiceReleasesOnlyOnce() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer player = new FakePlayer();
        tracker.onPlayerAdded(player, Set.of(KEY_A));

        assertEquals(Set.of(KEY_A), tracker.onPlayerRemoved(player));
        assertTrue(tracker.onPlayerRemoved(player).isEmpty());
    }

    @Test
    public void anUnknownPlayerReleasesNothing() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();

        assertTrue(tracker.onPlayerRemoved(new FakePlayer()).isEmpty());
        assertTrue(tracker.onPlayerRemoved(null).isEmpty());
    }

    @Test
    public void aPlayerWithoutKeysIsNotRemembered() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer player = new FakePlayer();

        tracker.onPlayerAdded(player, Collections.emptySet());
        tracker.refresh(player, null);

        assertEquals(0, tracker.trackedPlayers());
    }

    @Test
    public void playersAreTrackedByIdentityNotByEquality() {
        // a replaced player may have the same id (and an equal-looking state) as its successor
        final class Equal {
            @Override
            public boolean equals(Object o) {
                return o instanceof Equal;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        }
        ShareKeyTracker<Equal> tracker = new ShareKeyTracker<>();
        Equal old = new Equal();
        Equal replacement = new Equal();
        tracker.onPlayerAdded(old, Set.of(KEY_A));
        tracker.onPlayerAdded(replacement, Set.of(KEY_B));

        assertEquals(Set.of(KEY_A), tracker.onPlayerRemoved(old));
        assertEquals(Set.of(KEY_B), tracker.keysOf(replacement));
    }

    @Test
    public void malformedKeysAreIgnored() {
        ShareKeyTracker<FakePlayer> tracker = new ShareKeyTracker<>();
        FakePlayer player = new FakePlayer();

        tracker.onPlayerAdded(player, Set.of("short", KEY_A));

        assertEquals(Set.of(KEY_A), tracker.keysOf(player));
    }

    @Test
    public void keyOfProxyPathTakesTheLastSegmentOfAProxyPath() {
        assertEquals(KEY_A, ShareKeyTracker.keyOfProxyPath("/proxy/" + KEY_A, "proxy"));
        assertEquals(KEY_A, ShareKeyTracker.keyOfProxyPath("/proxy/some:device-id/" + KEY_A, "proxy"));
    }

    @Test
    public void keyOfProxyPathRejectsOtherPathsAndMalformedKeys() {
        assertNull(ShareKeyTracker.keyOfProxyPath(null, "proxy"));
        assertNull(ShareKeyTracker.keyOfProxyPath("", "proxy"));
        assertNull(ShareKeyTracker.keyOfProxyPath("/res/" + KEY_A, "proxy"));
        assertNull(ShareKeyTracker.keyOfProxyPath("/proxyx/" + KEY_A, "proxy"));
        assertNull(ShareKeyTracker.keyOfProxyPath("/proxy/short", "proxy"));
        assertNull(ShareKeyTracker.keyOfProxyPath("/proxy/", "proxy"));
        assertNull(ShareKeyTracker.keyOfProxyPath("/proxy/" + KEY_A + "/", "proxy"));
    }
}
