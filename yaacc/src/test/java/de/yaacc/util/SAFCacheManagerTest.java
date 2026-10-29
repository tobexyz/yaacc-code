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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SAFCacheManager} with in-memory preferences: migration from the default preferences, batched writes,
 * the load on the I/O thread and what a caller on the main thread gets before it ended.
 */
public class SAFCacheManagerTest {

    private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";
    private static final String FILE_A = TREE + "/document/primary%3ADCIM%2Fa.jpg";
    private static final String FILE_B = TREE + "/document/primary%3ADCIM%2Fb.jpg";
    private static final String FILE_C = TREE + "/document/primary%3ADCIM%2Fc.jpg";
    private static final String OTHER = "content://other/tree/x/document/x%2Fd.jpg";

    private final FakeSharedPreferences legacy = new FakeSharedPreferences();
    private final FakeSharedPreferences store = new FakeSharedPreferences();
    private final AtomicLong now = new AtomicLong(0);
    private final AtomicBoolean onMainThread = new AtomicBoolean(false);
    private final List<SAFCacheManager> managers = new ArrayList<>();

    @Before
    public void setUp() {
        // nothing: the managers are created by the tests
    }

    @After
    public void tearDown() {
        for (SAFCacheManager manager : managers) {
            manager.shutdown();
        }
    }

    private SAFCacheManager newManager() {
        SAFCacheManager manager = new SAFCacheManager(mock(Context.class), legacy, store, now::get, onMainThread::get);
        managers.add(manager);
        return manager;
    }

    /** Everything the I/O thread was given so far has run. */
    private static void awaitIo(SAFCacheManager manager) throws Exception {
        assertTrue(manager.awaitIdle(5000));
    }

    private static Uri uri(String value) {
        Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn(value);
        return uri;
    }

    private SAFMetadata metadata(SAFCacheManager manager, String file) {
        // .jpg: no duration extraction is queued
        return manager.getMetadata(uri(file), file.substring(file.lastIndexOf('/') + 1) + ".jpg", "image/jpeg", 10);
    }

    // ---- migration ----

    @Test
    public void theOldEntriesMoveToTheNewFileAndTheirShortIdsStillResolve() throws Exception {
        legacy.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1")
                .with("saf_cache_" + FILE_B, "00:01:00|audio/mpeg|7|20|2")
                .with("saf_id_counter", 12L)
                .with("settings_theme", "dark");

        SAFCacheManager manager = newManager();
        awaitIo(manager);

        assertEquals(FILE_A, manager.getUriForShortId("4"));
        assertEquals(FILE_B, manager.getUriForShortId("7"));
        assertEquals("a known uri keeps its id", "7", manager.getOrCreateShortId(FILE_B));
        assertEquals("the counter was taken over", "12", manager.getOrCreateShortId(OTHER));

        assertEquals("|image/jpeg|4|10|1", store.getString("saf_cache_" + FILE_A, null));
        assertEquals(Long.valueOf(12), store.snapshot().get("saf_id_counter"));
        assertFalse(legacy.contains("saf_cache_" + FILE_A));
        assertFalse(legacy.contains("saf_cache_" + FILE_B));
        assertFalse(legacy.contains("saf_id_counter"));
        assertEquals("other settings stay", "dark", legacy.getString("settings_theme", null));
        assertEquals("no apply(): the old file was committed once", 0, legacy.applies.get());
        assertEquals(1, legacy.commits.get());
    }

    @Test
    public void aMigrationThatWasInterruptedIsCompletedWithoutLosingAnything() throws Exception {
        // the new file got the entries and a higher counter, the old keys were not removed
        store.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|9").with("saf_id_counter", 30L);
        legacy.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1")
                .with("saf_cache_" + FILE_B, "|image/jpeg|7|20|2")
                .with("saf_id_counter", 12L);

        SAFCacheManager manager = newManager();
        awaitIo(manager);

        assertEquals("|image/jpeg|4|10|9", store.getString("saf_cache_" + FILE_A, null));
        assertEquals("|image/jpeg|7|20|2", store.getString("saf_cache_" + FILE_B, null));
        assertEquals(Long.valueOf(30), store.snapshot().get("saf_id_counter"));
        assertFalse(legacy.contains("saf_cache_" + FILE_B));
        assertEquals(FILE_B, manager.getUriForShortId("7"));
        assertEquals("30", manager.getOrCreateShortId(OTHER));
    }

