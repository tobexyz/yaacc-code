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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** The pass and the bump decisions of the SAF change check (issue #299), with a fake provider, bump and clock. */
public class SafChangeMonitorTest {

    private static final String ROOT = "content://p/tree/lib/document/lib";
    private static final String SUB = "content://p/tree/lib/document/lib%2Fsub";

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Map<String, SafDirectory> provider = new HashMap<>();
    private final List<String> listed = new ArrayList<>();
    private final List<Long> flushRequests = new ArrayList<>();
    private final AtomicInteger bumps = new AtomicInteger();
    private boolean bumpPossible = true;
    private final SafChangeMonitor monitor = new SafChangeMonitor(
            this::list,
            () -> {
                if (bumpPossible) {
                    bumps.incrementAndGet();
                }
                return bumpPossible;
            },
            flushRequests::add,
            now::get);

    private SafDirectory list(String folder) throws Exception {
        listed.add(folder);
        SafDirectory directory = provider.get(folder);
        if (directory == null) {
            throw new IOException("provider has no " + folder);
        }
        return directory;
    }

    private static SafDirectory listing(String... names) {
        List<SafDirectoryEntry> entries = new ArrayList<>();
        for (String name : names) {
            entries.add(new SafDirectoryEntry(name, "doc:" + name, "video/mp4", 1, 10, false));
        }
        return SafDirectory.listed("content://p", true, entries);
    }

    private void runPass(String... roots) {
        monitor.runPass(Arrays.asList(roots));
    }

    // --- passes

    @Test
    public void theFirstPassIsOnlyABaselineNoBump() {
        provider.put(ROOT, listing("a.mp4"));

        runPass(ROOT);

        assertEquals(0, bumps.get());
    }

    @Test
    public void aNewFileInTheRootBetweenTwoPassesBumpsOnce() {
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);

        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        runPass(ROOT);

        assertEquals(1, bumps.get());
        runPass(ROOT);
        assertEquals("nothing changed since", 1, bumps.get());
    }

    @Test
    public void aTorrentFolderWithManyFilesIsOneBump() {
        provider.put(ROOT, listing("old.mp4"));
        provider.put(SUB, listing("1.mp4"));
        monitor.onBrowse(SUB, provider.get(SUB), false);
        runPass(ROOT);

        provider.put(ROOT, listing("old.mp4", "torrent"));
        provider.put(SUB, listing("1.mp4", "2.mp4", "3.mp4"));
        runPass(ROOT);

        assertEquals("both folders changed in one pass", 1, bumps.get());
    }

    @Test
    public void everyPassListsTheRootsAndTheBrowsedFoldersFromTheProvider() {
        provider.put(ROOT, listing("a.mp4"));
        provider.put(SUB, listing("1.mp4"));
        monitor.onBrowse(SUB, provider.get(SUB), false);

        runPass(ROOT);

        assertEquals(Arrays.asList(ROOT, SUB), listed);
    }

    @Test
    public void aFolderThatCannotBeListedDoesNotStopThePassOrBump() {
        provider.put(ROOT, listing("a.mp4"));
        // SUB is not in the provider: listing throws
        monitor.onBrowse(SUB, listing("1.mp4"), false);
        runPass(ROOT);

        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        runPass(ROOT);

        assertEquals(1, bumps.get());
    }

    @Test
    public void aFailedListingIsNoChange() {
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);

        provider.put(ROOT, SafDirectory.listingFailed(ROOT, true));
        runPass(ROOT);
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);

        assertEquals(0, bumps.get());
    }

    @Test
    public void aFolderThatIsNoLongerWatchedStartsWithANewBaseline() {
        provider.put(ROOT, listing("a.mp4"));
        provider.put("content://p/tree/other/document/other", listing("x.mp4"));
        runPass(ROOT, "content://p/tree/other/document/other");

        // the second root is unselected, changes while not watched, and is selected again
        runPass(ROOT);
        provider.put("content://p/tree/other/document/other", listing("x.mp4", "y.mp4"));
        runPass(ROOT, "content://p/tree/other/document/other");

        assertEquals(0, bumps.get());
    }

    @Test
    public void resetForgetsEverythingSeen() {
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);

        monitor.reset();
        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        runPass(ROOT);

        assertEquals("the new listing is a baseline", 0, bumps.get());
        provider.put(ROOT, listing("a.mp4", "b.mp4", "c.mp4"));
        runPass(ROOT);
        assertEquals(1, bumps.get());
    }

    // --- the minimum interval and the postponed bump

    @Test
    public void aChangeRightAfterABumpIsPostponedToTheEndOfTheIntervalNotDropped() {
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);
        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        runPass(ROOT);
        assertEquals(1, bumps.get());
        flushRequests.clear();

        now.addAndGet(5_000);
        provider.put(ROOT, listing("a.mp4", "b.mp4", "c.mp4"));
        runPass(ROOT);

        assertEquals("not yet", 1, bumps.get());
        assertEquals("asks to come back when the interval is over", Collections.singletonList(10_000L), flushRequests);

        now.addAndGet(10_000);
        assertEquals(-1, monitor.flush());
        assertEquals(2, bumps.get());
    }

    @Test
    public void anEarlyFlushDoesNotBumpAndAsksAgain() {
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);
        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        runPass(ROOT);
        now.addAndGet(1_000);
        monitor.onBrowse(SUB, listing("1.mp4"), false);
        monitor.onBrowse(SUB, listing("1.mp4", "2.mp4"), false);
        flushRequests.clear();

        long next = monitor.flush();

        assertEquals(14_000L, next);
        assertEquals(1, bumps.get());
        assertEquals(Collections.singletonList(14_000L), flushRequests);
    }

    @Test
    public void aBumpThatIsNotPossibleYetKeepsTheChangeForTheNextPass() {
        bumpPossible = false;
        provider.put(ROOT, listing("a.mp4"));
        runPass(ROOT);
        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        runPass(ROOT);
        assertEquals(0, bumps.get());

        bumpPossible = true;
        runPass(ROOT);

        assertEquals("the change is still reported, without a new difference", 1, bumps.get());
    }

    @Test
    public void aBumpThatThrowsIsKeptForTheNextPass() {
        SafChangeMonitor throwing = new SafChangeMonitor(this::list, () -> {
            throw new IllegalStateException("service lock timeout");
        }, flushRequests::add, now::get);
        provider.put(ROOT, listing("a.mp4"));
        throwing.runPass(Collections.singletonList(ROOT));

        provider.put(ROOT, listing("a.mp4", "b.mp4"));
        throwing.runPass(Collections.singletonList(ROOT)); // must not throw

        // no exception from a later flush either; the change stays pending (a bump is retried by every pass)
        assertEquals(-1, throwing.flush());
    }

    // --- Browse reports

    @Test
    public void aBrowseThatReloadedAChangedFolderAsksForABumpWithoutAnExtraQuery() {
        monitor.onBrowse(SUB, listing("1.mp4"), false);

        monitor.onBrowse(SUB, listing("1.mp4", "2.mp4"), false);

        assertEquals(Collections.singletonList(0L), flushRequests);
        assertTrue(listed.isEmpty());
        assertEquals("the bump itself is done by flush() on the runner's thread", 0, bumps.get());
        monitor.flush();
        assertEquals(1, bumps.get());
    }

    @Test
    public void severalBrowseReportsBeforeTheFlushAskForOneFlush() {
        monitor.onBrowse(SUB, listing("1.mp4"), false);
        monitor.onBrowse(SUB, listing("1.mp4", "2.mp4"), false);
        monitor.onBrowse(SUB, listing("1.mp4", "2.mp4", "3.mp4"), false);

        assertEquals(1, flushRequests.size());
    }

    @Test
    public void aBrowseThatGotTheCachedListingOfAKnownFolderIsNotCompared() {
        monitor.onBrowse(SUB, listing("1.mp4"), false);

        monitor.onBrowse(SUB, listing("1.mp4", "2.mp4"), true);

        assertTrue(flushRequests.isEmpty());
    }

    @Test
    public void aBrowseThatGotTheCachedListingOfAnUnknownFolderMakesItsBaseline() {
        // e.g. the listing was cached by the server configuration screen
        monitor.onBrowse(SUB, listing("1.mp4"), true);

        monitor.onBrowse(SUB, listing("1.mp4", "2.mp4"), false);

        assertEquals(1, flushRequests.size());
    }

    @Test
    public void aFailedListingOfABrowseIsNoChange() {
        monitor.onBrowse(SUB, listing("1.mp4"), false);

        monitor.onBrowse(SUB, SafDirectory.listingFailed(SUB, true), false);

        assertTrue(flushRequests.isEmpty());
    }

    @Test
    public void aFolderThatWasBrowsedIsCheckedByThePassesAndNotAfterFifteenMinutes() {
        provider.put(SUB, listing("1.mp4"));
        monitor.onBrowse(SUB, provider.get(SUB), false);

        runPass();
        assertEquals(Collections.singletonList(SUB), listed);

        listed.clear();
        now.addAndGet(SafChangeMonitor.BROWSED_TTL_MILLIS);
        runPass();
        assertTrue(listed.isEmpty());
    }

    // --- pure decisions

    @Test
    public void pauseAfterAPassIsTheIntervalAndLongerAfterASlowPass() {
        assertEquals(30_000L, SafChangeMonitor.pauseAfterPass(0));
        assertEquals(30_000L, SafChangeMonitor.pauseAfterPass(9_999));
        assertEquals(30_000L, SafChangeMonitor.pauseAfterPass(10_000));
        assertEquals(30_003L, SafChangeMonitor.pauseAfterPass(10_001));
        assertEquals(90_000L, SafChangeMonitor.pauseAfterPass(30_000));
        assertEquals(30_000L, SafChangeMonitor.pauseAfterPass(-5));
    }

    @Test
    public void theCheckRunsOnlyWhileAServedSafFolderIsSelected() {
        assertTrue(SafChangeMonitor.shouldWatch(true, true, true, 1));
        assertFalse("no folder selected", SafChangeMonitor.shouldWatch(true, true, true, 0));
        assertFalse("server off", SafChangeMonitor.shouldWatch(false, true, true, 2));
        assertFalse("content provider off", SafChangeMonitor.shouldWatch(true, false, true, 2));
        assertFalse("SAF folders not served", SafChangeMonitor.shouldWatch(true, true, false, 2));
    }

    @Test
    public void theDefaultsAreTheDocumentedOnes() {
        assertEquals(30_000L, SafChangeMonitor.PASS_INTERVAL_MILLIS);
        assertEquals(15_000L, SafChangeMonitor.MIN_BUMP_INTERVAL_MILLIS);
        assertEquals(15 * 60_000L, SafChangeMonitor.BROWSED_TTL_MILLIS);
        assertEquals(16, SafChangeMonitor.MAX_WATCHED_FOLDERS);
    }
}
