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

import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link ShareRegistrar}: one eviction and one retry, and the caller keeps what was refused. */
public class ShareRegistrarTest {

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

    private ShareRegistry.Entry entry(Object target) {
        return new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, target, "audio/mpeg",
                Collections.<String>emptySet(), GRACE, ABSOLUTE);
    }

    @Test
    public void aFreeSlotIsUsedAtOnce() {
        assertNotNull(ShareRegistrar.register(registry, entry(new Source())));
    }

    @Test
    public void aFullRegistryEvictsAReleasedLeftoverAndRetries() {
        Source leftover = new Source();
        String leftoverKey = ShareRegistrar.register(registry, entry(leftover));
        ShareRegistrar.register(registry, entry(new Source()));
        registry.release(leftoverKey);

        String key = ShareRegistrar.register(registry, entry(new Source()));

        assertNotNull(key);
        assertEquals(1, leftover.closes.get());
        assertNull(registry.lookup(leftoverKey));
    }

    @Test
    public void runningSharesAreNeverEvictedAndTheCallerKeepsTheRefusedTarget() {
        Source a = new Source();
        Source b = new Source();
        ShareRegistrar.register(registry, entry(a));
        ShareRegistrar.register(registry, entry(b));
        Source refused = new Source();

        String key = ShareRegistrar.register(registry, entry(refused));

        assertNull(key);
        assertEquals(0, a.closes.get());
        assertEquals(0, b.closes.get());
        assertEquals("the registry does not close a target it refused", 0, refused.closes.get());
    }
}
