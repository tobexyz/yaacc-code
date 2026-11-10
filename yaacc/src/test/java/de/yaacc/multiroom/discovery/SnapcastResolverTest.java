/*
 *
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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A manual host name is resolved on a helper with a time limit, never on the reader thread without one; a literal is
 * never looked up; a name that resolves to an unusable address is refused.
 */
public class SnapcastResolverTest {

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    @Test
    public void anAddressThatIsKnownIsNotLookedUp() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            lookups.incrementAndGet();
            return ip(1, 2, 3, 4);
        }, 1_000);
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse("192.168.1.5");

        assertSame(endpoint.getAddress(), resolver.resolve(endpoint));
        assertEquals(0, lookups.get());
    }

    @Test
    public void aHostNameIsResolvedOnce() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            lookups.incrementAndGet();
            assertEquals("snap.local", host);
            return ip(192, 168, 1, 5);
        }, 1_000);

        assertEquals(ip(192, 168, 1, 5), resolver.resolve(SnapcastEndpoint.parse("snap.local")));
        assertEquals(1, lookups.get());
    }

    @Test
    public void aLookupThatDoesNotReturnEndsAtTheTimeLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            release.await(); // the resolver hangs
            return ip(10, 0, 0, 1);
        }, 200);
        long start = System.nanoTime();
        try {
            resolver.resolve(SnapcastEndpoint.parse("snap.local"));
            fail("should time out");
        } catch (IOException expected) {
            // fine
        } finally {
            release.countDown();
        }
        assertTrue("returned at the limit, not at the lookup", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
    }

    @Test
    public void hangingLookupsDoNotPileUpThreads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            started.incrementAndGet();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignoredLikeTheSystemResolver) {
                    // getaddrinfo is not interruptible: the thread stays busy until the lookup returns
                }
            }
            return ip(10, 0, 0, 1);
        }, 100);
        try {
            for (int i = 0; i < 10; i++) {
                try {
                    resolver.resolve(SnapcastEndpoint.parse("snap" + i + ".local"));
                } catch (IOException expected) {
                    // timed out or busy
                }
            }
            assertTrue("at most " + SnapcastResolver.MAX_HELPERS + " lookups are under way, was " + started.get(),
                    started.get() <= SnapcastResolver.MAX_HELPERS);
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aFailedLookupIsAnIoException() {
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            throw new UnknownHostException();
        }, 1_000);
        try {
            resolver.resolve(SnapcastEndpoint.parse("nope.local"));
            fail("should fail");
        } catch (IOException expected) {
            assertFalse("no host name in the message", String.valueOf(expected.getMessage()).contains("nope"));
        }
    }

    @Test
    public void aNameThatResolvesToAnUnusableAddressIsRefused() throws Exception {
        for (InetAddress bad : new InetAddress[]{ip(0, 0, 0, 0), ip(224, 0, 0, 1), ip(255, 255, 255, 255)}) {
            SnapcastResolver resolver = new SnapcastResolver(host -> bad, 1_000);
            try {
                resolver.resolve(SnapcastEndpoint.parse("snap.local"));
                fail("should be refused: " + bad);
            } catch (IOException expected) {
                // fine
            }
        }
    }
}
