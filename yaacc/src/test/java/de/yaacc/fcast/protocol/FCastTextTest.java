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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Task 2.2 (text and numbers): what a receiver may put into the app's hands. */
public class FCastTextTest {

    @Test
    public void controlCharactersAreRemovedAndTheTextIsTrimmed() {
        assertEquals("Living Room", FCastText.clean("  Living\u0000 Room\n\u0007 ", 60));
        assertEquals("ab", FCastText.clean("a\u007fb\u0085", 60));
        assertEquals("ab", FCastText.clean("a b ", 60));
    }

    @Test
    public void longTextIsCutByCodePointsNotInsideASurrogatePair() {
        String text = "😀😀😀😀😀";
        assertEquals("😀😀😀", FCastText.clean(text, 3));
        assertEquals(5, FCastText.clean(text, 60).codePointCount(0, FCastText.clean(text, 60).length()));
    }

    @Test
    public void loneSurrogatesAreDropped() {
        assertEquals("ab", FCastText.clean("a\ud800b\udc00", 10));
    }

    @Test
    public void nullStaysNullAndEmptyStaysEmpty() {
        assertNull(FCastText.clean(null, 10));
        assertEquals("", FCastText.clean("", 10));
        assertEquals("", FCastText.clean("   ", 10));
        assertEquals("", FCastText.clean("abc", 0));
    }

    @Test
    public void aMegabyteOfTextIsCutQuickly() {
        char[] chars = new char[1_000_000];
        Arrays.fill(chars, 'a');
        assertEquals(60, FCastText.clean(new String(chars), FCastText.MAX_NAME).length());
    }

    @Test
    public void clampKeepsValuesInRangeAndTurnsNaNIntoTheMinimum() {
        assertEquals(0.0, FCastText.clamp(-3, 0, 1), 0.0);
        assertEquals(1.0, FCastText.clamp(7, 0, 1), 0.0);
        assertEquals(0.5, FCastText.clamp(0.5, 0, 1), 0.0);
        assertEquals(0.0, FCastText.clamp(Double.NaN, 0, 1), 0.0);
        assertEquals(1.0, FCastText.clamp(Double.POSITIVE_INFINITY, 0, 1), 0.0);
        assertEquals(0.0, FCastText.clamp(Double.NEGATIVE_INFINITY, 0, 1), 0.0);
    }

    @Test
    public void nestingIsMeasuredOutsideStrings() {
        assertFalse(FCastText.isNestedTooDeep("{\"a\":{\"b\":[1,2,{\"c\":3}]}}"));
        assertFalse(FCastText.isNestedTooDeep("{\"a\":\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"}"));
        assertFalse(FCastText.isNestedTooDeep("{\"a\":\"quote \\\" [[[[[[[[[[[[[[[[[[[[[[[[\"}"));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < FCastText.MAX_DEPTH; i++) {
            deep.append('[');
        }
        assertFalse("exactly the limit", FCastText.isNestedTooDeep(deep.toString()));
        assertTrue(FCastText.isNestedTooDeep(deep.append('[').toString()));
    }

    @Test
    public void zeroWidthAndBidirectionalMarksAreRemovedFromNames() {
        assertEquals("TV", FCastText.clean("T\u200bV", 60));
        assertEquals("TV", FCastText.clean("\u202eT\u202cV\u2066", 60));
        assertEquals("TV", FCastText.clean("\ufeffTV", 60));
        assertEquals("Café", FCastText.clean("Café", 60));
    }

    @Test
    public void ordinaryJsonIsAcceptable() {
        String[] good = {"{}", "[]", "{\"a\":1}", "{ \"a\" : [ 1 , -2.5e+3 , true , false , null ] }", "{\"a\":\"it's \\\" # // /* ; =\"}",
                "{\"url\":\"http://h/a?b=c;d#e\"}", "{\"t\":\"é€😀\"}", "{\"a\":{\"b\":{\"c\":[[1]]}}}"};
        for (String body : good) {
            assertTrue(body, FCastText.isAcceptableJson(body));
        }
    }

    @Test
    public void formsThatOnlyThePlatformParserAcceptAreRefused() {
        String[] bad = {"{'a':1}", "{\"a\":1} // comment", "{\"a\":1 /* c */}", "{\"a\":1 # c\n}", "{\"a\":1;\"b\":2}", "{\"a\"=1}",
                "{\"a\":<1>}", "{\"a\":\"unterminated}", "{\"a\":\\1}"};
        for (String body : bad) {
            assertFalse(body, FCastText.isAcceptableJson(body));
        }
    }

    @Test
    public void aQuoteHiddenInASingleQuotedStringOrACommentCanNotHideTheNesting() {
        // each of these made the old scan (double-quoted strings only) count too little: the guard must refuse them whatever follows
        char[] open = new char[20_000];
        Arrays.fill(open, '[');
        String brackets = new String(open);
        String[] bodies = {"{'a':'\"','b':" + brackets, "{\"a\":1 /* \" */,\"b\":" + brackets, "{\"a\":1 # \"\n,\"b\":" + brackets};
        for (String body : bodies) {
            assertFalse(FCastText.isAcceptableJson(body));
        }
    }

    @Test
    public void tooDeepNestingIsRefusedAndTheLimitItselfIsFine() {
        StringBuilder atLimit = new StringBuilder();
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < FCastText.MAX_DEPTH; i++) {
            atLimit.append('[');
            over.append('[');
        }
        over.append('[');
        for (int i = 0; i < FCastText.MAX_DEPTH; i++) {
            atLimit.append(']');
        }
        assertTrue(FCastText.isAcceptableJson(atLimit.toString()));
        assertFalse(FCastText.isAcceptableJson(over.toString()));
    }

    @Test
    public void aBodyOfSixteenThousandBracketsIsRefusedWithoutRecursing() {
        char[] chars = new char[16_000];
        Arrays.fill(chars, '[');
        assertTrue(FCastText.isNestedTooDeep(new String(chars)));
    }
}