    @Test
    public void nothingIsWrittenWhenThereIsNothingToMigrate() throws Exception {
        legacy.with("settings_theme", "dark");
        SAFCacheManager manager = newManager();
        awaitIo(manager);
        assertEquals(0, legacy.commits.get());
        assertEquals(0, store.commits.get());
    }

    @Test
    public void ifTheNewFileCannotBeWrittenTheOldEntriesStay() throws Exception {
        legacy.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1");
        store.failCommits = true;
        SAFCacheManager manager = newManager();
        awaitIo(manager);
        assertTrue(legacy.contains("saf_cache_" + FILE_A));
        assertEquals("0 commits to the old file", 0, legacy.commits.get());
    }

    // ---- batching ----

    @Test
    public void writesAreCollectedAndCommittedOnceByTheIoThread() throws Exception {
        SAFCacheManager manager = newManager();
        awaitIo(manager);

        SAFMetadata a = metadata(manager, FILE_A);
        SAFMetadata b = metadata(manager, FILE_B);
        assertNotNull(a);
        assertEquals("1", a.shortId);
        assertEquals("2", b.shortId);
        assertEquals("a second call is served from memory", "1", metadata(manager, FILE_A).shortId);
        assertEquals("nothing written yet", 0, store.commits.get());
        assertEquals(0, store.applies.get());

        manager.flush();

        assertEquals(1, store.commits.get());
        assertEquals(0, store.applies.get());
        assertNotNull(store.getString("saf_cache_" + FILE_A, null));
        assertNotNull(store.getString("saf_cache_" + FILE_B, null));
        assertEquals(Long.valueOf(3), store.snapshot().get("saf_id_counter"));
    }

    @Test
    public void aFlushWithoutChangesCommitsNothing() throws Exception {
        SAFCacheManager manager = newManager();
        awaitIo(manager);
        manager.flush();
        assertEquals(0, store.commits.get());
    }

    @Test
    public void manyEntriesAreCommittedInBatchesNotOnePerEntry() throws Exception {
        SAFCacheManager manager = newManager();
        awaitIo(manager);
        int files = 700;
        for (int i = 0; i < files; i++) {
            metadata(manager, TREE + "/document/f" + i);
        }
        awaitIo(manager);
        manager.flush();

        // every entry has a put and an id (two changes), the policy flushes every 250 changes
        assertTrue("batched: " + store.commits.get(), store.commits.get() <= files * 2 / SAFCacheManager.FLUSH_MAX_CHANGES + 2);
        assertTrue(store.commits.get() >= 2);
        assertEquals(files + 1, store.snapshot().size()); // entries + counter
        assertEquals(0, store.applies.get());
    }

    @Test
    public void theIdsSurviveARestartOfTheManager() throws Exception {
        SAFCacheManager first = newManager();
        awaitIo(first);
        String idA = metadata(first, FILE_A).shortId;
        String idB = metadata(first, FILE_B).shortId;
        first.flush();

        SAFCacheManager second = newManager();
        awaitIo(second);
        assertEquals(FILE_A, second.getUriForShortId(idA));
        assertEquals(FILE_B, second.getUriForShortId(idB));
        assertEquals("no id is given out twice", "3", metadata(second, FILE_C).shortId);
    }

    @Test
    public void aFailedCommitIsRetried() throws Exception {
        SAFCacheManager manager = newManager();
        awaitIo(manager);
        metadata(manager, FILE_A);
        store.failCommits = true;
        manager.flush();
        assertNull(store.getString("saf_cache_" + FILE_A, null));
        store.failCommits = false;
        manager.flush();
        assertNotNull(store.getString("saf_cache_" + FILE_A, null));
    }

    // ---- main thread and the load ----

