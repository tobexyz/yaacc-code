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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;

import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.seamless.util.MimeType;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;
import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler.ContentHolder;
import de.yaacc.util.HttpRange;

/**
 * The files up to 50 MB used to be answered from memory ({@code readRangeFormFile}), now they are
 * streamed like the big ones (issue #294). This compares the old body with the new one for the
 * requests the old code answered correctly, and pins the ones it answered wrongly.
 */
public class SmallFileRangeCompatTest {

    private static final int SIZE = 100_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The removed in-memory code, verbatim apart from the logging and the types around it. */
    private static byte[] legacyReadRange(File file, List<HttpRange> ranges) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileSize = raf.length();
            long startPosition;
            long rangeLength;
            if (ranges.isEmpty()) {
                startPosition = 0;
                rangeLength = fileSize;
            } else {
                HttpRange range = ranges.get(0);
                startPosition = range.getStart() == null ? 0 : range.getStart();
                if (range.getEnd() == null || range.getEnd() == 0) {
                    rangeLength = fileSize;
                } else {
                    rangeLength = range.getEnd() - startPosition;
                }
                if (range.getSuffixLength() != null && range.getSuffixLength() > 0) {
                    startPosition = fileSize - range.getSuffixLength();
                    rangeLength = range.getSuffixLength();
                }
            }
            if (startPosition < 0 || startPosition + rangeLength > fileSize) {
                rangeLength = fileSize - startPosition;
            }
            raf.seek(startPosition);
            byte[] buffer = new byte[(int) rangeLength];
            raf.read(buffer);
            return buffer;
        }
    }

    private File file() throws IOException {
        File file = tmp.newFile();
        Files.write(file.toPath(), ProducerTestSupport.body(SIZE));
        return file;
    }

    private static byte[] bodyOf(AsyncEntityProducer producer) throws IOException {
        FakeChannel channel = new FakeChannel();
        producer.produce(channel);
        int events = 1;
        while (!channel.ended && producer.available() > 0) {
            assertTrue("producer does not make progress", events++ < 100_000);
            producer.produce(channel);
        }
        assertTrue("stream ended", channel.ended);
        return channel.bytes();
    }

    private static ContentHolder holder(File file, String header) {
        return new ContentHolder(MimeType.valueOf("video/mp4"), file.getAbsolutePath(),
                RangeResponsePlan.parseHeader(header), mock(Context.class));
    }

    private void assertSameAsLegacy(File file, String header) throws IOException {
        byte[] legacy = legacyReadRange(file, HttpRange.parseRangeHeader(header));

        ContentHolder holder = holder(file, header);
        AsyncEntityProducer producer = holder.getEntityProducer();
        byte[] now = bodyOf(producer);

        assertArrayEquals("body of " + header, legacy, now);
        assertEquals("Content-Length of " + header, legacy.length, producer.getContentLength());
        assertEquals("the plan announces what is sent for " + header, legacy.length,
                holder.getRangePlan().window().length());
    }

    @Test
    public void rangesTheOldCodeAnsweredCorrectlyAreUnchanged() throws IOException {
        File file = file();

        assertSameAsLegacy(file, "bytes=0-");          // the whole file
        assertSameAsLegacy(file, "bytes=1000-");       // open end
        assertSameAsLegacy(file, "bytes=99999-");      // last byte, open end
        assertSameAsLegacy(file, "bytes=-1");          // suffix
        assertSameAsLegacy(file, "bytes=-5000");
        assertSameAsLegacy(file, "bytes=-100000");     // suffix as long as the file
        assertSameAsLegacy(file, "bytes=99000-120000"); // end beyond the file
        assertSameAsLegacy(file, "bytes=500-100000");  // end one beyond the last byte
    }

    @Test
    public void aClosedRangeNowHasTheLengthThatContentRangeAnnounces() throws IOException {
        File file = file();
        byte[] all = ProducerTestSupport.body(SIZE);

        // before: end - start = 99 bytes under "Content-Range: bytes 100-199/100000"
        assertEquals(99, legacyReadRange(file, HttpRange.parseRangeHeader("bytes=100-199")).length);
        ContentHolder holder = holder(file, "bytes=100-199");
        byte[] now = bodyOf(holder.getEntityProducer());

        assertEquals("bytes 100-199/100000", holder.getRangePlan().contentRange());
        assertArrayEquals(java.util.Arrays.copyOfRange(all, 100, 200), now);
    }

    @Test
    public void firstByteOnlyIsOneByteNotTheWholeFile() throws IOException {
        File file = file();

        // before: an end of 0 meant "no end" below 50 MB
        assertEquals(SIZE, legacyReadRange(file, HttpRange.parseRangeHeader("bytes=0-0")).length);
        assertArrayEquals(new byte[]{ProducerTestSupport.body(1)[0]},
                bodyOf(holder(file, "bytes=0-0").getEntityProducer()));
    }

    @Test
    public void aStartBeyondTheEndIsAnswered416AndNeverReadsTheFile() throws IOException {
        File file = file();

        // before: NegativeArraySizeException
        try {
            legacyReadRange(file, HttpRange.parseRangeHeader("bytes=200000-"));
            org.junit.Assert.fail("the old code threw");
        } catch (NegativeArraySizeException expected) {
            // as documented
        }
        ContentHolder holder = holder(file, "bytes=200000-");
        assertEquals(416, holder.getRangePlan().status());
        assertEquals("bytes */100000", holder.getRangePlan().contentRange());
    }

    @Test
    public void noRangeStillSendsTheFileItself() throws IOException {
        File file = file();

        AsyncEntityProducer producer = new ContentHolder(MimeType.valueOf("video/mp4"), file.getAbsolutePath(),
                Collections.emptyList(), mock(Context.class)).getEntityProducer();

        assertEquals(SIZE, producer.getContentLength());
        assertArrayEquals(ProducerTestSupport.body(SIZE), bodyOf(producer));
    }

    @Test
    public void aRangeOfAnInMemoryResourceSendsOnlyThatSlice() throws IOException {
        byte[] content = ProducerTestSupport.body(5000);
        ContentHolder holder = new ContentHolder(MimeType.valueOf("image/png"), content,
                RangeResponsePlan.parseHeader("bytes=10-19"), mock(Context.class));

        AsyncEntityProducer producer = holder.getEntityProducer();

        assertEquals("bytes 10-19/5000", holder.getRangePlan().contentRange());
        assertEquals(10, producer.getContentLength());
        assertArrayEquals(java.util.Arrays.copyOfRange(content, 10, 20), bodyOf(producer));
    }

    @Test
    public void anInMemoryResourceWithoutARangeIsSentWhole() throws IOException {
        byte[] content = ProducerTestSupport.body(5000);
        ContentHolder holder = new ContentHolder(MimeType.valueOf("image/png"), content,
                Collections.emptyList(), mock(Context.class));

        assertEquals(200, holder.getRangePlan().status());
        assertArrayEquals(content, bodyOf(holder.getEntityProducer()));
    }

    @Test
    public void aLiveStreamIgnoresRangesAndAnswers200() {
        java.io.InputStream stream = new java.io.ByteArrayInputStream(new byte[0]);
        ContentHolder holder = new YaaccUpnpServerContentHttpHandler.LiveStreamContentHolder(
                MimeType.valueOf("audio/wav"), stream, mock(Context.class));

        RangeResponsePlan plan = holder.getRangePlan();

        assertEquals(200, plan.status());
        assertEquals(-1, plan.size());
    }
}
