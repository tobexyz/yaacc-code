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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The duration of a SAF file lives in the serialized {@link SAFMetadata} under {@code saf_cache_<uri>}. The preload
 * used to store a bare duration there, which the reader could not parse, so the metadata was built again and the
 * duration extracted a second time. The extractions are counted with a fake {@link SAFCacheManager.DurationExtractor}.
 */
public class SAFCacheManagerDurationTest {

    private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3AMusic";
    private static final String SONG_A = TREE + "/document/primary%3AMusic%2Fa.mp3";
    private static final String SONG_B = TREE + "/document/primary%3AMusic%2Fb.mp3";

    private final FakeSharedPreferences legacy = new FakeSharedPreferences();
    private final FakeSharedPreferences store = new FakeSharedPreferences();
    private final List<SAFCacheManager> managers = new ArrayList<>();
    private final AtomicInteger preloadExtractions = new AtomicInteger();
    private final AtomicInteger backgroundExtractions = new AtomicInteger();
    private volatile String backgroundDuration = null;

    @After
    public void tearDown() {
        for (SAFCacheManager manager : managers) {
            manager.shutdown();
        }
    }

    private SAFCacheManager newManager() throws Exception {
        SAFCacheManager manager = new SAFCacheManager(mock(Context.class), legacy, store, () -> 0L, () -> false);
        manager.setDurationExtractor(new SAFCacheManager.DurationExtractor() {
            @Override
            public String extractForPreload(Uri uri) {
                preloadExtractions.incrementAndGet();
                return "00:03:12";
            }

            @Override
            public String extractInBackground(Uri uri, String mimeType) {
                backgroundExtractions.incrementAndGet();
                return backgroundDuration;
            }
        });
        managers.add(manager);
        assertTrue(manager.awaitIdle(5000));
        return manager;
    }

    private static Uri uri(String value) {
        Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn(value);
        return uri;
    }

    private static DocumentFile song(Uri uri, String name, long length) {
        DocumentFile file = mock(DocumentFile.class);
        when(file.isDirectory()).thenReturn(false);
        when(file.getUri()).thenReturn(uri);
        when(file.getName()).thenReturn(name);
        when(file.getType()).thenReturn("audio/mpeg");
        when(file.length()).thenReturn(length);
        return file;
    }

    private static DocumentFile folder(DocumentFile... children) {
        DocumentFile dir = mock(DocumentFile.class);
        when(dir.isDirectory()).thenReturn(true);
        when(dir.canRead()).thenReturn(true);
        when(dir.getName()).thenReturn("Music");
        when(dir.listFiles()).thenReturn(children);
        return dir;
    }

    private void preload(SAFCacheManager manager, DocumentFile dir) {
        manager.traverseAndCache(dir, System.currentTimeMillis());
    }

    // ---- preload ----

    @Test
    public void preloadStoresReadableMetadataWithTheDuration() throws Exception {
        SAFCacheManager manager = newManager();

        preload(manager, folder(song(uri(SONG_A), "a.mp3", 123)));
        manager.flush();

        SAFMetadata stored = SAFMetadata.deserialize(store.getString("saf_cache_" + SONG_A, null));
        assertNotNull("the stored value is a serialized SAFMetadata", stored);
        assertEquals("00:03:12", stored.duration);
        assertEquals("audio/mpeg", stored.mimeType);
        assertEquals(123, stored.fileSize);
        assertNotNull(stored.shortId);
        assertEquals("the id resolves", SONG_A, manager.getUriForShortId(stored.shortId));
    }

    @Test
    public void getMetadataAfterPreloadIsAHitWithoutASecondExtraction() throws Exception {
        SAFCacheManager manager = newManager();
        preload(manager, folder(song(uri(SONG_A), "a.mp3", 123)));
        assertEquals(1, preloadExtractions.get());

        SAFMetadata metadata = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 123);
        assertTrue(manager.awaitBackgroundIdle(5000));

