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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** What counts as a change of a folder, and when a bump is due (issue #299). Plain JVM, fake clock. */
public class SafChangeDetectorTest {

    private static final long MIN_INTERVAL = 15_000;

    private final AtomicLong now = new AtomicLong(100_000);
    private final SafChangeDetector detector = new SafChangeDetector(16, MIN_INTERVAL, now::get);

    private static SafDirectoryEntry file(String name, long modified, long size) {
        return new SafDirectoryEntry(name, "doc:" + name, "video/mp4", modified, size, false);
    }

    private static SafDirectoryEntry folder(String name, long modified) {
        return new SafDirectoryEntry(name, "doc:" + name, SafDirectoryEntry.MIME_TYPE_DIR, modified, 0, true);
    }

    private static SafDirectory listing(SafDirectoryEntry... entries) {
        return SafDirectory.listed("content://x/tree/a/document/a", true, new ArrayList<>(Arrays.asList(entries)));
    }

    // --- what is a change

    @Test
    public void theFirstListingOfAFolderIsNoChange() {
        assertFalse(detector.observe("a", listing(file("1.mp4", 1, 10))));

        assertEquals(0, detector.pendingFolders());
        assertEquals(-1, detector.delayUntilBump());
        assertTrue(detector.knows("a"));
    }

    @Test
    public void theSameListingAgainIsNoChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10), folder("sub", 5)));

        assertFalse(detector.observe("a", listing(file("1.mp4", 1, 10), folder("sub", 5))));
        assertEquals(0, detector.pendingFolders());
    }

    @Test
    public void anAddedFileIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertTrue(detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 20))));
        assertEquals(1, detector.pendingFolders());
    }

    @Test
    public void anAddedFolderIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertTrue(detector.observe("a", listing(file("1.mp4", 1, 10), folder("new torrent", 3))));
    }

    @Test
    public void aRemovedFileIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 20)));

        assertTrue(detector.observe("a", listing(file("1.mp4", 1, 10))));
    }

    @Test
    public void aFolderThatBecameEmptyIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertTrue(detector.observe("a", listing()));
    }

    @Test
    public void aRenamedFileIsAChange() {
        detector.observe("a", listing(new SafDirectoryEntry("movie.mp4.part", "doc:1", "application/octet-stream", 1, 10, false)));

        // a torrent client renames the finished file; the provider may keep the document id
        assertTrue(detector.observe("a", listing(new SafDirectoryEntry("movie.mp4", "doc:1", "video/mp4", 1, 10, false))));
    }

    @Test
    public void aChangedDocumentIdWithTheSameNameIsAChange() {
        detector.observe("a", listing(new SafDirectoryEntry("x.mp4", "doc:1", "video/mp4", 1, 10, false)));

        assertTrue(detector.observe("a", listing(new SafDirectoryEntry("x.mp4", "doc:2", "video/mp4", 1, 10, false))));
    }

    @Test
    public void aChangedSizeIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertTrue(detector.observe("a", listing(file("1.mp4", 1, 11))));
    }

    @Test
    public void aChangedLastModifiedIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertTrue(detector.observe("a", listing(file("1.mp4", 2, 10))));
    }

    @Test
    public void aFileThatBecameAFolderIsAChange() {
        detector.observe("a", listing(new SafDirectoryEntry("x", "doc:x", "application/octet-stream", 1, 0, false)));

        assertTrue(detector.observe("a", listing(new SafDirectoryEntry("x", "doc:x", SafDirectoryEntry.MIME_TYPE_DIR, 1, 0, true))));
    }

    @Test
    public void theOrderOfTheEntriesDoesNotMatter() {
        detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 20), folder("sub", 3)));

        assertFalse(detector.observe("a", listing(folder("sub", 3), file("2.mp4", 2, 20), file("1.mp4", 1, 10))));
    }

    @Test
    public void swappingTwoValuesBetweenEntriesIsAChange() {
        detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 20)));

        assertTrue(detector.observe("a", listing(file("1.mp4", 2, 20), file("2.mp4", 1, 10))));
    }

    @Test
    public void nameBoundariesAreNotBlurred() {
        long first = SafChangeDetector.fingerprintOf(Arrays.asList(
                new SafDirectoryEntry("ab", "c", null, 0, 0, false)));
        long second = SafChangeDetector.fingerprintOf(Arrays.asList(
                new SafDirectoryEntry("a", "bc", null, 0, 0, false)));
        long nullName = SafChangeDetector.fingerprintOf(Arrays.asList(
                new SafDirectoryEntry(null, "c", null, 0, 0, false)));
        long emptyName = SafChangeDetector.fingerprintOf(Arrays.asList(
                new SafDirectoryEntry("", "c", null, 0, 0, false)));

        assertNotEquals(first, second);
        assertNotEquals(nullName, emptyName);
    }

    @Test
    public void theEntryCountIsPartOfTheFingerprint() {
        SafDirectoryEntry twin = file("same", 1, 1);
        long one = SafChangeDetector.fingerprintOf(Arrays.asList(twin));
        long two = SafChangeDetector.fingerprintOf(Arrays.asList(twin, twin));

        assertNotEquals(one, two);
        assertNotEquals(one, SafChangeDetector.fingerprintOf(Collections.emptyList()));
    }

    @Test
    public void foldersAreComparedSeparately() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));
        detector.observe("b", listing(file("1.mp4", 1, 10)));

        assertTrue(detector.observe("b", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 20))));
        assertFalse(detector.observe("a", listing(file("1.mp4", 1, 10))));
        assertEquals(1, detector.pendingFolders());
    }

    // --- listings that failed

    @Test
    public void aFolderThatCannotBeListedIsNeitherAChangeNorACrashAndKeepsItsFingerprint() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertFalse(detector.observe("a", SafDirectory.listingFailed("content://x", true)));
        assertFalse(detector.observe("a", SafDirectory.notADirectory(null)));
        assertFalse(detector.observe("a", SafDirectory.listed("content://x", false, new ArrayList<>()))); // permission lost
        assertFalse(detector.observe("a", null));
        assertFalse(detector.observe(null, listing()));

        assertEquals(0, detector.pendingFolders());
        // the old fingerprint is still the baseline: the same listing as before is no change ...
        assertFalse(detector.observe("a", listing(file("1.mp4", 1, 10))));
        // ... and a real difference is one
        assertTrue(detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 2))));
    }

    @Test
    public void aFailedFirstListingIsNotABaseline() {
        assertFalse(detector.observe("a", SafDirectory.listingFailed("content://x", true)));

        assertFalse(detector.knows("a"));
        assertFalse(detector.observe("a", listing(file("1.mp4", 1, 10))));
        assertEquals(0, detector.pendingFolders());
    }

    // --- coalescing and the minimum interval

    @Test
    public void manyChangedFoldersInOnePassAreOneBump() {
        for (String folder : new String[]{"a", "b", "c"}) {
            detector.observe(folder, listing(file("1.mp4", 1, 10)));
        }
        for (String folder : new String[]{"a", "b", "c"}) {
            assertTrue(detector.observe(folder, listing(file("1.mp4", 1, 10), file("2.mp4", 2, 2))));
        }

        Set<String> due = detector.takeDue();

        assertEquals(Set.of("a", "b", "c"), due);
        assertTrue("one bump covers all of them", detector.takeDue().isEmpty());
        assertEquals(0, detector.pendingFolders());
    }

    @Test
    public void theSameFolderChangingTwiceIsCountedOnce() {
        detector.observe("a", listing(file("1.mp4", 1, 10)));
        detector.observe("a", listing(file("1.mp4", 1, 11)));
        detector.observe("a", listing(file("1.mp4", 1, 12)));

        assertEquals(1, detector.takeDue().size());
    }

    @Test
    public void theFirstBumpIsNotDelayed() {
        detector.observe("a", listing());
        detector.observe("a", listing(file("1.mp4", 1, 10)));

        assertEquals(0, detector.delayUntilBump());
        assertEquals(1, detector.takeDue().size());
    }

    @Test
    public void aChangeRightAfterABumpWaitsForTheRestOfTheIntervalAndIsNotDropped() {
        detector.observe("a", listing());
        detector.observe("a", listing(file("1.mp4", 1, 10)));
        detector.takeDue();
        now.addAndGet(4_000);
        assertTrue(detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 2))));

        assertEquals(MIN_INTERVAL - 4_000, detector.delayUntilBump());
        assertTrue("not due yet", detector.takeDue().isEmpty());
        assertEquals("still waiting", 1, detector.pendingFolders());

        now.addAndGet(MIN_INTERVAL - 4_000 - 1);
        assertEquals(1, detector.delayUntilBump());
        assertTrue(detector.takeDue().isEmpty());

        now.addAndGet(1);
        assertEquals(0, detector.delayUntilBump());
        assertEquals(Set.of("a"), detector.takeDue());
    }

    @Test
    public void aFileWrittenEverySecondGivesOneBumpPerInterval() {
        detector.observe("a", listing());
        int bumps = 0;
        for (int second = 1; second <= 60; second++) {
            now.addAndGet(1_000);
            detector.observe("a", listing(file("download.part", second, second * 1000L)));
            if (!detector.takeDue().isEmpty()) {
                bumps++;
            }
        }

        // second 1 at once, then every 15 s: 1, 16, 31, 46
        assertEquals(4, bumps);
        assertEquals("the last changes wait for the next slot", 1, detector.pendingFolders());
    }

    @Test
    public void nothingPendingMeansNoDelay() {
        assertEquals(-1, detector.delayUntilBump());
        assertTrue(detector.takeDue().isEmpty());
    }

    @Test
    public void aBumpThatCouldNotBeSentIsGivenBackAndTheIntervalUndone() {
        detector.observe("a", listing());
        detector.observe("a", listing(file("1.mp4", 1, 10)));
        Set<String> due = detector.takeDue();

        detector.giveBack(due);

        assertEquals(1, detector.pendingFolders());
        assertEquals("no bump happened, so none is waited for", 0, detector.delayUntilBump());
        assertEquals(Set.of("a"), detector.takeDue());
    }

    @Test
    public void aChangeSeenWhileTheBumpIsBeingSentWaitsForTheNextBump() {
        detector.observe("a", listing());
        detector.observe("a", listing(file("1.mp4", 1, 10)));
        detector.takeDue(); // the bump is on its way ...
        detector.observe("a", listing(file("1.mp4", 1, 10), file("2.mp4", 2, 2))); // ... and this change is seen now

        assertEquals(1, detector.pendingFolders());
        assertEquals(MIN_INTERVAL, detector.delayUntilBump());
    }

    // --- bound, forgetting

    @Test
    public void atMostMaxFoldersAreKnownTheLeastRecentlyWatchedGoesFirst() {
        SafChangeDetector small = new SafChangeDetector(2, MIN_INTERVAL, now::get);
        small.observe("a", listing(file("1", 1, 1)));
        small.observe("b", listing(file("1", 1, 1)));
        small.observe("a", listing(file("1", 1, 1))); // a is watched again: b is now the eldest

        small.observe("c", listing(file("1", 1, 1)));

        assertTrue(small.knows("a"));
        assertFalse(small.knows("b"));
        assertTrue(small.knows("c"));
        // b starts again with a baseline: no change although the listing differs from the forgotten one
        assertFalse(small.observe("b", listing(file("2", 2, 2))));
    }

    @Test
    public void aFolderThatWasEvictedKeepsItsPendingChange() {
        SafChangeDetector small = new SafChangeDetector(1, MIN_INTERVAL, now::get);
        small.observe("a", listing(file("1", 1, 1)));
        small.observe("a", listing(file("1", 1, 2)));
        assertEquals(1, small.pendingFolders());

        small.observe("b", listing(file("1", 1, 1)));

        // the change of a was real and not reported yet: it must not be lost with the fingerprint
        assertEquals(1, small.pendingFolders());
    }

    @Test
    public void retainOnlyForgetsTheFoldersThatAreNotWatchedAnyMore() {
        detector.observe("a", listing(file("1", 1, 1)));
        detector.observe("b", listing(file("1", 1, 1)));

        detector.retainOnly(Arrays.asList("a", "c"));

        assertTrue(detector.knows("a"));
        assertFalse(detector.knows("b"));
        assertFalse(detector.observe("b", listing(file("2", 2, 2))));
    }

    @Test
    public void resetForgetsFingerprintsAndPendingChangesButNotTheTimeOfTheLastBump() {
        detector.observe("a", listing());
        detector.observe("a", listing(file("1", 1, 1)));
        detector.takeDue();
        detector.observe("a", listing(file("1", 1, 2)));

        detector.reset();

        assertFalse(detector.knows("a"));
        assertEquals(0, detector.pendingFolders());
        assertFalse("the new baseline", detector.observe("a", listing(file("9", 9, 9))));
        assertTrue(detector.observe("a", listing(file("9", 9, 10))));
        assertEquals("the interval of the last bump still runs", MIN_INTERVAL, detector.delayUntilBump());
    }

    @Test
    public void aLargeFolderIsFingerprintedFast() {
        List<SafDirectoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            entries.add(file("file number " + i + ".mp4", i, i * 3L));
        }
        long start = System.nanoTime();

        long first = SafChangeDetector.fingerprintOf(entries);
        long second = SafChangeDetector.fingerprintOf(entries);

        assertEquals(first, second);
        assertTrue("100000 entries twice in under 2 s", System.nanoTime() - start < 2_000_000_000L);
    }
}
