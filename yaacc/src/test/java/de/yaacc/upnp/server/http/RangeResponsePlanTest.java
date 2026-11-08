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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncPushProducer;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import de.yaacc.util.HttpRange;

/**
 * {@link RangeResponsePlan}: status and Content-Range of the answer to a request, for the FULL size
 * of the resource (issue #294).
 */
public class RangeResponsePlanTest {

    private static final long SIZE = 1000;

    private static RangeResponsePlan plan(String header, long size) {
        return RangeResponsePlan.of(RangeResponsePlan.parseHeader(header), size);
    }

    private static void assertPartial(RangeResponsePlan plan, String contentRange, long start, long length) {
        assertEquals(206, plan.status());
        assertEquals(contentRange, plan.contentRange());
        assertTrue(plan.hasBody());
        assertTrue(plan.isPartial());
        assertEquals(start, plan.window().start());
        assertEquals(length, plan.window().length());
    }

    private static void assertNotSatisfiable(RangeResponsePlan plan, long size) {
        assertEquals(416, plan.status());
        assertEquals("bytes */" + size, plan.contentRange());
        assertFalse("a 416 has no body, so nothing is opened", plan.hasBody());
        assertFalse(plan.isPartial());
        assertEquals(0, plan.window().length());
    }

    // ---- no range

    @Test
    public void noHeaderIs200WithTheWholeResource() {
        RangeResponsePlan plan = plan(null, SIZE);

        assertEquals(200, plan.status());
        assertNull(plan.contentRange());
        assertTrue(plan.hasBody());
        assertFalse(plan.isPartial());
        assertEquals(0, plan.window().start());
        assertEquals(SIZE, plan.window().length());
    }

    @Test
    public void emptyRangeListIs200() {
        assertEquals(200, RangeResponsePlan.of(Collections.emptyList(), SIZE).status());
        assertEquals(200, RangeResponsePlan.of(null, SIZE).status());
    }

    @Test
    public void noRangeOnAnEmptyResourceIs200() {
        RangeResponsePlan plan = plan(null, 0);

        assertEquals(200, plan.status());
        assertEquals(0, plan.window().length());
    }

    // ---- satisfiable

    @Test
    public void closedRangeIs206WithItsContentRange() {
        assertPartial(plan("bytes=100-199", SIZE), "bytes 100-199/1000", 100, 100);
    }

    @Test
    public void openRangeRunsToTheLastByte() {
        assertPartial(plan("bytes=900-", SIZE), "bytes 900-999/1000", 900, 100);
    }

    @Test
    public void firstByteOnlyIsOneByte() {
        assertPartial(plan("bytes=0-0", SIZE), "bytes 0-0/1000", 0, 1);
    }

    @Test
    public void lastByteOnlyIsOneByte() {
        assertPartial(plan("bytes=999-999", SIZE), "bytes 999-999/1000", 999, 1);
    }

    @Test
    public void endBeyondTheResourceIsClipped() {
        assertPartial(plan("bytes=990-5000", SIZE), "bytes 990-999/1000", 990, 10);
    }

    @Test
    public void suffixIsTheTail() {
        assertPartial(plan("bytes=-100", SIZE), "bytes 900-999/1000", 900, 100);
    }

    @Test
    public void suffixLongerThanTheResourceIsTheWholeResourceAs206() {
        assertPartial(plan("bytes=-5000", SIZE), "bytes 0-999/1000", 0, 1000);
    }

    @Test
    public void rangeOfTheWholeResourceStaysAs206() {
        assertPartial(plan("bytes=0-", SIZE), "bytes 0-999/1000", 0, 1000);
    }

    @Test
    public void sizeBeyondTheIntRangeIsExact() {
        long size = 10_000_000_000L;

        assertPartial(plan("bytes=9999999990-", size), "bytes 9999999990-9999999999/10000000000", 9_999_999_990L, 10);
    }

    // ---- not satisfiable

    @Test
    public void startAtTheSizeIs416() {
        assertNotSatisfiable(plan("bytes=1000-", SIZE), SIZE);
    }

    @Test
    public void startBeyondTheSizeIs416() {
        assertNotSatisfiable(plan("bytes=5000-6000", SIZE), SIZE);
    }

    @Test
    public void suffixOfZeroIs416() {
        assertNotSatisfiable(plan("bytes=-0", SIZE), SIZE);
    }

    @Test
    public void endBelowStartIs416() {
        // parses (the parser does not check the order); RFC 9110 14.1.1 lets a server ignore or reject it
        assertNotSatisfiable(plan("bytes=10-5", SIZE), SIZE);
    }

    @Test
    public void anyRangeOfAnEmptyResourceIs416() {
        assertNotSatisfiable(plan("bytes=0-0", 0), 0);
        assertNotSatisfiable(plan("bytes=0-", 0), 0);
        assertNotSatisfiable(plan("bytes=-5", 0), 0);
    }

    @Test
    public void numbersBeyondLongAreIgnoredNotAnError() {
        // the parser throws NumberFormatException; the header is ignored like any unusable one
        RangeResponsePlan plan = plan("bytes=99999999999999999999-", SIZE);

        assertEquals(200, plan.status());
    }

