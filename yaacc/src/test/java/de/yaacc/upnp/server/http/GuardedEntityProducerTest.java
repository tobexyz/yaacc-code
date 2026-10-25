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
package de.yaacc.upnp.server.http;

import static de.yaacc.upnp.server.http.ProducerTestSupport.KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;
import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;

/** {@link GuardedEntityProducer}: the upstream (HTTP_LINK) response wrapped with the share guards. */
public class GuardedEntityProducerTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(1, clock::get);
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final FakeDelegate delegate = new FakeDelegate();

    /** What HttpCore sees of the upstream producer, recording the calls and writing a fixed chunk. */
    private static final class FakeDelegate implements AsyncEntityProducer {
        final List<String> calls = new ArrayList<>();
        byte[] chunk = new byte[0];
        RuntimeException failOnRelease;
        RuntimeException failOnFailed;
        DataStreamChannel lastChannel;

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public void failed(Exception cause) {
            calls.add("failed");
            if (failOnFailed != null) {
                throw failOnFailed;
            }
        }

        @Override
        public long getContentLength() {
            return 4242L;
        }

        @Override
        public String getContentType() {
            return "video/mp4";
        }

        @Override
        public String getContentEncoding() {
            return "identity";
        }

        @Override
        public boolean isChunked() {
            return true;
        }

        @Override
        public Set<String> getTrailerNames() {
            return Collections.singleton("x");
        }

        @Override
        public int available() {
            return 7;
        }

        @Override
        public void produce(DataStreamChannel channel) throws IOException {
            calls.add("produce");
            lastChannel = channel;
            channel.write(ByteBuffer.wrap(chunk));
        }

        @Override
        public void releaseResources() {
            calls.add("releaseResources");
            if (failOnRelease != null) {
                throw failOnRelease;
            }
        }
    }

    private GuardedEntityProducer guarded(ShareConcurrencyLimiter.Lease lease) {
        return new GuardedEntityProducer(delegate, live::get, lease);
    }

    @Test
    public void delegatesTheDescriptionOfTheResponse() {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));

        assertEquals(4242L, producer.getContentLength());
        assertEquals("video/mp4", producer.getContentType());
        assertEquals("identity", producer.getContentEncoding());
        assertTrue(producer.isChunked());
        assertEquals(Collections.singleton("x"), producer.getTrailerNames());
        assertEquals(7, producer.available());
        assertFalse("never repeatable, whatever the upstream says", producer.isRepeatable());
    }

    @Test
    public void producesThroughTheDelegateWhileTheShareIsLive() throws IOException {
        delegate.chunk = new byte[]{1, 2, 3};
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();

        producer.produce(channel);

        assertEquals(List.of("produce"), delegate.calls);
        assertEquals(3, channel.bytes().length);
    }

    @Test
    public void abortsWithAnIOExceptionWithoutTouchingTheDelegateWhenTheShareIsGone() {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));
        live.set(false);

        try {
            producer.produce(new FakeChannel());
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(delegate.calls.isEmpty());
        }
    }

    @Test
    public void abortsWhenTheWatchdogTookTheLeaseAway() {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS + 1);
        assertEquals(1, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));

        try {
            producer.produce(new FakeChannel());
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(delegate.calls.isEmpty());
        }
        producer.releaseResources();
        assertNotNull("the late end must not give a second slot back", limiter.tryAcquire(KEY));
        assertNull(limiter.tryAcquire(KEY));
    }

    @Test
    public void bytesTheClientAcceptsAreReportedAsProgress() throws IOException {
        delegate.chunk = new byte[ShareLimits.MIN_PROGRESS_BYTES];
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        GuardedEntityProducer producer = guarded(lease);
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS - 1_000L);

        producer.produce(new FakeChannel());
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS - 1_000L);

        assertEquals(0, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
        assertTrue(lease.isLive());
    }

    @Test
    public void aClientThatAcceptsNothingIsNotProgress() throws IOException {
        delegate.chunk = new byte[ShareLimits.MIN_PROGRESS_BYTES];
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        GuardedEntityProducer producer = guarded(lease);
        FakeChannel channel = new FakeChannel();
        channel.maxPerWrite = 0;

        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS - 1_000L);
        producer.produce(channel);
        clock.addAndGet(2_000L);

        assertEquals(1, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
    }

    @Test
    public void theDelegateSeesAChannelThatForwardsEndStreamAndRequestOutput() throws IOException {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);

        delegate.lastChannel.requestOutput();
        delegate.lastChannel.endStream();

        assertEquals(1, channel.outputRequests);
        assertTrue(channel.ended);
    }

    @Test
    public void releaseResourcesReleasesTheDelegateAndTheLease() {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));

        producer.releaseResources();

        assertEquals(List.of("releaseResources"), delegate.calls);
        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void releaseResourcesGivesTheLeaseBackEvenIfTheDelegateThrows() {
        delegate.failOnRelease = new IllegalStateException("boom");
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));

        try {
            producer.releaseResources();
            fail("the delegate's exception is not swallowed");
        } catch (IllegalStateException expected) {
            assertSame(delegate.failOnRelease, expected);
        }
        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void failedTellsTheDelegateAndGivesTheLeaseBack() {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));

        producer.failed(new IOException("connection reset"));

        assertEquals(List.of("failed"), delegate.calls);
        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void failedGivesTheLeaseBackEvenIfTheDelegateThrows() {
        delegate.failOnFailed = new IllegalStateException("boom");
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));

        try {
            producer.failed(new IOException("connection reset"));
            fail("the delegate's exception is not swallowed");
        } catch (IllegalStateException expected) {
            assertEquals(0, limiter.activeCount(KEY));
        }
    }

    @Test
    public void failedThenReleaseResourcesGivesTheLeaseBackExactlyOnce() {
        GuardedEntityProducer producer = guarded(limiter.tryAcquire(KEY));

        producer.failed(new IOException("connection reset"));
        producer.releaseResources();
        producer.releaseResources();

        assertEquals(0, limiter.activeCount(KEY));
        assertNotNull(limiter.tryAcquire(KEY));
        assertNull(limiter.tryAcquire(KEY));
    }
}
