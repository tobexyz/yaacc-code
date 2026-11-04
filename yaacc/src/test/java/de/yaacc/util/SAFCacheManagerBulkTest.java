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
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SAFCacheManager#getMetadataBulk}: the same results as the single calls, cold and warm, the same short ids,
 * one write for what was new. In-memory preferences, no device.
 */
public class SAFCacheManagerBulkTest {

    private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";

    private final FakeSharedPreferences legacy = new FakeSharedPreferences();
    private final FakeSharedPreferences store = new FakeSharedPreferences();
    private final AtomicLong now = new AtomicLong(0);
    private final AtomicBoolean onMainThread = new AtomicBoolean(false);
    private final List<SAFCacheManager> managers = new ArrayList<>();

    @After
    public void tearDown() {
        for (SAFCacheManager manager : managers) {
            manager.shutdown();
        }
    }

    private SAFCacheManager newManager(FakeSharedPreferences preferences) {
        SAFCacheManager manager = new SAFCacheManager(mock(Context.class), legacy, preferences, now::get, onMainThread::get);
        managers.add(manager);
        return manager;
    }

    private static Uri uri(int i) {
        Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn(TREE + "/document/primary%3ADCIM%2F.thumbnails%2F" + i);
        return uri;
    }

    private static List<SafMetadataRequest> requests(List<Uri> uris) {
        List<SafMetadataRequest> requests = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            // .jpg and .txt: no duration extraction is queued
            requests.add(new SafMetadataRequest(uris.get(i), "f" + i + (i % 2 == 0 ? ".jpg" : ".txt"), i % 2 == 0 ? "image/jpeg" : "text/plain", 100 + i));
        }
        return requests;
    }

    private static List<Uri> uris(int count) {
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            uris.add(uri(i));
        }
        return uris;
    }

    private static void assertSameMetadata(String message, SAFMetadata expected, SAFMetadata actual) {
        assertNotNull(message, actual);
        assertEquals(message + " duration", expected.duration, actual.duration);
        assertEquals(message + " mime type", expected.mimeType, actual.mimeType);
        assertEquals(message + " short id", expected.shortId, actual.shortId);
        assertEquals(message + " size", expected.fileSize, actual.fileSize);
    }

    @Test
    public void aColdBulkGivesWhatTheSingleCallsGiveAndTheShortIdsAreStable() throws Exception {
        List<Uri> uris = uris(2 * SAFCacheManager.BULK_CHUNK + 37);
        List<SafMetadataRequest> requests = requests(uris);
        SAFCacheManager single = newManager(new FakeSharedPreferences());
        SAFCacheManager bulk = newManager(new FakeSharedPreferences());
        assertTrue(single.awaitIdle(5000));
        assertTrue(bulk.awaitIdle(5000));

        SAFMetadata[] fromBulk = bulk.getMetadataBulk(requests);

        assertEquals(requests.size(), fromBulk.length);
        for (int i = 0; i < requests.size(); i++) {
            SafMetadataRequest r = requests.get(i);
            SAFMetadata expected = single.getMetadata(r.uri, r.name, r.providerMimeType, r.fileSize);
            assertSameMetadata("file " + i, expected, fromBulk[i]);
            // the id maps back to the uri
            assertEquals(r.uri.toString(), bulk.getUriForShortId(fromBulk[i].shortId));
        }
        // a later single call and a later bulk see the same ids
        SAFMetadata again = bulk.getMetadata(requests.get(5).uri, "f5.txt", "text/plain", 105);
        assertSameMetadata("single after bulk", fromBulk[5], again);
        SAFMetadata[] second = bulk.getMetadataBulk(requests);
        for (int i = 0; i < requests.size(); i++) {
            assertSameMetadata("second bulk, file " + i, fromBulk[i], second[i]);
        }
        Set<String> ids = new HashSet<>();
        for (SAFMetadata metadata : fromBulk) {
            assertTrue("short ids are unique", ids.add(metadata.shortId));
        }
    }

    @Test
    public void aWarmBulkReadsTheStoredEntriesAndKeepsTheirIds() throws Exception {
        List<Uri> uris = uris(700);
        List<SafMetadataRequest> requests = requests(uris);
        SAFCacheManager first = newManager(store);
        assertTrue(first.awaitIdle(5000));
        SAFMetadata[] cold = first.getMetadataBulk(requests);
        assertTrue(first.awaitIdle(5000));

        // a new process over the same stored state: the memory is empty, the entries are stored
        SAFCacheManager second = newManager(store);
        assertTrue(second.awaitIdle(5000));
        SAFMetadata[] warm = second.getMetadataBulk(requests);

        for (int i = 0; i < requests.size(); i++) {
            assertSameMetadata("file " + i, cold[i], warm[i]);
        }
        assertEquals("nothing new: nothing written", 1, store.commits.get());
    }

    @Test
    public void whatIsNewIsWrittenInOneCommit() throws Exception {
        SAFCacheManager manager = newManager(store);
        assertTrue(manager.awaitIdle(5000));
        int commitsBefore = store.commits.get();

        manager.getMetadataBulk(requests(uris(3 * SAFCacheManager.BULK_CHUNK)));
        assertTrue(manager.awaitIdle(5000));

        assertEquals("one write of all new entries, not one per " + SAFCacheManager.FLUSH_MAX_CHANGES + " changes", commitsBefore + 1, store.commits.get());
        int entries = 0;
        for (String key : store.snapshot().keySet()) {
            if (key.startsWith("saf_cache_")) {
                entries++;
            }
        }
        assertEquals(3 * SAFCacheManager.BULK_CHUNK, entries);
    }

    @Test
    public void aRequestWithoutUriIsNullAndTheOthersAreServed() throws Exception {
        SAFCacheManager manager = newManager(new FakeSharedPreferences());
        assertTrue(manager.awaitIdle(5000));
        List<SafMetadataRequest> requests = new ArrayList<>();
        requests.add(new SafMetadataRequest(uri(1), "a.jpg", "image/jpeg", 1));
        requests.add(new SafMetadataRequest(null, "b.jpg", "image/jpeg", 2));
        requests.add(new SafMetadataRequest(uri(3), "c.jpg", "image/jpeg", 3));

        SAFMetadata[] result = manager.getMetadataBulk(requests);

        assertNotNull(result[0]);
        assertNull(result[1]);
        assertNotNull(result[2]);
        assertEquals(0, manager.getMetadataBulk(new ArrayList<>()).length);
    }

    @Test
    public void beforeTheStoredStateIsLoadedTheMainThreadGetsTransientIdsAndNothingIsStored() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        store.holdGetAll = hold;
        SAFCacheManager manager = newManager(store);
        onMainThread.set(true);

        SAFMetadata[] result = manager.getMetadataBulk(requests(uris(5)));

        for (SAFMetadata metadata : result) {
            assertTrue("transient id " + metadata.shortId, metadata.shortId.startsWith("t"));
        }
        assertFalse(manager.isLoaded());
        hold.countDown();
        assertTrue(manager.awaitIdle(5000));
        assertEquals("nothing was persisted", 0, store.commits.get());
    }

    @Test
    public void aColdBulkOf5570FilesIsFast() throws Exception {
        List<Uri> uris = uris(5570);
        List<SafMetadataRequest> requests = requests(uris);
        SAFCacheManager manager = newManager(new FakeSharedPreferences());
        assertTrue(manager.awaitIdle(5000));

        long start = System.nanoTime();
        SAFMetadata[] result = manager.getMetadataBulk(requests);
        long millis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(5570, result.length);
        assertTrue("5570 new entries took " + millis + " ms", millis < 1500);
    }
}
