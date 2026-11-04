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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The duration preload and the background duration tasks give way to a Browse that is being answered
 * ({@link SAFCacheManager#browseGate()}), and go on when it is done.
 */
public class SAFCacheManagerBrowseBackoffTest {

    private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3AMusic";
    private static final String SONG_A = TREE + "/document/primary%3AMusic%2Fa.mp3";
    private static final String SONG_B = TREE + "/document/primary%3AMusic%2Fb.mp3";

    private final List<SAFCacheManager> managers = new ArrayList<>();
    private final AtomicInteger preloadExtractions = new AtomicInteger();
    private final AtomicInteger backgroundExtractions = new AtomicInteger();

    @After
    public void tearDown() {
        for (SAFCacheManager manager : managers) {
            manager.shutdown();
        }
    }

    private SAFCacheManager newManager() {
        SAFCacheManager manager = new SAFCacheManager(mock(Context.class), new FakeSharedPreferences(), new FakeSharedPreferences(), () -> 0L, () -> false);
        manager.setDurationExtractor(new SAFCacheManager.DurationExtractor() {
            @Override
            public String extractForPreload(Uri uri) {
                preloadExtractions.incrementAndGet();
                return "00:03:12";
            }

            @Override
            public String extractInBackground(Uri uri, String mimeType) {
                backgroundExtractions.incrementAndGet();
                return "00:01:00";
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

    private static DocumentFile song(String uri, String name) {
        Uri fileUri = uri(uri);
        DocumentFile file = mock(DocumentFile.class);
        when(file.isDirectory()).thenReturn(false);
        when(file.getUri()).thenReturn(fileUri);
        when(file.getName()).thenReturn(name);
        when(file.getType()).thenReturn("audio/mpeg");
        when(file.length()).thenReturn(123L);
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

    @Test
    public void thePreloadWaitsWhileABrowseIsActiveAndGoesOnAfterIt() throws Exception {
        SAFCacheManager manager = newManager();
        manager.browseGate().enter();
        Thread preload = new Thread(() -> manager.traverseAndCache(folder(song(SONG_A, "a.mp3"), song(SONG_B, "b.mp3")), System.currentTimeMillis()));

        preload.start();
        Thread.sleep(300);
        assertEquals("nothing is extracted while the Browse is answered", 0, preloadExtractions.get());
        assertTrue(preload.isAlive());

        manager.browseGate().exit();
        preload.join(10_000);

        assertEquals(2, preloadExtractions.get());
    }

    @Test
    public void thePreloadDoesNotWaitWhenNoBrowseIsActive() throws Exception {
        SAFCacheManager manager = newManager();

        long start = System.nanoTime();
        manager.traverseAndCache(folder(song(SONG_A, "a.mp3"), song(SONG_B, "b.mp3")), System.currentTimeMillis());
        long millis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(2, preloadExtractions.get());
        assertTrue("took " + millis + " ms", millis < 5000);
    }

    @Test
    public void aBackgroundDurationTaskWaitsForTheBrowseToo() throws Exception {
        SAFCacheManager manager = newManager();
        manager.browseGate().enter();

        // a new audio file: its duration is extracted in the background
        manager.getMetadataBulk(java.util.Collections.singletonList(new SafMetadataRequest(uri(SONG_A), "a.mp3", "audio/mpeg", 5)));
        Thread.sleep(300);
        assertEquals(0, backgroundExtractions.get());

        manager.browseGate().exit();
        assertTrue(manager.awaitBackgroundIdle(10_000));

        assertEquals(1, backgroundExtractions.get());
    }
}
