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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The guard in front of {@code org.json}: depth, token count, quotes, stray characters. */
public class SnapcastJsonTest {

    private static String nested(int depth) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            text.append('[');
        }
        for (int i = 0; i < depth; i++) {
            text.append(']');
        }
        return text.toString();
    }

    private static void assertMalformed(String text) {
        try {
            SnapcastJson.parse(text);
            fail("accepted: " + (text.length() > 80 ? text.substring(0, 80) + "..." : text));
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
        }
    }

    private static void assertLimit(String text) {
        try {
            SnapcastJson.parse(text);
            fail("accepted a line over the limit");
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.LIMIT, expected.getKind());
        }
    }

    @Test
    public void parsesObjectsAndArrays() throws Exception {
        assertTrue(SnapcastJson.parse("{\"a\":1}") instanceof JSONObject);
        assertTrue(SnapcastJson.parse("  [1,2]  ") instanceof JSONArray);
        assertTrue(SnapcastJson.parse("{\"a\":1}\r") instanceof JSONObject);
    }

    @Test
    public void depthLimitIs32() throws Exception {
        assertTrue(SnapcastJson.parse(nested(SnapcastLimits.MAX_DEPTH)) instanceof JSONArray);
        assertMalformed(nested(SnapcastLimits.MAX_DEPTH + 1));
        assertEquals(32, SnapcastLimits.MAX_DEPTH);
    }

    @Test
    public void nesting10000DeepIsRefusedBeforeOrgJsonSeesIt() {
        assertMalformed(nested(10_000));
        // unbalanced and deeper: the scan alone refuses it
        StringBuilder open = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            open.append("{\"a\":");
        }
        assertMalformed(open.toString());
    }

    @Test
    public void bracketsInsideStringsAreNoNesting() throws Exception {
        StringBuilder brackets = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            brackets.append('[');
        }
        JSONObject parsed = (JSONObject) SnapcastJson.parse("{\"a\":\"" + brackets + "\"}");

        assertEquals(1000, parsed.getString("a").length());
        // an escaped quote does not end the string
        JSONObject escaped = (JSONObject) SnapcastJson.parse("{\"a\":\"x\\\"" + brackets + "\"}");
        assertEquals(1002, escaped.getString("a").length());
    }

    @Test
    public void aClosingQuoteInsideAnEscapedBackslashEndsTheString() throws Exception {
        // the value is one backslash; the string ends right after it, so the brackets that follow are real nesting
        assertMalformed("{\"a\":\"\\\\\"," + "\"b\":" + nested(100) + "}");
    }

    @Test
    public void tokenCountLimitIs10000() throws Exception {
        assertTrue(SnapcastJson.parse(arrayOfZeros(SnapcastLimits.MAX_TOKENS)) instanceof JSONArray);
        assertLimit(arrayOfZeros(SnapcastLimits.MAX_TOKENS + 1));
        assertLimit(arrayOfZeros(100_000));
        assertEquals(10_000, SnapcastLimits.MAX_TOKENS);
    }

    @Test
    public void theTokenCapHoldsOnThePathTheConnectionUses() {
        try {
            SnapcastMessage.parseLine(arrayOfZeros(SnapcastLimits.MAX_TOKENS + 1));
            fail("a line over the token cap reached org.json");
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.LIMIT, expected.getKind());
        }
    }

    @Test
    public void tokenCountIgnoresStructureInsideStrings() throws Exception {
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            noise.append(",:{[");
        }
        assertTrue(SnapcastJson.parse("{\"a\":\"" + noise + "\"}") instanceof JSONObject);
    }

    private static void assertScanMalformed(CharSequence text) {
        try {
            SnapcastJson.scan(text.toString());
            fail("the scan accepted a line that Android's parser reads differently");
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
        }
    }

    @Test
    public void aQuoteInsideAnUnquotedLiteralIsNotTheStartOfAString() {
        // Android's JSONTokener reads a" as one literal; a scan that opens a string there hides the nesting behind it
        StringBuilder deep = new StringBuilder("[a\",");
        for (int i = 0; i < 100_000; i++) {
            deep.append('[');
        }
        assertScanMalformed(deep.append("\"]"));

        StringBuilder wide = new StringBuilder("[a\",");
        for (int i = 0; i < 300_000; i++) {
            wide.append("[],");
        }
        assertScanMalformed(wide.append("b\"]"));
    }

    @Test
    public void aQuoteOpensAStringOnlyAfterAnOpeningBracketCommaOrColon() throws Exception {
        SnapcastJson.scan("{ \"a\" : [ \"x\" , \"y\" ] }");
        SnapcastJson.scan("[\n\"a\"\t,\r\"b\"]");
        SnapcastJson.scan("{\"a\":\"b\",\"c\":{\"d\":[\"e\"]}}");
        assertScanMalformed("[\"a\" \"b\"]");
        assertScanMalformed("{\"a\":\"b\"\"c\":\"d\"}");
        assertScanMalformed("[1\"x\"]");
        assertScanMalformed("[true\"x\"]");
        assertScanMalformed("{\"a\"b\":1}");
        assertScanMalformed("[a\"]");
    }

    private static String arrayOfZeros(int count) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            text.append(i == 0 ? "0" : ",0");
        }
        return text.append(']').toString();
    }

    @Test
    public void lenientFormsOfTheParserAreRefused() {
        assertMalformed("{'a':1}");
        assertMalformed("{\"a\":1} // comment");
        assertMalformed("{\"a\":/* \" */1}");
        assertMalformed("{\"a\":1} # comment");
        assertMalformed("{\"a\"=1}");
        assertMalformed("{\"a\":1;\"b\":2}");
    }

    @Test
    public void onlyOneValueAndNothingAfterIt() {
        assertMalformed("{} {}");
        assertMalformed("{}x");
        assertMalformed("[] []");
    }

    @Test
    public void rejectsWhatIsNoContainer() {
        assertMalformed(null);
        assertMalformed("");
        assertMalformed("   ");
        assertMalformed("42");
        assertMalformed("\"text\"");
        assertMalformed("null");
        assertMalformed("true");
    }

    @Test
    public void rejectsBrokenSyntaxWithATypedException() {
        assertMalformed("{");
        assertMalformed("{\"a\":");
        assertMalformed("[1,2");
        assertMalformed("{\"a\":\"unterminated}");
        assertMalformed("{\"a\" 1}");
        assertMalformed("]");
        assertMalformed("}{");
        assertMalformed("[}");
    }

    @Test
    public void parseObjectRefusesAnArray() {
        try {
            SnapcastJson.parseObject("[1]");
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
        }
    }

    @Test
    public void aTinyStackIsEnoughBecauseTheScanGuardsFirst() throws Exception {
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                assertMalformed(nested(10_000));
                assertMalformed(nested(1_000_000));
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "tiny-stack", 128 * 1024);
        thread.start();
        thread.join(30_000);
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }
}