    @Test
    public void firstRangeDecidesWhenThereAreSeveral() {
        assertPartial(plan("bytes=0-9,20-29", SIZE), "bytes 0-9/1000", 0, 10);
        // the first range is not satisfiable: 416, even though the second one would be
        assertNotSatisfiable(plan("bytes=5000-,0-9", SIZE), SIZE);
    }

    // ---- unknown size

    @Test
    public void unknownSizeWithARangeIs206WithoutContentRange() {
        RangeResponsePlan plan = plan("bytes=100-199", -1);

        assertEquals(206, plan.status());
        assertNull(plan.contentRange());
        assertTrue(plan.hasBody());
        assertFalse("there is no window to cut", plan.isPartial());
        assertNull(plan.window());
        assertEquals(-1, plan.size());
    }

    @Test
    public void unknownSizeIsNeverNotSatisfiable() {
        assertEquals(206, plan("bytes=99999999-", -1).status());
        assertEquals(206, plan("bytes=-0", -1).status());
    }

    @Test
    public void unknownSizeWithoutARangeIs200() {
        RangeResponsePlan plan = plan(null, -1);

        assertEquals(200, plan.status());
        assertNull(plan.contentRange());
        assertNull(plan.window());
    }

    // ---- headers that are not usable are ignored

    @Test
    public void unusableHeadersGiveNoRangesAndNeverThrow() {
        String[] unusable = {
                "", "   ", "garbage", "bytes", "bytes=", "bytes=abc", "bytes=-", "bytes=--5", "bytes=1-2-3",
                "bytes=0-5,x", "items=0-5", "pages=1-2", "=0-5", "bytes 0-5", "bytes=0-5;q=1",
                "Bytes=0-5", "bytes=0-5, 6-10", "bytes=٣-٥", "bytes=0x10-0x20",
                "bytes=99999999999999999999-", "bytes=0-99999999999999999999"
        };
        for (String header : unusable) {
            List<HttpRange> ranges = RangeResponsePlan.parseHeader(header);
            assertTrue("ignored: '" + header + "'", ranges.isEmpty());
            assertEquals("200 for '" + header + "'", 200, RangeResponsePlan.of(ranges, SIZE).status());
        }
    }

    @Test
    public void aHeaderOverTheLimitIsIgnored() {
        StringBuilder header = new StringBuilder("bytes=0-1");
        while (header.length() <= RangeResponsePlan.MAX_HEADER_LENGTH) {
            header.append(",0-1");
        }

        assertTrue(RangeResponsePlan.parseHeader(header.toString()).isEmpty());
    }

    @Test
    public void surroundingWhitespaceIsTolerated() {
        assertPartial(plan("  bytes=0-9 ", SIZE), "bytes 0-9/1000", 0, 10);
    }

    @Test
    public void severalValidRangesAreKeptForTheCaller() {
        assertEquals(2, RangeResponsePlan.parseHeader("bytes=0-9,20-29").size());
    }

    // ---- applyTo

    /** The response head the builder would send. */
    private static HttpResponse responseOf(AsyncResponseBuilder builder) {
        HttpResponse[] sent = new HttpResponse[1];
        try {
            builder.build().sendResponse(new ResponseChannel() {
                @Override
                public void sendInformation(HttpResponse response, HttpContext context) {
                    throw new AssertionError("no 1xx expected");
                }

                @Override
                public void sendResponse(HttpResponse response, EntityDetails entityDetails, HttpContext context) {
                    sent[0] = response;
                }

                @Override
                public void pushPromise(HttpRequest promise, AsyncPushProducer pushProducer, HttpContext context) {
                    throw new AssertionError("no push expected");
                }
            }, null);
        } catch (HttpException | IOException e) {
            throw new AssertionError(e);
        }
        return sent[0];
    }

    @Test
    public void applyToSetsStatusAndContentRange() {
        AsyncResponseBuilder builder = AsyncResponseBuilder.create(200);

        plan("bytes=100-199", SIZE).applyTo(builder);
        HttpResponse response = responseOf(builder);

        assertEquals(206, response.getCode());
        assertEquals("bytes 100-199/1000", response.getFirstHeader(HttpHeaders.CONTENT_RANGE).getValue());
    }

    @Test
    public void applyToSetsTheUnsatisfiedContentRangeOf416() {
        AsyncResponseBuilder builder = AsyncResponseBuilder.create(200);

        plan("bytes=1000-", SIZE).applyTo(builder);
        HttpResponse response = responseOf(builder);

        assertEquals(416, response.getCode());
        assertEquals("bytes */1000", response.getFirstHeader(HttpHeaders.CONTENT_RANGE).getValue());
    }

    @Test
    public void applyToSetsNoContentRangeFor200AndForAnUnknownSize() {
        AsyncResponseBuilder whole = AsyncResponseBuilder.create(500);
        plan(null, SIZE).applyTo(whole);
        AsyncResponseBuilder unknown = AsyncResponseBuilder.create(500);
        plan("bytes=0-9", -1).applyTo(unknown);

        assertEquals(200, responseOf(whole).getCode());
        assertNull(responseOf(whole).getFirstHeader(HttpHeaders.CONTENT_RANGE));
        assertEquals(206, responseOf(unknown).getCode());
        assertNull(responseOf(unknown).getFirstHeader(HttpHeaders.CONTENT_RANGE));
        assertNotNull(plan("bytes=0-9", SIZE).window());
    }
}
