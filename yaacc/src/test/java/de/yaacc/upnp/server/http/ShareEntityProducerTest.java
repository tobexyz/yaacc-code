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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.nio.StreamChannel;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.share.ByteRangeResolver;
import de.yaacc.share.RangeStreamer;
import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;
import de.yaacc.upnp.server.http.ProducerTestSupport.ArraySource;
import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;

/**
 * {@link ShareEntityProducer}: the body of a held-file share, one chunk per "output is ready"
 * callback, driven through HttpCore's public {@code produce(DataStreamChannel)} like the server
 * does, and (for the pending-chunk rule) through {@code produceData} with a raw channel.
 */
public class ShareEntityProducerTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final ShareConcurrencyLimiter limiter = new ShareConcurrencyLimiter(1, clock::get);
    private final AtomicBoolean live = new AtomicBoolean(true);

    private ShareEntityProducer producerFor(ArraySource source, ShareConcurrencyLimiter.Lease lease) {
        return new ShareEntityProducer(new RangeStreamer(source, ProducerTestSupport.whole(source),
                ShareLimits.CHUNK_BYTES), source.size(), ContentType.APPLICATION_OCTET_STREAM, live::get, lease);
    }

    /** Calls produce() as the server does whenever the channel is writable, until the stream ends. */
    private static void drive(ShareEntityProducer producer, FakeChannel channel, int maxCalls) throws IOException {
        for (int i = 0; i < maxCalls && !channel.ended; i++) {
            producer.produce(channel);
        }
        assertTrue("stream did not end within " + maxCalls + " callbacks", channel.ended);
    }

    @Test
    public void streamsTheWholeBodyInOrderAndEndsTheStream() throws IOException {
        byte[] body = ProducerTestSupport.body(3 * ShareLimits.CHUNK_BYTES + 123);
        ArraySource source = new ArraySource(body);
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();

        drive(producer, channel, 20);

        assertArrayEquals(body, channel.bytes());
        assertEquals(body.length, producer.getContentLength());
    }

    @Test
    public void anEmptyRangeEndsTheStreamWithoutData() throws IOException {
        ArraySource source = new ArraySource(new byte[0]);
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();

        drive(producer, channel, 5);

        assertEquals(0, channel.bytes().length);
    }

    @Test
    public void aClientThatTakesFewBytesPerCallLosesAndDuplicatesNothing() throws IOException {
        byte[] body = ProducerTestSupport.body(2 * ShareLimits.CHUNK_BYTES + 777);
        ArraySource source = new ArraySource(body);
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();
        channel.maxPerWrite = 5_000;

        drive(producer, channel, 1_000);

        assertArrayEquals(body, channel.bytes());
    }

    @Test
    public void aClientThatSometimesTakesNothingLosesAndDuplicatesNothing() throws IOException {
        byte[] body = ProducerTestSupport.body(ShareLimits.CHUNK_BYTES + 4_321);
        ArraySource source = new ArraySource(body);
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();
        for (int i = 0; i < 2_000 && !channel.ended; i++) {
            channel.maxPerWrite = i % 3 == 0 ? 0 : 1_500;
            producer.produce(channel);
        }

        assertTrue(channel.ended);
        assertArrayEquals(body, channel.bytes());
    }

    @Test
    public void aChunkTheRawChannelOnlyPartlyTakesIsFinishedBeforeTheNextOneIsRead() throws IOException {
        byte[] body = ProducerTestSupport.body(3_000);
        ArraySource source = new ArraySource(body);
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
        boolean[] ended = new boolean[1];
        StreamChannel<ByteBuffer> raw = new StreamChannel<ByteBuffer>() {
            @Override
            public int write(ByteBuffer src) {
                int n = Math.min(src.remaining(), 700);
                byte[] chunk = new byte[n];
                src.get(chunk);
                received.write(chunk, 0, n);
                return n;
            }

            @Override
            public void endStream() {
                ended[0] = true;
            }
        };

        for (int i = 0; i < 50 && !ended[0]; i++) {
            producer.produceData(raw);
        }

        assertTrue(ended[0]);
        assertArrayEquals(body, received.toByteArray());
    }

    @Test
    public void abortsWithAnIOExceptionAsSoonAsTheShareIsGone() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(3 * ShareLimits.CHUNK_BYTES));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);
        live.set(false);

        try {
            producer.produce(channel);
            fail("expected an IOException");
        } catch (IOException expected) {
            assertFalse(channel.ended);
        }
    }

    @Test
    public void abortsWhenTheSourceIsClosing() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(3 * ShareLimits.CHUNK_BYTES));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);
        source.close();

        try {
            for (int i = 0; i < 10; i++) {
                producer.produce(channel);
            }
            fail("expected an IOException");
        } catch (IOException expected) {
            assertFalse(channel.ended);
        }
    }

    @Test
    public void abortsAfterTheWatchdogOnlyWhenAnotherResponseTookTheSlot() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(4 * ShareLimits.CHUNK_BYTES));
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        ShareEntityProducer producer = producerFor(source, lease);
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS + 1);
        assertEquals(1, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
        // the cap is 1: another response uses the freed slot while this reader is paused
        ShareConcurrencyLimiter.Lease other = limiter.tryAcquire(KEY);
        assertNotNull(other);

        try {
            producer.produce(channel);
            fail("expected an IOException");
        } catch (IOException expected) {
            assertEquals("the other response keeps its slot", 1, limiter.activeCount(KEY));
        }
        // the late end of the aborted response must not give a slot back
        producer.releaseResources();
        assertEquals(1, limiter.activeCount(KEY));
        other.release();
        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void aPausedReaderThatResumesGetsASlotAgainAndFinishesTheDownload() throws IOException {
        byte[] body = ProducerTestSupport.body(4 * ShareLimits.CHUNK_BYTES);
        ArraySource source = new ArraySource(body);
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        ShareEntityProducer producer = producerFor(source, lease);
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS * 3);
        assertEquals(1, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
        assertEquals(0, limiter.activeCount(KEY));

        drive(producer, channel, 20);

        assertArrayEquals("no byte lost or repeated across the pause", body, channel.bytes());
        assertEquals("holds a slot again", 1, limiter.activeCount(KEY));
        producer.releaseResources();
        producer.releaseResources();
        assertEquals(0, limiter.activeCount(KEY));
        assertNotNull(limiter.tryAcquire(KEY));
        assertNull(limiter.tryAcquire(KEY));
    }

    @Test
    public void aResumedResponseStartsAFreshIdleWindow() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(6 * ShareLimits.CHUNK_BYTES));
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        ShareEntityProducer producer = producerFor(source, lease);
        FakeChannel channel = new FakeChannel();
        channel.maxPerWrite = 0;
        producer.produce(channel);
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS * 3);
        limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS);
        producer.produce(channel);
        assertTrue(lease.isLive());

        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS - 1_000L);
        assertEquals(0, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
        clock.addAndGet(2_000L);
        assertEquals(1, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
    }

    @Test
    public void bytesTheClientTookKeepTheLeaseFromExpiring() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(4 * ShareLimits.CHUNK_BYTES));
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        ShareEntityProducer producer = producerFor(source, lease);
        FakeChannel channel = new FakeChannel();
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS - 1_000L);
        producer.produce(channel);
        clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS - 1_000L);

        assertEquals(0, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
        assertTrue(lease.isLive());
    }

    @Test
    public void aClientThatTakesNothingDoesNotKeepTheLeaseFromExpiring() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(4 * ShareLimits.CHUNK_BYTES));
        ShareConcurrencyLimiter.Lease lease = limiter.tryAcquire(KEY);
        ShareEntityProducer producer = producerFor(source, lease);
        FakeChannel channel = new FakeChannel();
        channel.maxPerWrite = 0;
        for (int i = 0; i < 4; i++) {
            clock.addAndGet(ShareLimits.WRITE_IDLE_MILLIS / 2);
            producer.produce(channel);
        }

        assertEquals(1, limiter.expireIdle(ShareLimits.WRITE_IDLE_MILLIS));
    }

    @Test
    public void releaseResourcesGivesTheLeaseBack() {
        ArraySource source = new ArraySource(ProducerTestSupport.body(10));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        assertEquals(1, limiter.activeCount(KEY));

        producer.releaseResources();

        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void failedGivesTheLeaseBack() {
        ArraySource source = new ArraySource(ProducerTestSupport.body(10));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));

        producer.failed(new IOException("connection reset"));

        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void failedThenReleaseResourcesGivesTheLeaseBackExactlyOnce() {
        ArraySource source = new ArraySource(ProducerTestSupport.body(10));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));

        producer.failed(new IOException("connection reset"));
        producer.releaseResources();
        producer.releaseResources();

        assertEquals(0, limiter.activeCount(KEY));
        // one slot only: a double give-back would let two new responses in
        assertNotNull(limiter.tryAcquire(KEY));
        assertNull(limiter.tryAcquire(KEY));
    }

    @Test
    public void theLeaseIsHeldUntilTheResponseEnds() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(100));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();

        drive(producer, channel, 5);
        assertEquals(1, limiter.activeCount(KEY));

        producer.releaseResources();
        assertEquals(0, limiter.activeCount(KEY));
    }

    @Test
    public void theResponseIsNotRepeatableAndNeverClosesTheSource() throws IOException {
        ArraySource source = new ArraySource(ProducerTestSupport.body(100));
        ShareEntityProducer producer = producerFor(source, limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();

        drive(producer, channel, 5);
        producer.releaseResources();

        assertFalse(producer.isRepeatable());
        assertEquals("the registry owns the source", 0, source.closes);
    }

    @Test
    public void aRangeIsServedExactly() throws IOException {
        byte[] body = ProducerTestSupport.body(10_000);
        ArraySource source = new ArraySource(body);
        ByteRangeResolver.Result range = ByteRangeResolver.resolve(
                ByteRangeResolver.parseHeader("bytes=1000-1999"), source.size());
        ShareEntityProducer producer = new ShareEntityProducer(new RangeStreamer(source, range,
                ShareLimits.CHUNK_BYTES), range.length(), ContentType.APPLICATION_OCTET_STREAM, live::get,
                limiter.tryAcquire(KEY));
        FakeChannel channel = new FakeChannel();

        drive(producer, channel, 5);

        byte[] expected = new byte[1000];
        System.arraycopy(body, 1000, expected, 0, 1000);
        assertArrayEquals(expected, channel.bytes());
        assertEquals(1000L, producer.getContentLength());
    }
}
