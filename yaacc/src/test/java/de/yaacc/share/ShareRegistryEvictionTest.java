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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ShareRegistry#evictOldestReleased()}: the registry keeps a released entry in
 * its slot during the grace period ({@code ShareRegistryTest}), the glue calls this method when
 * {@code register} returned null so that a user's own playback is not starved by leftovers.
 */
public class ShareRegistryEvictionTest {

    private static final long GRACE = 10_000L;
    private static final long ABSOLUTE = 100_000L;

    private volatile long now;
    private ShareRegistry registry;

    private static final class Source implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @Before
    public void setUp() {
        now = 1_000_000L;
        registry = new ShareRegistry(() -> now, new SecureRandom()::nextBytes, 2);
    }

    private String register(Object target) {
        return registry.register(new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, target,
                "audio/mpeg", Collections.<String>emptySet(), GRACE, ABSOLUTE));
    }

    @Test
    public void nothingToEvictWhenNoEntryWasReleased() {
        Source running = new Source();
        String key = register(running);

        assertFalse(registry.evictOldestReleased());

        assertNotNull(registry.lookup(key));
        assertEquals(0, running.closes.get());
    }

    @Test
    public void emptyRegistryHasNothingToEvict() {
        assertFalse(registry.evictOldestReleased());
    }

    @Test
    public void aReleasedEntryInItsGracePeriodIsEvictedAndClosedOnce() {
        Source leftover = new Source();
        String key = register(leftover);
        registry.release(key);
        now += GRACE - 1;

        assertTrue(registry.evictOldestReleased());
        assertFalse(registry.evictOldestReleased());

        assertNull(registry.lookup(key));
        assertEquals(1, leftover.closes.get());
    }

    @Test
    public void theOldestReleasedEntryGoesFirstAndRunningEntriesStay() {
        Source older = new Source();
        Source newer = new Source();
        String olderKey = register(older);
        String newerKey = register(newer);
        registry.release(olderKey);
        now += 1_000L;
        registry.release(newerKey);

        assertTrue(registry.evictOldestReleased());

        assertNull(registry.lookup(olderKey));
        assertNotNull(registry.lookup(newerKey));
        assertEquals(1, older.closes.get());
        assertEquals(0, newer.closes.get());
    }

    @Test
    public void aMarkedActiveEntryIsNotEvicted() {
        String key = register(new Source());
        registry.release(key);
        registry.markActive(key);

        assertFalse(registry.evictOldestReleased());
        assertNotNull(registry.lookup(key));
    }

    @Test
    public void evictionMakesRoomForARetryOfRegister() {
        String first = register(new Source());
        register(new Source());
        registry.release(first);
        assertNull(register(new Source()));

        assertTrue(registry.evictOldestReleased());

        assertNotNull(register(new Source()));
        assertEquals(2, registry.size());
    }
}