        assertEquals("00:03:12", metadata.duration);
        assertEquals(1, preloadExtractions.get());
        assertEquals("no queued extraction for a hit", 0, backgroundExtractions.get());
    }

    @Test
    public void getMetadataAfterAPreloadFromAnEarlierRunIsAHitToo() throws Exception {
        SAFCacheManager first = newManager();
        preload(first, folder(song(uri(SONG_A), "a.mp3", 123)));
        first.flush();

        SAFCacheManager second = newManager();
        SAFMetadata metadata = second.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 123);
        assertTrue(second.awaitBackgroundIdle(5000));

        assertEquals("00:03:12", metadata.duration);
        assertEquals(0, backgroundExtractions.get());
    }

    @Test
    public void preloadKeepsTheShortIdAndTheValuesOfAnExistingEntry() throws Exception {
        SAFCacheManager manager = newManager();
        // browsed before the preload: the entry exists without a duration (the background read found none)
        SAFMetadata browsed = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 77);
        assertTrue(manager.awaitBackgroundIdle(5000));
        assertEquals("", browsed.duration);

        preload(manager, folder(song(uri(SONG_A), "a.mp3", 999)));
        SAFMetadata after = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 77);

        assertEquals("00:03:12", after.duration);
        assertEquals("the short id never changes", browsed.shortId, after.shortId);
        assertEquals("the size of the entry stays", 77, after.fileSize);
        assertEquals(SONG_A, manager.getUriForShortId(browsed.shortId));
    }

    @Test
    public void preloadDoesNotExtractWhatHasADurationAlready() throws Exception {
        SAFCacheManager manager = newManager();
        preload(manager, folder(song(uri(SONG_A), "a.mp3", 123)));
        preload(manager, folder(song(uri(SONG_A), "a.mp3", 123)));

        assertEquals(1, preloadExtractions.get());
    }

    @Test
    public void aFileTheRetrieverCannotReadStoresNothingAndIsNotTriedAgain() throws Exception {
        SAFCacheManager manager = newManager();
        manager.setDurationExtractor(new SAFCacheManager.DurationExtractor() {
            @Override
            public String extractForPreload(Uri uri) throws Exception {
                preloadExtractions.incrementAndGet();
                throw new IllegalArgumentException("status 0x80000000");
            }

            @Override
            public String extractInBackground(Uri uri, String mimeType) {
                return null;
            }
        });

        DocumentFile dir = folder(song(uri(SONG_A), "a.mp3", 123));
        preload(manager, dir);
        preload(manager, dir);
        manager.flush();

        assertEquals(1, preloadExtractions.get());
        assertNull(store.getString("saf_cache_" + SONG_A, null));
    }

    @Test
    public void theQueuedBackgroundTaskSkipsAFileThePreloadAlreadyHandled() throws Exception {
        SAFCacheManager manager = newManager();
        CountDownLatch inTaskA = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger backgroundCalls = new AtomicInteger();
        manager.setDurationExtractor(new SAFCacheManager.DurationExtractor() {
            @Override
            public String extractForPreload(Uri uri) {
                preloadExtractions.incrementAndGet();
                return "00:03:12";
            }

            @Override
            public String extractInBackground(Uri uri, String mimeType) {
                backgroundCalls.incrementAndGet();
                inTaskA.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        });
        manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 1); // its task occupies the executor
        assertTrue(inTaskA.await(5, TimeUnit.SECONDS));
        manager.getMetadata(uri(SONG_B), "b.mp3", "audio/mpeg", 2); // its task waits behind it

        preload(manager, folder(song(uri(SONG_B), "b.mp3", 2)));
        release.countDown();
        assertTrue(manager.awaitBackgroundIdle(5000));

        assertEquals("only the task of the first file ran its extraction", 1, backgroundCalls.get());
        assertEquals("00:03:12", manager.getMetadata(uri(SONG_B), "b.mp3", "audio/mpeg", 2).duration);
    }

    // ---- legacy bare values ----

    @Test
    public void aBareDurationOfTheFormerPreloadIsUpgradedWithoutAnExtraction() throws Exception {
        store.with("saf_cache_" + SONG_A, "00:03:12");
        SAFCacheManager manager = newManager();

        SAFMetadata metadata = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 123);
        assertTrue(manager.awaitBackgroundIdle(5000));
        manager.flush();

        assertEquals("00:03:12", metadata.duration);
        assertEquals("audio/mpeg", metadata.mimeType);
        assertEquals(123, metadata.fileSize);
        assertNotNull(metadata.shortId);
        assertEquals(0, preloadExtractions.get());
        assertEquals("not queued for a second extraction", 0, backgroundExtractions.get());
        SAFMetadata stored = SAFMetadata.deserialize(store.getString("saf_cache_" + SONG_A, null));
        assertNotNull("the stored value was upgraded", stored);
        assertEquals("00:03:12", stored.duration);
        assertEquals(metadata.shortId, stored.shortId);
    }

    @Test
    public void anUpgradedEntryKeepsItsShortIdAndNeverGetsANewOne() throws Exception {
        store.with("saf_cache_" + SONG_A, "00:03:12");
        SAFCacheManager manager = newManager();

        String first = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 123).shortId;
        String other = manager.getMetadata(uri(SONG_B), "b.mp3", "audio/mpeg", 5).shortId;
        String again = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 123).shortId;
        manager.flush();

        SAFCacheManager restarted = newManager();
        String afterRestart = restarted.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 123).shortId;

        assertEquals(first, again);
        assertEquals(first, afterRestart);
        assertTrue(!first.equals(other));
        assertEquals(SONG_A, restarted.getUriForShortId(first));
    }

    @Test
    public void aBareDurationThatIsOnlyInTheStoredStateIsUpgradedToo() throws Exception {
        SAFCacheManager manager = newManager();
        // written to the file after the load: not in the memory structures, read through the stored state
        store.with("saf_cache_" + SONG_A, "01:02:03");

        SAFMetadata metadata = manager.getMetadata(uri(SONG_A), "a.mp3", "audio/mpeg", 5);
        assertTrue(manager.awaitBackgroundIdle(5000));

        assertEquals("01:02:03", metadata.duration);
        assertEquals(0, backgroundExtractions.get());
    }

    @Test
    public void aBareDurationNeedsNoPreloadExtraction() throws Exception {
        store.with("saf_cache_" + SONG_A, "00:03:12");
        SAFCacheManager manager = newManager();

        preload(manager, folder(song(uri(SONG_A), "a.mp3", 123)));

        assertEquals(0, preloadExtractions.get());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void getDurationReturnsTheDurationNotTheSerializedMetadata() throws Exception {
        SAFCacheManager manager = newManager();
        preload(manager, folder(song(uri(SONG_A), "a.mp3", 123)));
        store.with("saf_cache_" + SONG_B, "00:00:09");
        SAFCacheManager other = newManager();

        assertEquals("00:03:12", manager.getDuration(uri(SONG_A)));
        assertEquals("a legacy bare value", "00:00:09", other.getDuration(uri(SONG_B)));
        assertEquals("both cache hits", 1, preloadExtractions.get());
    }
}
