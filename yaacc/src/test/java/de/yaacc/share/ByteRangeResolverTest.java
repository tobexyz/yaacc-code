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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import de.yaacc.share.ByteRangeResolver.Range;
import de.yaacc.share.ByteRangeResolver.Result;

/**
 * Unit tests for {@link ByteRangeResolver}: status (200/206/416), the bytes to send and the
 * {@code Content-Range} text for a single-range server, and the never-throwing parser of the raw
 * {@code Range} header. Plain JVM. The decisions are documented on the class.
 */
public class ByteRangeResolverTest {

    private static final long TOTAL = 1000L;

    private static List<Range> ranges(Range... values) {
        return Arrays.asList(values);
    }

    private static Result serve(String header, long total) {
        return ByteRangeResolver.resolve(ByteRangeResolver.parseHeader(header), total);
    }

    private static void assertWhole(Result result, long total) {
        assertNotNull(result);
        assertEquals(200, result.status());
        assertEquals(0L, result.start());
        assertEquals(total - 1, result.endInclusive());
        assertEquals(total, result.length());
        assertNull(result.contentRangeHeader());
    }

    private static void assertPartial(Result result, long start, long endInclusive, long total) {
        assertNotNull(result);
        assertEquals(206, result.status());
        assertEquals(start, result.start());
        assertEquals(endInclusive, result.endInclusive());
        assertEquals(endInclusive - start + 1, result.length());
        assertEquals("bytes " + start + "-" + endInclusive + "/" + total, result.contentRangeHeader());
    }

    private static void assertUnsatisfiable(Result result, long total) {
        assertNotNull(result);
        assertEquals(416, result.status());
        assertEquals(0L, result.length());
        assertEquals("bytes */" + total, result.contentRangeHeader());
    }

    // ---- resolve: no range

    @Test
    public void nullRangesServeTheWholeResource() {
        assertWhole(ByteRangeResolver.resolve(null, TOTAL), TOTAL);
    }

    @Test
    public void emptyRangesServeTheWholeResource() {
        assertWhole(ByteRangeResolver.resolve(Collections.<Range>emptyList(), TOTAL), TOTAL);
    }

    // ---- resolve: single range