    @Test
    public void theMainThreadDoesNotWaitForTheLoadAndPersistsNothing() throws Exception {
        legacy.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1");
        CountDownLatch release = new CountDownLatch(1);
        legacy.holdGetAll = release; // the load stays in the migration
        onMainThread.set(true);
        SAFCacheManager manager = newManager();

        long start = System.nanoTime();
        SAFMetadata computed = metadata(manager, FILE_B);
        String id = manager.getOrCreateShortId(FILE_C);
        String unknown = manager.getUriForShortId("4");
        assertTrue("did not block", System.nanoTime() - start < 2_000_000_000L);

        assertNotNull(computed);
        assertTrue("transient id: " + computed.shortId, computed.shortId.startsWith("t"));
        assertTrue(id.startsWith("t"));
        assertEquals("the transient id resolves in this process", FILE_C, manager.getUriForShortId(id));
        assertNull("a stored id is not known before the load", unknown);
        assertFalse(manager.isLoaded());

        release.countDown();
        awaitIo(manager);
        assertEquals(FILE_A, manager.getUriForShortId("4"));
        manager.flush();
        assertNull("nothing was persisted for the transient answer", store.getString("saf_cache_" + FILE_B, null));
        assertEquals(0, store.applies.get());
    }

    @Test
    public void otherThreadsWaitForTheLoad() throws Exception {
        legacy.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1");
        CountDownLatch release = new CountDownLatch(1);
        legacy.holdGetAll = release;
        SAFCacheManager manager = newManager();

        AtomicReference<String> resolved = new AtomicReference<>();
        Thread caller = new Thread(() -> resolved.set(manager.getUriForShortId("4")));
        caller.start();
        Thread.sleep(100);
        assertNull("still waiting", resolved.get());
        release.countDown();
        caller.join(5000);
        assertEquals(FILE_A, resolved.get());
    }

    @Test
    public void theConstructorDoesNoPreferencesIo() throws Exception {
        legacy.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1");
        CountDownLatch release = new CountDownLatch(1);
        legacy.holdGetAll = release;
        long start = System.nanoTime();
        SAFCacheManager manager = newManager();
        assertTrue("construction returned although the load is held back", System.nanoTime() - start < 2_000_000_000L);
        release.countDown();
        awaitIo(manager);
    }

    // ---- clear and remove ----

    @Test
    public void clearCacheEmptiesMemoryAndTheStoredFileAndRestartsTheNumbering() throws Exception {
        store.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1").with("saf_id_counter", 5L);
        SAFCacheManager manager = newManager();
        awaitIo(manager);
        assertEquals(FILE_A, manager.getUriForShortId("4"));

        manager.clearCache();
        awaitIo(manager);

        assertNull(manager.getUriForShortId("4"));
        assertTrue(store.snapshot().isEmpty());
        assertEquals("1", manager.getOrCreateShortId(FILE_C));
        assertEquals(0, manager.getCacheSize());
    }

    @Test
    public void removeCachedUnderForgetsOnlyTheEntriesBelowTheTree() throws Exception {
        store.with("saf_cache_" + FILE_A, "|image/jpeg|4|10|1")
                .with("saf_cache_" + FILE_B, "|image/jpeg|5|10|1")
                .with("saf_cache_" + OTHER, "|image/jpeg|6|10|1")
                .with("saf_id_counter", 7L);
        SAFCacheManager manager = newManager();
        awaitIo(manager);

        manager.removeCachedUnder(TREE);
        awaitIo(manager);

        assertNull(store.getString("saf_cache_" + FILE_A, null));
        assertNull(store.getString("saf_cache_" + FILE_B, null));
        assertNotNull(store.getString("saf_cache_" + OTHER, null));
        assertEquals(1, manager.getCacheSize());
        // the short id map is not touched (as before), the entry is built anew and is not read back from the old state
        SAFMetadata rebuilt = manager.getMetadata(uri(FILE_A), "a.jpg", "image/jpeg", 1);
        assertEquals("4", rebuilt.shortId);
        assertEquals(1, rebuilt.fileSize);
    }

    @Test
    public void flushIfCreatedWithoutAManagerDoesNothing() {
        SAFCacheManager.flushIfCreated(10);
    }
}
