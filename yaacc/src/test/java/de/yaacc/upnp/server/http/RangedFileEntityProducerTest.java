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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;
import de.yaacc.util.HttpRange;

/**
 * {@link RangedFileEntityProducer}: the streaming body of a ranged answer for a local file
 * (issue #294). The producer is driven like httpcore5 5.3.6 drives it (see {@link #drive}).
 */
public class RangedFileEntityProducerTest {

    private static final long BIG = 120L * 1024 * 1024;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The byte at {@code position} of every test file. */
    private static byte patternAt(long position) {
        return (byte) ((position * 31 + (position >> 8) * 7) & 0xFF);
    }

    private static byte[] expected(long from, long length) {
        byte[] data = new byte[(int) length];
        for (int i = 0; i < length; i++) {
            data[i] = patternAt(from + i);
        }
        return data;
    }

    private File fullFile(int size) throws IOException {
        File file = tmp.newFile();
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(expected(0, size));
        }
        return file;
    }

    /** A sparse file larger than the 50 MB threshold of the handler, patterned only in [from, from + length). */
    private File bigSparseFile(long from, int length) throws IOException {
        File file = tmp.newFile();
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(BIG);
            raf.seek(from);
            raf.write(expected(from, length));
        }
        return file;
    }

    private static List<HttpRange> header(String value) {
        return HttpRange.parseRangeHeader(value);
    }

    private static RangedFileEntityProducer producer(File file, List<HttpRange> ranges) {
        return new RangedFileEntityProducer(file, ContentType.APPLICATION_OCTET_STREAM,
                RangeWindow.resolve(ranges.isEmpty() ? null : ranges.get(0), file.length()));
    }

    /**
     * What HttpCore 5.3.6 does with a response entity (verified in the bytecode of
     * AbstractHttp1StreamDuplexer#onOutput and AbstractBinAsyncEntityProducer): per output event
     * {@code produce()} is called once, whether or not data is announced; after it the output
     * event is only kept (and {@code produce()} called again) while {@code available() > 0}. A
     * producer that does not end its stream before {@code available()} drops to 0 is never
     * called again.
     */
    private static void drive(AsyncEntityProducer producer, FakeChannel channel) throws IOException {
        producer.produce(channel);
        int events = 1;
        while (!channel.ended && producer.available() > 0) {
            assertTrue("producer does not make progress", events++ < 100_000);
            producer.produce(channel);
        }
    }

    private void assertServes(File file, String rangeHeader, long from, long length) throws IOException {
        RangedFileEntityProducer producer = producer(file, header(rangeHeader));
        FakeChannel channel = new FakeChannel();

        drive(producer, channel);

        assertEquals("Content-Length of " + rangeHeader, length, producer.getContentLength());
        assertArrayEquals("body of " + rangeHeader, expected(from, length), channel.bytes());
        assertTrue("stream ended for " + rangeHeader, channel.ended);
    }

    @Test
    public void reportersRangeOfALargeFileIsCompleteAndEnded() throws IOException {
        // 500000 bytes: 61 full 8192-byte chunks and a partial one that used to stay in the buffer
        assertServes(bigSparseFile(100_000_000L, 500_000), "bytes=100000000-100499999", 100_000_000L, 500_000);
    }

    @Test
    public void rangeEndingAtTheEndOfTheFile() throws IOException {
        File file = fullFile(300_000);

        assertServes(file, "bytes=250000-", 250_000, 50_000);
        assertServes(file, "bytes=250000-299999", 250_000, 50_000);
    }

    @Test
    public void rangeLengthIsAMultipleOfTheChunkSize() throws IOException {
        // 16384 = 2 * 8192: every chunk is full; the stream still has to be ended
        assertServes(fullFile(100_000), "bytes=8192-24575", 8192, 16_384);
    }

    @Test
    public void rangeSmallerThanOneChunk() throws IOException {
        assertServes(fullFile(100_000), "bytes=1000-1099", 1000, 100);
    }

    @Test
    public void rangeOfTheWholeFile() throws IOException {
        File file = fullFile(300_000);

        assertServes(file, "bytes=0-", 0, 300_000);
        assertServes(file, "bytes=0-299999", 0, 300_000);
    }

    @Test
    public void slowClientThatAcceptsPartsOfAWriteGetsEveryByte() throws IOException {
        File file = fullFile(300_000);
        RangedFileEntityProducer producer = producer(file, header("bytes=1000-200999"));
        FakeChannel channel = new FakeChannel();
        channel.maxPerWrite = 1000;

        drive(producer, channel);

        assertArrayEquals(expected(1000, 200_000), channel.bytes());
        assertTrue(channel.ended);
    }

    @Test
    public void noRangeServesTheWholeFile() throws IOException {
        File file = fullFile(70_000);
        RangedFileEntityProducer producer = producer(file, java.util.Collections.emptyList());
        FakeChannel channel = new FakeChannel();

        drive(producer, channel);

        assertEquals(70_000, producer.getContentLength());
        assertArrayEquals(expected(0, 70_000), channel.bytes());
        assertTrue(channel.ended);
    }

    @Test
    public void bytesZeroToZeroIsTheFirstByteOnly() throws IOException {
        File file = fullFile(300_000);

        assertServes(file, "bytes=0-0", 0, 1);
        assertServes(file, "bytes=5-5", 5, 1);
    }

    @Test
    public void endBeyondTheFileIsClippedToTheLastByte() throws IOException {
        assertServes(fullFile(300_000), "bytes=299000-999999999", 299_000, 1_000);
    }

    @Test
    public void suffixRangeIsTheTail() throws IOException {
        File file = fullFile(300_000);

        assertServes(file, "bytes=-1000", 299_000, 1_000);
        assertServes(file, "bytes=-8192", 291_808, 8_192);
    }

    @Test
    public void suffixLongerThanTheFileIsTheWholeFile() throws IOException {
        assertServes(fullFile(30_000), "bytes=-999999", 0, 30_000);
    }

    @Test
    public void startAtOrBeyondTheEndIsAnEmptyBodyThatEnds() throws IOException {
        File file = fullFile(30_000);

        assertServes(file, "bytes=30000-", 30_000, 0);
        assertServes(file, "bytes=999999-", 30_000, 0);
        assertServes(file, "bytes=999999-1999999", 30_000, 0);
    }

    @Test
    public void emptyFileHasAnEmptyBodyThatEnds() throws IOException {
        assertServes(fullFile(0), "bytes=0-", 0, 0);
    }

    @Test
    public void endBelowStartIsAnEmptyBodyThatEnds() throws IOException {
        assertServes(fullFile(30_000), "bytes=500-100", 30_000, 0);
    }

    @Test
    public void isNotRepeatable() throws IOException {
        assertFalse(producer(fullFile(10), header("bytes=0-")).isRepeatable());
    }

    @Test
    public void fileIsOpenedByTheFirstProduceAndClosedWhenTheBodyIsComplete() throws IOException {
        RangedFileEntityProducer producer = producer(fullFile(100_000), header("bytes=0-"));
        FakeChannel channel = new FakeChannel();

        assertFalse("nothing opened before the response is sent", producer.isOpen());
        producer.produce(channel);
        assertTrue(producer.isOpen());
        drive(producer, channel);

        assertTrue(channel.ended);
        assertFalse(producer.isOpen());
    }

    @Test
    public void fileIsClosedWhenTheExchangeFails() throws IOException {
        RangedFileEntityProducer producer = producer(fullFile(100_000), header("bytes=0-"));
        producer.produce(new FakeChannel());
        assertTrue(producer.isOpen());

        producer.failed(new IOException("connection reset"));

        assertFalse(producer.isOpen());
    }

    @Test
    public void fileIsClosedWhenTheResponseIsReleasedBeforeTheEnd() throws IOException {
        RangedFileEntityProducer producer = producer(fullFile(100_000), header("bytes=0-"));
        producer.produce(new FakeChannel());
        assertTrue(producer.isOpen());

        producer.releaseResources();
        producer.releaseResources();

        assertFalse(producer.isOpen());
    }

    @Test
    public void fileIsClosedWhenTheChannelFailsToWrite() throws IOException {
        RangedFileEntityProducer producer = producer(fullFile(100_000), header("bytes=0-"));
        FakeChannel channel = new FakeChannel();
        channel.failOnWrite = new IOException("broken pipe");

        try {
            producer.produce(channel);
            fail("the failure of the channel has to reach HttpCore");
        } catch (IOException expected) {
            assertEquals("broken pipe", expected.getMessage());
        }

        assertFalse(producer.isOpen());
    }

    @Test
    public void fileThatShrankDuringTheResponseFailsInsteadOfEndingQuietly() throws IOException {
        File file = fullFile(100_000);
        RangedFileEntityProducer producer = producer(file, header("bytes=0-"));
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(10_000);
        }

        try {
            drive(producer, channel);
            fail("a body shorter than its Content-Length must not look complete");
        } catch (IOException expected) {
            assertFalse(channel.ended);
        }

        assertFalse(producer.isOpen());
    }

    @Test
    public void missingFileFailsTheFirstProduceAndLeavesNothingOpen() throws IOException {
        File file = fullFile(1_000);
        RangedFileEntityProducer producer = producer(file, header("bytes=0-"));
        assertTrue(file.delete());

        try {
            producer.produce(new FakeChannel());
            fail("an unreadable file must fail the response");
        } catch (IOException expected) {
            assertFalse(producer.isOpen());
        }
    }
}
