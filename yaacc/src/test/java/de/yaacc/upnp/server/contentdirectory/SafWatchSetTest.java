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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Which SAF folders are checked for changes (issue #299). Plain JVM, fake clock. */
public class SafWatchSetTest {

    private static final long TTL = 15 * 60_000L;

    private final AtomicLong now = new AtomicLong(1_000);
    private final SafWatchSet watchSet = new SafWatchSet(4, TTL, now::get);

    @Test
    public void theRootsAreAlwaysWatchedInAlphabeticalOrder() {
        assertEquals(Arrays.asList("r1", "r2"), watchSet.watched(Arrays.asList("r2", "r1")));
    }

    @Test
    public void noRootsAndNothingBrowsedWatchesNothing() {
        assertEquals(Collections.emptyList(), watchSet.watched(Collections.emptyList()));
    }

    @Test
    public void aBrowsedFolderIsWatchedAfterTheRootsNewestFirst() {
        watchSet.noteBrowsed("sub1");
        now.addAndGet(1_000);
        watchSet.noteBrowsed("sub2");

        assertEquals(Arrays.asList("r1", "sub2", "sub1"), watchSet.watched(Collections.singletonList("r1")));
    }

    @Test
    public void browsingAFolderAgainMovesItToTheFront() {
        watchSet.noteBrowsed("sub1");
        watchSet.noteBrowsed("sub2");
        watchSet.noteBrowsed("sub1");

        assertEquals(Arrays.asList("sub1", "sub2"), watchSet.watched(Collections.emptyList()));
    }

    @Test
    public void aBrowsedRootIsListedOnce() {
        watchSet.noteBrowsed("r1");

        assertEquals(Collections.singletonList("r1"), watchSet.watched(Collections.singletonList("r1")));
    }

    @Test
    public void aBrowsedFolderDropsOutFifteenMinutesAfterItsLastBrowse() {
        watchSet.noteBrowsed("sub1");

        now.addAndGet(TTL - 1);
        assertEquals(Collections.singletonList("sub1"), watchSet.watched(Collections.emptyList()));

        now.addAndGet(1);
        assertEquals(Collections.emptyList(), watchSet.watched(Collections.emptyList()));
    }

    @Test
    public void aBrowseRestartsTheTimeOfAFolder() {
        watchSet.noteBrowsed("sub1");
        now.addAndGet(TTL - 1);
        watchSet.noteBrowsed("sub1");
        now.addAndGet(TTL - 1);

        assertEquals(Collections.singletonList("sub1"), watchSet.watched(Collections.emptyList()));
    }

    @Test
    public void neverMoreThanMaxFoldersRootsFirst() {
        for (int i = 1; i <= 10; i++) {
            watchSet.noteBrowsed("sub" + i);
            now.addAndGet(10);
        }

        // 2 roots, so the 2 newest browsed folders
        assertEquals(Arrays.asList("r1", "r2", "sub10", "sub9"), watchSet.watched(Arrays.asList("r1", "r2")));
    }

    @Test
    public void moreRootsThanTheMaximumAreCutAlphabetically() {
        List<String> roots = new ArrayList<>();
        for (int i = 6; i >= 1; i--) {
            roots.add("r" + i);
        }
        watchSet.noteBrowsed("sub1");

        assertEquals(Arrays.asList("r1", "r2", "r3", "r4"), watchSet.watched(roots));
    }

    @Test
    public void resetForgetsTheBrowsedFolders() {
        watchSet.noteBrowsed("sub1");

        watchSet.reset();

        assertEquals(Collections.singletonList("r1"), watchSet.watched(Collections.singletonList("r1")));
    }

    @Test
    public void aNullFolderIsIgnored() {
        watchSet.noteBrowsed(null);

        assertEquals(Collections.emptyList(), watchSet.watched(Collections.emptyList()));
    }
}