    @Test
    public void closedRangeIsServedAsPartialContent() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, 99)), TOTAL), 0, 99, TOTAL);
        assertEquals("bytes 0-99/1000",
                ByteRangeResolver.resolve(ranges(Range.of(0, 99)), TOTAL).contentRangeHeader());
    }

    @Test
    public void rangeInTheMiddleIsServed() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(200, 299)), TOTAL), 200, 299, TOTAL);
    }

    @Test
    public void singleByteRangesAreServed() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, 0)), TOTAL), 0, 0, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(999, 999)), TOTAL), 999, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(500, 500)), TOTAL), 500, 500, TOTAL);
    }

    @Test
    public void openEndedRangeRunsToTheLastByte() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.from(100)), TOTAL), 100, 999, TOTAL);
        assertEquals(900L, ByteRangeResolver.resolve(ranges(Range.from(100)), TOTAL).length());
    }

    @Test
    public void openEndCanBeWrittenAsLastMinusOne() {
        assertEquals(Range.from(100), Range.of(100, ByteRangeResolver.OPEN));
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(100, -1)), TOTAL), 100, 999, TOTAL);
    }

    @Test
    public void openRangeFromZeroIsPartialContentOfEverything() {
        // documented: a satisfiable single range is always 206, also when it covers it all
        assertPartial(ByteRangeResolver.resolve(ranges(Range.from(0)), TOTAL), 0, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, 999)), TOTAL), 0, 999, TOTAL);
    }

    @Test
    public void openRangeFromTheLastByteServesOneByte() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.from(999)), TOTAL), 999, 999, TOTAL);
    }

    @Test
    public void suffixRangeServesTheLastBytes() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(100)), TOTAL), 900, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(1)), TOTAL), 999, 999, TOTAL);
    }

    @Test
    public void suffixExactlyAsLongAsTheResourceServesEverything() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(1000)), TOTAL), 0, 999, TOTAL);
    }

    @Test
    public void suffixLongerThanTheResourceServesEverythingAsPartialContent() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(1001)), TOTAL), 0, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(5000)), TOTAL), 0, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(Long.MAX_VALUE)), TOTAL), 0, 999, TOTAL);
    }

    @Test
    public void lastPositionBeyondTheEndIsClipped() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(900, 5000)), TOTAL), 900, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, 1000)), TOTAL), 0, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(999, 1000)), TOTAL), 999, 999, TOTAL);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, Long.MAX_VALUE)), TOTAL), 0, 999, TOTAL);
    }

    // ---- resolve: not satisfiable (416)

    @Test
    public void startAtTheLengthIsNotSatisfiable() {
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.from(1000)), TOTAL), TOTAL);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.of(1000, 1100)), TOTAL), TOTAL);
        assertEquals("bytes */1000",
                ByteRangeResolver.resolve(ranges(Range.from(1000)), TOTAL).contentRangeHeader());
    }

    @Test
    public void startBeyondTheLengthIsNotSatisfiable() {
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.from(1001)), TOTAL), TOTAL);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.of(5000, 6000)), TOTAL), TOTAL);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.from(Long.MAX_VALUE)), TOTAL), TOTAL);
        assertUnsatisfiable(
                ByteRangeResolver.resolve(ranges(Range.of(Long.MAX_VALUE, Long.MAX_VALUE)), TOTAL), TOTAL);
    }

    @Test
    public void suffixOfZeroLengthIsNotSatisfiable() {
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.suffix(0)), TOTAL), TOTAL);
    }

    // ---- resolve: ranges that are ignored (200, whole resource)

    @Test
    public void lastBelowFirstIsInvalidAndIgnored() {
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(10, 5)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(999, 0)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(2000, 1500)), TOTAL), TOTAL);
    }

    @Test
    public void negativePositionsAreInvalidAndIgnored() {
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(-5, 10)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(-5, -1)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(0, -2)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.from(-1)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.suffix(-1)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.suffix(Long.MIN_VALUE)), TOTAL), TOTAL);
    }

    @Test
    public void aNullElementIsInvalidAndIgnored() {
        assertWhole(ByteRangeResolver.resolve(Collections.<Range>singletonList(null), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(Arrays.asList(Range.of(0, 9), null), TOTAL), TOTAL);
    }

    @Test
    public void severalRangesAreNotServedAsRangesAndGiveTheWholeResource() {
        // documented decision: single-range server, no multipart/byteranges
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(0, 1), Range.of(5, 9)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(0, 1), Range.suffix(5), Range.from(900)), TOTAL), TOTAL);
    }

    @Test
    public void severalRangesGiveTheWholeResourceEvenIfOneOfThemIsNotSatisfiable() {
        assertWhole(ByteRangeResolver.resolve(ranges(Range.of(0, 1), Range.from(5000)), TOTAL), TOTAL);
        assertWhole(ByteRangeResolver.resolve(ranges(Range.from(5000), Range.from(6000)), TOTAL), TOTAL);
    }

    // ---- resolve: zero-length and unknown-length resources

    @Test
    public void emptyResourceWithoutRangeIsAnEmptyOk() {
        Result result = ByteRangeResolver.resolve(null, 0L);

        assertEquals(200, result.status());
        assertEquals(0L, result.start());
        assertEquals(-1L, result.endInclusive());
        assertEquals(0L, result.length());
        assertNull(result.contentRangeHeader());
    }

    @Test
    public void everyRangeOnAnEmptyResourceIsNotSatisfiable() {
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.from(0)), 0L), 0L);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.of(0, 0)), 0L), 0L);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.of(0, 99)), 0L), 0L);
        assertEquals("bytes */0",
                ByteRangeResolver.resolve(ranges(Range.from(0)), 0L).contentRangeHeader());
    }

    @Test
    public void suffixOnAnEmptyResourceIsNotSatisfiable() {
        // documented: nothing to select, so 416 (not an empty 206)
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.suffix(5)), 0L), 0L);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.suffix(0)), 0L), 0L);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.suffix(Long.MAX_VALUE)), 0L), 0L);
    }

    @Test
    public void rangesOnAnEmptyResourceThatAreIgnoredStillGiveTheEmptyOk() {
        Result result = ByteRangeResolver.resolve(ranges(Range.of(10, 5)), 0L);

        assertEquals(200, result.status());
        assertEquals(0L, result.length());
        assertNull(result.contentRangeHeader());
    }

    @Test
    public void oneByteResourceCanBeRanged() {
        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, 0)), 1L), 0, 0, 1L);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(10)), 1L), 0, 0, 1L);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.from(1)), 1L), 1L);
    }

    @Test
    public void unknownNegativeTotalIsNeverRangedAndNeverThrows() {
        for (long total : new long[]{-1L, -2L, Long.MIN_VALUE}) {
            Result result = ByteRangeResolver.resolve(ranges(Range.of(0, 99)), total);

            assertNotNull(result);
            assertEquals(200, result.status());
            assertNull(result.contentRangeHeader());
            assertEquals(-1L, result.length());
        }
    }

    // ---- resolve: long arithmetic

    @Test
    public void hugeTotalDoesNotOverflow() {
        long total = Long.MAX_VALUE;

        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(0, Long.MAX_VALUE)), total),
                0, Long.MAX_VALUE - 1, total);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(Long.MAX_VALUE)), total),
                0, Long.MAX_VALUE - 1, total);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.suffix(5)), total),
                Long.MAX_VALUE - 5, Long.MAX_VALUE - 1, total);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.from(Long.MAX_VALUE - 1)), total),
                Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, total);
        assertUnsatisfiable(ByteRangeResolver.resolve(ranges(Range.from(Long.MAX_VALUE)), total), total);
        assertWhole(ByteRangeResolver.resolve(null, total), total);
    }

    @Test
    public void largeRangeInsideAMultiGigabyteResource() {
        long total = 5L * 1024L * 1024L * 1024L;

        assertPartial(ByteRangeResolver.resolve(ranges(Range.of(4_294_967_296L, 4_294_967_299L)), total),
                4_294_967_296L, 4_294_967_299L, total);
        assertPartial(ByteRangeResolver.resolve(ranges(Range.from(4_294_967_296L)), total),
                4_294_967_296L, total - 1, total);
    }

    // ---- Range value class

    @Test
    public void rangeAccessorsDescribeTheThreeForms() {
        Range closed = Range.of(5, 9);
        Range open = Range.from(7);
        Range suffix = Range.suffix(11);

        assertFalse(closed.isSuffix());
        assertEquals(5L, closed.first());
        assertEquals(9L, closed.last());
        assertEquals(7L, open.first());
        assertEquals(ByteRangeResolver.OPEN, open.last());
        assertEquals(-1L, ByteRangeResolver.OPEN);
        assertTrue(suffix.isSuffix());
        assertEquals(11L, suffix.suffixLength());
    }

    @Test
    public void rangeValidity() {
        assertTrue(Range.of(0, 0).isValid());
        assertTrue(Range.of(5, 9).isValid());
        assertTrue(Range.from(0).isValid());
        assertTrue(Range.suffix(0).isValid());
        assertTrue(Range.suffix(5).isValid());
        assertFalse(Range.of(9, 5).isValid());
        assertFalse(Range.of(-1, 5).isValid());
        assertFalse(Range.from(-1).isValid());
        assertFalse(Range.suffix(-1).isValid());
    }

    @Test
    public void rangesWithTheSameValuesAreEqual() {
        assertEquals(Range.of(1, 2), Range.of(1, 2));
        assertEquals(Range.of(1, 2).hashCode(), Range.of(1, 2).hashCode());
        assertEquals(Range.suffix(3), Range.suffix(3));
        assertFalse(Range.of(1, 2).equals(Range.of(1, 3)));
        assertFalse(Range.from(3).equals(Range.suffix(3)));
    }

    // ---- parseHeader: no usable header

    @Test
    public void nullEmptyAndBlankHeadersGiveNoRanges() {
        assertTrue(ByteRangeResolver.parseHeader(null).isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("   ").isEmpty());
        assertNotNull(ByteRangeResolver.parseHeader(null));
    }

    @Test
    public void aUnitOtherThanBytesGivesNoRanges() {
        assertTrue(ByteRangeResolver.parseHeader("items=0-5").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("lines=0-5").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("0-99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes 0-99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("=0-99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytesx=0-99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("xbytes=0-99").isEmpty());
    }

    @Test
    public void aHeaderWithoutAnyRangeGivesNoRanges() {
        assertTrue(ByteRangeResolver.parseHeader("bytes=").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=,").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=,,").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes= ").isEmpty());
    }

    // ---- parseHeader: valid headers

    @Test
    public void closedRangeIsParsed() {
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("bytes=0-99"));
        assertEquals(ranges(Range.of(0, 0)), ByteRangeResolver.parseHeader("bytes=0-0"));
        assertEquals(ranges(Range.of(500, 999)), ByteRangeResolver.parseHeader("bytes=500-999"));
    }

    @Test
    public void openEndedRangeIsParsed() {
        assertEquals(ranges(Range.from(100)), ByteRangeResolver.parseHeader("bytes=100-"));
        assertEquals(ranges(Range.from(0)), ByteRangeResolver.parseHeader("bytes=0-"));
    }

    @Test
    public void suffixRangeIsParsed() {
        assertEquals(ranges(Range.suffix(100)), ByteRangeResolver.parseHeader("bytes=-100"));
        assertEquals(ranges(Range.suffix(0)), ByteRangeResolver.parseHeader("bytes=-0"));
    }

    @Test
    public void severalRangesAreParsedInOrder() {
        assertEquals(ranges(Range.of(0, 1), Range.of(5, 9)), ByteRangeResolver.parseHeader("bytes=0-1,5-9"));
        assertEquals(ranges(Range.of(0, 1), Range.suffix(5), Range.from(900)),
                ByteRangeResolver.parseHeader("bytes=0-1,-5,900-"));
    }

    @Test
    public void unitIsMatchedIgnoringAsciiCase() {
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("Bytes=0-99"));
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("BYTES=0-99"));
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("bYtEs=0-99"));
    }

    @Test
    public void whitespaceAroundTheHeaderAndTheCommasIsIgnored() {
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader(" bytes=0-99 "));
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("\tbytes=0-99\t"));
        assertEquals(ranges(Range.of(0, 1), Range.of(5, 9)), ByteRangeResolver.parseHeader("bytes=0-1, 5-9"));
        assertEquals(ranges(Range.of(0, 1), Range.of(5, 9)), ByteRangeResolver.parseHeader("bytes=0-1 ,5-9"));
        assertEquals(ranges(Range.of(0, 1), Range.of(5, 9)), ByteRangeResolver.parseHeader("bytes=0-1 \t, \t5-9"));
    }

    @Test
    public void emptyListElementsAreIgnored() {
        assertEquals(ranges(Range.of(0, 1), Range.of(5, 9)), ByteRangeResolver.parseHeader("bytes=0-1,,5-9"));
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("bytes=0-99,"));
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("bytes=,0-99"));
    }

    @Test
    public void leadingZerosAreAccepted() {
        assertEquals(ranges(Range.of(7, 99)), ByteRangeResolver.parseHeader("bytes=007-0099"));
        assertEquals(ranges(Range.suffix(5)), ByteRangeResolver.parseHeader("bytes=-005"));
    }

    // ---- parseHeader: invalid headers are ignored as a whole

    @Test
    public void malformedPiecesIgnoreTheWholeHeader() {
        String[] headers = {"bytes=abc", "bytes=a-b", "bytes=1-2-3", "bytes=--5", "bytes=-", "bytes=-5-",
                "bytes=5", "bytes=0-99x", "bytes=x0-99", "bytes=0-9,x", "bytes=x,0-9", "bytes=0-9;5-9",
                "bytes=0-9 5-9", "bytes=+5-10", "bytes=0x10-20", "bytes=1e3-2e3", "bytes=0.5-9",
                "bytes==0-9", "bytes=0-9=", "bytes=0_9", "bytes=0–9", "bytes=*"};

        for (String header : headers) {
            assertNotNull(header, ByteRangeResolver.parseHeader(header));
            assertTrue(header, ByteRangeResolver.parseHeader(header).isEmpty());
        }
        // and a valid neighbour proves the table is not just empty because nothing parses
        assertEquals(ranges(Range.of(0, 9)), ByteRangeResolver.parseHeader("bytes=0-9"));
    }

    @Test
    public void whitespaceInsideARangeOrAfterTheEqualsSignIgnoresTheHeader() {
        assertTrue(ByteRangeResolver.parseHeader("bytes=0 - 99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=0- 99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=0 -99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=- 5").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes= 0-99").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes =0-99").isEmpty());
        assertEquals(ranges(Range.of(0, 99)), ByteRangeResolver.parseHeader("bytes=0-99"));
    }

    @Test
    public void lastBelowFirstIgnoresTheHeader() {
        assertTrue(ByteRangeResolver.parseHeader("bytes=10-5").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=1-0").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=0-1,10-5").isEmpty());
        assertEquals(ranges(Range.of(5, 5)), ByteRangeResolver.parseHeader("bytes=5-5"));
    }

    @Test
    public void nonAsciiDigitsAndControlCharactersIgnoreTheHeader() {
        assertTrue(ByteRangeResolver.parseHeader("bytes=١-٢").isEmpty());      // arabic-indic digits
        assertTrue(ByteRangeResolver.parseHeader("bytes=０-９").isEmpty());      // fullwidth digits
        assertTrue(ByteRangeResolver.parseHeader("bytes=0-9\n").isEmpty());              // only spaces and tabs are trimmed
        assertTrue(ByteRangeResolver.parseHeader("\nbytes=0-9").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=0-\u00009").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("bytes=0-9\r\nX: y").isEmpty());
        assertTrue(ByteRangeResolver.parseHeader("\u0000").isEmpty());
        assertEquals(ranges(Range.of(0, 9)), ByteRangeResolver.parseHeader("bytes=0-9"));
    }

    // ---- parseHeader: overflow and size

    @Test
    public void numbersBeyondLongSaturateInsteadOfThrowing() {
        assertEquals(ranges(Range.of(0, Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=0-99999999999999999999"));
        assertEquals(ranges(Range.from(Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=99999999999999999999-"));
        assertEquals(ranges(Range.suffix(Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=-99999999999999999999"));
        assertEquals(ranges(Range.of(Long.MAX_VALUE, Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=99999999999999999999-99999999999999999999"));
    }

    @Test
    public void longMaxValueItselfIsParsedExactly() {
        assertEquals(ranges(Range.of(0, Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=0-9223372036854775807"));
        assertEquals(ranges(Range.from(Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=9223372036854775807-"));
    }

    @Test
    public void overflowingNumbersEndInTheExpectedStatus() {
        assertPartial(serve("bytes=0-99999999999999999999", TOTAL), 0, 999, TOTAL);
        assertUnsatisfiable(serve("bytes=99999999999999999999-", TOTAL), TOTAL);
        assertPartial(serve("bytes=-99999999999999999999", TOTAL), 0, 999, TOTAL);
    }

    @Test
    public void aVeryLongNumberDoesNotThrow() {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            digits.append('9');
        }

        assertEquals(ranges(Range.of(0, Long.MAX_VALUE)),
                ByteRangeResolver.parseHeader("bytes=0-" + digits));
    }

    @Test
    public void aHeaderAtTheMaximumLengthIsParsedAndOneLongerIsIgnored() {
        assertEquals(1024, ByteRangeResolver.MAX_HEADER_LENGTH);
        StringBuilder atLimit = new StringBuilder("bytes=0-");
        while (atLimit.length() < ByteRangeResolver.MAX_HEADER_LENGTH) {
            atLimit.append('0');
        }
        String tooLong = atLimit + "0";

        assertEquals(ByteRangeResolver.MAX_HEADER_LENGTH, atLimit.length());
        assertEquals(ranges(Range.of(0, 0)), ByteRangeResolver.parseHeader(atLimit.toString()));
        assertTrue(ByteRangeResolver.parseHeader(tooLong).isEmpty());
    }

    @Test
    public void aHugeHeaderIsIgnoredWithoutThrowing() {
        StringBuilder many = new StringBuilder("bytes=");
        for (int i = 0; i < 20000; i++) {
            many.append("0-1,");
        }

        assertTrue(ByteRangeResolver.parseHeader(many.toString()).isEmpty());
        assertWhole(serve(many.toString(), TOTAL), TOTAL);
    }

    @Test
    public void parsedListIsNeverNullAndNeverThrowsForHostileInput() {
        String[] hostile = {null, "", "bytes=", ",", "=", "-", "bytes=-,-", "bytes=,,,,", "bytes=0-0-0-0",
                "\u0000\u0000", "bytes=\u0000", "bytes=" + "9".repeat(5000), "BYTES=-", "bytes=--",
                "bytes=9223372036854775808-9223372036854775809", "bytes=0-1,".repeat(300)};

        for (String header : hostile) {
            List<Range> parsed = ByteRangeResolver.parseHeader(header);
            assertNotNull(parsed);
            assertNotNull(ByteRangeResolver.resolve(parsed, TOTAL));
            assertNotNull(ByteRangeResolver.resolve(new ArrayList<>(parsed), 0L));
        }
    }

    // ---- parseHeader + resolve together (what the handler does)

    @Test
    public void noHeaderServesTheWholeResource() {
        assertWhole(serve(null, TOTAL), TOTAL);
        assertWhole(serve("", TOTAL), TOTAL);
    }

    @Test
    public void headerTable() {
        assertPartial(serve("bytes=0-99", TOTAL), 0, 99, TOTAL);
        assertPartial(serve("bytes=100-", TOTAL), 100, 999, TOTAL);
        assertPartial(serve("bytes=-100", TOTAL), 900, 999, TOTAL);
        assertPartial(serve("bytes=-5000", TOTAL), 0, 999, TOTAL);
        assertPartial(serve("bytes=900-5000", TOTAL), 900, 999, TOTAL);
        assertPartial(serve("BYTES=0-0", TOTAL), 0, 0, TOTAL);
        assertPartial(serve(" bytes=999-999 ", TOTAL), 999, 999, TOTAL);
    }

    @Test
    public void unsatisfiableHeadersGive416() {
        assertUnsatisfiable(serve("bytes=1000-", TOTAL), TOTAL);
        assertUnsatisfiable(serve("bytes=1000-2000", TOTAL), TOTAL);
        assertUnsatisfiable(serve("bytes=-0", TOTAL), TOTAL);
        assertUnsatisfiable(serve("bytes=0-", 0L), 0L);
        assertUnsatisfiable(serve("bytes=-1", 0L), 0L);
    }

    @Test
    public void ignoredHeadersGive200() {
        assertWhole(serve("bytes=10-5", TOTAL), TOTAL);
        assertWhole(serve("bytes=abc", TOTAL), TOTAL);
        assertWhole(serve("items=0-5", TOTAL), TOTAL);
        assertWhole(serve("bytes=0-9,x", TOTAL), TOTAL);
        assertWhole(serve("bytes=0 - 9", TOTAL), TOTAL);
        assertWhole(serve("garbage", TOTAL), TOTAL);
    }

    @Test
    public void severalRangesInTheHeaderGive200WithTheWholeResource() {
        assertWhole(serve("bytes=0-1,5-9", TOTAL), TOTAL);
        assertWhole(serve("bytes=0-1, 5-9, -3", TOTAL), TOTAL);
    }

    @Test
    public void emptyResourceTable() {
        assertWhole(serve(null, 0L), 0L);
        assertUnsatisfiable(serve("bytes=0-99", 0L), 0L);
        assertWhole(serve("bytes=10-5", 0L), 0L);
    }
}
