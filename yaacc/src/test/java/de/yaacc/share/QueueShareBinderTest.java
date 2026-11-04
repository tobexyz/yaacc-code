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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import de.yaacc.player.PlayableItem;

public class QueueShareBinderTest {

    private static Uri uri(String text) {
        Uri uri = Mockito.mock(Uri.class);
        Mockito.when(uri.toString()).thenReturn(text);
        return uri;
    }

    private final List<String> claimed = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final List<PendingShare.Data> prepared = new ArrayList<>();
    private final List<Set<String>> receivers = new ArrayList<>();

    private boolean room = true;
    private long duration = -1L;

    private final QueueShareBinder.Keys keys = new QueueShareBinder.Keys() {
        @Override
        public boolean hasRoom() {
            return room;
        }

        @Override
        public void claimed(String key) {
            claimed.add(key);
        }

        @Override
        public void remove(String key) {
            removed.add(key);
        }
    };

    private QueueShareBinder binder(QueueShareBinder.Preparer preparer, List<PlayableItem> items) {
        List<PickedQueue.Entry> entries = new ArrayList<>();
        entries.add(new PickedQueue.Entry("content://p/1", "One\n", "audio/mpeg", 5));
        entries.add(new PickedQueue.Entry("content://p/2", "Two", "audio/flac", 6));
        return new QueueShareBinder(entries, items, Collections.singleton("192.0.2.7"), preparer, keys, entry -> duration);
    }

    private List<PlayableItem> placeholders() {
        List<PlayableItem> items = new ArrayList<>();
        items.add(SharedItemFactory.create("audio/mpeg", "One", uri("yaacc-pending:0"), 5, 0, 0));
        items.add(SharedItemFactory.create("audio/flac", "Two", uri("yaacc-pending:1"), 6, 0, 0));
        return items;
    }

    private ShareService.Prepared preparedShare(String key, String url) {
        PlayableItem item = SharedItemFactory.create("audio/mpeg", "One", uri(url), 5, 0, 0);
        return new ShareService.Prepared(key, item);
    }

    @Test
    public void bindingCopiesTheSharedUrlAndItemOntoThePlaceholderAndClaimsTheKey() {
        List<PlayableItem> items = placeholders();
        PlayableItem placeholder = items.get(1);
        QueueShareBinder binder = binder((data, ids) -> {
            prepared.add(data);
            receivers.add(ids);
            return preparedShare("KEY1", "http://192.0.2.1:1/proxy/KEY1");
        }, items);
        QueueShareWindow.Bound bound = binder.bind(1);
        assertNotNull(bound);
        assertEquals("http://192.0.2.1:1/proxy/KEY1", placeholder.getUri().toString());
        assertEquals(Collections.singletonList("KEY1"), claimed);
        assertEquals("content://p/2", prepared.get(0).getUri());
        assertEquals("audio/flac", prepared.get(0).getMimeType());
        assertEquals(Collections.singleton("192.0.2.7"), receivers.get(0));
        assertTrue(removed.isEmpty());
        bound.release();
        assertEquals(Collections.singletonList("KEY1"), removed);
    }

    @Test
    public void thePickedNameIsCleanedWhenItIsShared() {
        List<PlayableItem> items = placeholders();
        binder((data, ids) -> {
            prepared.add(data);
            return preparedShare("K", "http://192.0.2.1:1/proxy/K");
        }, items).bind(0);
        assertEquals("One", prepared.get(0).getDisplayName());
    }

    @Test
    public void aShareThatCannotBePreparedGivesNoBoundAndTouchesNothing() {
        List<PlayableItem> items = placeholders();
        PlayableItem placeholder = items.get(0);
        String before = placeholder.getUri().toString();
        QueueShareBinder binder = binder((data, ids) -> null, items);
        assertNull(binder.bind(0));
        assertEquals(before, placeholder.getUri().toString());
        assertTrue(claimed.isEmpty());
    }

    @Test
    public void aFullRegistryRefusesBeforeAnyFileIsOpened() {
        room = false;
        List<PendingShare.Data> opened = new ArrayList<>();
        QueueShareBinder binder = binder((data, ids) -> {
            opened.add(data);
            return preparedShare("K", "http://192.0.2.1:1/proxy/K");
        }, placeholders());
        assertNull(binder.bind(0));
        assertTrue(opened.isEmpty());
        assertTrue(claimed.isEmpty());
        room = true;
        assertNotNull(binder.bind(0));
    }

    @Test
    public void aThrowingPreparerIsAFailedBind() {
        QueueShareBinder binder = binder((data, ids) -> {
            throw new IllegalStateException("boom");
        }, placeholders());
        assertNull(binder.bind(0));
    }

    @Test
    public void indexesOutsideTheQueueAreRefused() {
        QueueShareBinder binder = binder((data, ids) -> preparedShare("K", "http://192.0.2.1:1/proxy/K"),
                placeholders());
        assertNull(binder.bind(-1));
        assertNull(binder.bind(2));
    }

    @Test(timeout = 10000)
    public void bindsOfDifferentPlayersNeverOverlap() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInside = new java.util.concurrent.atomic.AtomicInteger();
        QueueShareBinder.Preparer slow = (data, ids) -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
            return preparedShare("K", "http://192.0.2.1:1/proxy/K");
        };
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final QueueShareBinder binder = binder(slow, placeholders());
            Thread thread = new Thread(() -> binder.bind(0));
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, maxInside.get());
    }

    @Test
    public void aKnownDurationGoesIntoTheItemAndItsDidlResource() {
        duration = 215_000L;
        List<PlayableItem> items = placeholders();
        binder((data, ids) -> preparedShare("K", "http://192.0.2.1:1/proxy/K"), items).bind(0);
        assertEquals(215_000L, items.get(0).getDuration());
        assertEquals("00:03:35", items.get(0).getItem().getFirstResource().getDuration());
        assertEquals("http://192.0.2.1:1/proxy/K", items.get(0).getUri().toString());
    }

    @Test
    public void anUnknownDurationLeavesTheItemAsShared() {
        duration = -1L;
        List<PlayableItem> items = placeholders();
        binder((data, ids) -> preparedShare("K", "http://192.0.2.1:1/proxy/K"), items).bind(0);
        assertNull(items.get(0).getItem().getFirstResource().getDuration());
    }
}
