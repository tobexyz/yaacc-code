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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;

/**
 * {@link RangedStreamEntityProducer}: the ranged body read from a stream (SAF documents), against
 * streams as content providers deliver them: short reads, short skips, {@code available()} of 0.
 * The producer is driven like HttpCore 5.3.6 drives it, see {@link RangedFileEntityProducerTest}.
 */
public class RangedStreamEntityProducerTest {

    private static final byte[] DATA = ProducerTestSupport.body(300_000);

    /** A stream that is as unhelpful as the contract allows. */
    private static final class AwkwardStream extends InputStream {
        final byte[] data;
        int position;
        int maxSkip = Integer.MAX_VALUE;
        int maxRead = Integer.MAX_VALUE;
        int closes;
        int opens;

        AwkwardStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int n = Math.min(Math.min(length, maxRead), data.length - position);
            if (n <= 0) {
                return -1;
            }
            System.arraycopy(data, position, buffer, offset, n);
            position += n;
            return n;
        }

        @Override
        public long skip(long count) {
            int n = (int) Math.min(Math.min(count, maxSkip), data.length - position);
            position += n;
            return n;
        }

        /** Like a pipe or a provider that has nothing buffered: never says that data is there. */
        @Override
        public int available() {
            return 0;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static RangedStreamEntityProducer producer(AwkwardStream stream, long start, long length) {
        return new RangedStreamEntityProducer(ContentType.APPLICATION_OCTET_STREAM, () -> {
            stream.opens++;
            return stream;
        }, start, length);
    }

    private static void drive(AsyncEntityProducer producer, FakeChannel channel) throws IOException {
        producer.produce(channel);
        int events = 1;
        while (!channel.ended && producer.available() > 0) {
            assertTrue("producer does not make progress", events++ < 100_000);
            producer.produce(channel);
        }
    }

    private static void assertServes(AwkwardStream stream, long start, long length) throws IOException {
        RangedStreamEntityProducer producer = producer(stream, start, length);
        FakeChannel channel = new FakeChannel();

        drive(producer, channel);

        assertEquals(length, producer.getContentLength());
        assertArrayEquals(Arrays.copyOfRange(DATA, (int) start, (int) (start + length)), channel.bytes());
        assertTrue("stream ended", channel.ended);
        assertEquals("input closed once", 1, stream.closes);
        assertFalse(producer.isOpen());
    }

    @Test
    public void rangeInTheMiddleIsCompleteAndEnded() throws IOException {
        assertServes(new AwkwardStream(DATA), 100_000, 50_000);
    }

    @Test
    public void rangeLengthIsAMultipleOfTheChunkSize() throws IOException {
        assertServes(new AwkwardStream(DATA), 8192, 16_384);
    }

    @Test
    public void rangeEndingAtTheEndOfTheStream() throws IOException {
        assertServes(new AwkwardStream(DATA), 250_000, 50_000);
    }

    @Test
    public void wholeStream() throws IOException {
        assertServes(new AwkwardStream(DATA), 0, DATA.length);
    }

    @Test
    public void streamThatSkipsLessThanAskedIsSkippedToTheStart() throws IOException {
        AwkwardStream stream = new AwkwardStream(DATA);
        stream.maxSkip = 1000;

        assertServes(stream, 100_000, 20_000);
    }

    @Test
    public void streamThatSkipsNothingIsReadToTheStart() throws IOException {
        AwkwardStream stream = new AwkwardStream(DATA);
        stream.maxSkip = 0;

        assertServes(stream, 3_000, 20_000);
    }

    @Test
    public void streamWithShortReadsAndNothingAvailableIsServedToTheEnd() throws IOException {
        AwkwardStream stream = new AwkwardStream(DATA);
        stream.maxRead = 3_000;

        assertServes(stream, 1_000, 100_000);
    }

    @Test
    public void slowClientThatAcceptsPartsOfAWriteGetsEveryByte() throws IOException {
        RangedStreamEntityProducer producer = producer(new AwkwardStream(DATA), 1_000, 200_000);
        FakeChannel channel = new FakeChannel();
        channel.maxPerWrite = 1000;

        drive(producer, channel);

        assertArrayEquals(Arrays.copyOfRange(DATA, 1_000, 201_000), channel.bytes());
        assertTrue(channel.ended);
    }

    @Test
    public void emptyBodyEndsAtOnceWithoutOpeningTheStream() throws IOException {
        AwkwardStream stream = new AwkwardStream(DATA);
        RangedStreamEntityProducer producer = producer(stream, 0, 0);
        FakeChannel channel = new FakeChannel();

        drive(producer, channel);

        assertEquals(0, channel.bytes().length);
        assertTrue(channel.ended);
        assertEquals(0, stream.opens);
    }

    @Test
    public void streamThatEndsBeforeTheAnnouncedLengthFailsTheResponse() throws IOException {
        AwkwardStream stream = new AwkwardStream(Arrays.copyOf(DATA, 20_000));
        RangedStreamEntityProducer producer = producer(stream, 0, 50_000);
        FakeChannel channel = new FakeChannel();

        try {
            drive(producer, channel);
            fail("a body shorter than its Content-Length must not look complete");
        } catch (IOException expected) {
            assertFalse(channel.ended);
        }

        assertEquals(1, stream.closes);
        assertFalse(producer.isOpen());
    }

    @Test
    public void streamThatEndsBeforeTheStartFailsTheResponse() {
        AwkwardStream stream = new AwkwardStream(Arrays.copyOf(DATA, 1_000));
        stream.maxSkip = 0;
        RangedStreamEntityProducer producer = producer(stream, 5_000, 100);

        try {
            producer.produce(new FakeChannel());
            fail("a range behind the end of the stream cannot be served");
        } catch (IOException expected) {
            assertEquals(1, stream.closes);
            assertFalse(producer.isOpen());
        }
    }

    @Test
    public void streamThatCannotBeOpenedFailsTheResponse() {
        RangedStreamEntityProducer producer = new RangedStreamEntityProducer(
                ContentType.APPLICATION_OCTET_STREAM, () -> {
            throw new IOException("permission revoked");
        }, 0, 100);

        try {
            producer.produce(new FakeChannel());
            fail("an unreadable document must fail the response");
        } catch (IOException expected) {
            assertEquals("permission revoked", expected.getMessage());
            assertFalse(producer.isOpen());
        }
    }

    @Test
    public void inputIsClosedWhenTheExchangeFailsOrIsReleased() throws IOException {
        AwkwardStream failed = new AwkwardStream(DATA);
        RangedStreamEntityProducer failedProducer = producer(failed, 0, 100_000);
        failedProducer.produce(new FakeChannel());
        assertTrue(failedProducer.isOpen());
        failedProducer.failed(new IOException("connection reset"));
        failedProducer.releaseResources();
        assertEquals(1, failed.closes);

        AwkwardStream released = new AwkwardStream(DATA);
        RangedStreamEntityProducer releasedProducer = producer(released, 0, 100_000);
        releasedProducer.produce(new FakeChannel());
        releasedProducer.releaseResources();
        releasedProducer.releaseResources();
        assertEquals(1, released.closes);
        assertFalse(releasedProducer.isOpen());
    }

    @Test
    public void inputIsClosedWhenTheChannelFailsToWrite() {
        AwkwardStream stream = new AwkwardStream(DATA);
        RangedStreamEntityProducer producer = producer(stream, 0, 100_000);
        FakeChannel channel = new FakeChannel();
        channel.failOnWrite = new IOException("broken pipe");

        try {
            producer.produce(channel);
            fail("the failure of the channel has to reach HttpCore");
        } catch (IOException expected) {
            assertEquals(1, stream.closes);
            assertFalse(producer.isOpen());
        }
    }

    @Test
    public void isNotRepeatable() {
        assertFalse(producer(new AwkwardStream(DATA), 0, 1).isRepeatable());
    }
}
