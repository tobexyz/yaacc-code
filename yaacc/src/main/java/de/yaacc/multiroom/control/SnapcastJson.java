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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The guarded reader for the text of a server (which is not trusted) and tolerant accessors. The platform's
 * {@code org.json} has no limit on nesting and recurses, so a line of 100,000 brackets would end in a
 * {@code StackOverflowError} on the calling thread; it also reads more than JSON (comments, single quotes, {@code =}
 * and {@code ;}). {@link #parse} therefore scans the text first, quote-aware, and refuses nesting deeper than
 * {@link SnapcastLimits#MAX_DEPTH}, more than {@link SnapcastLimits#MAX_TOKENS} tokens, the lenient forms and anything
 * after the one value. The {@code StackOverflowError} handler stays as the last line of defense (precedent:
 * {@code de.yaacc.yxc.YxcJson}). The class is public only because the binary protocol client
 * ({@code de.yaacc.multiroom.protocol}) reads its JSON messages through the same guard; just the four entry points it needs
 * ({@code parseObject}, {@code string}, {@code integer}, {@code bool}) are public.
 *
 * <p><b>Where a string starts.</b> The scan must agree with the parser that runs behind it. Android's
 * {@code JSONTokener} reads a value that does not start with a quote as a literal up to one of {@code {}[]/\:,=;#} or
 * a space, and a quote is not in that set, so in {@code a"} no string begins. A scan that opened a string at every
 * quote would see one string where Android sees structure (and the other way round for the depth and the token cap).
 * So a quote outside a string is accepted only where a value or a name can begin: directly after {@code {},
 * {@code [}, {@code ,} or {@code :}, white space aside. Everything else is refused as malformed.
 */
public final class SnapcastJson {

    private SnapcastJson() {
    }

    // ---- the guarded parse

    /**
     * @return a {@link JSONObject} or a {@link JSONArray}
     * @throws SnapcastException of kind MALFORMED for anything else
     */
    static Object parse(String text) throws SnapcastException {
        if (text == null) {
            throw SnapcastException.malformed("no text");
        }
        scan(text);
        try {
            int start = 0;
            while (isWhitespace(text.charAt(start))) {
                start++;
            }
            return text.charAt(start) == '{' ? new JSONObject(text) : new JSONArray(text);
        } catch (JSONException | RuntimeException e) {
            // no cause: the message of a JSONException quotes the text of the server (up to 1 MiB, may hold addresses);
            // another implementation of org.json may throw more than JSONException for odd input
            throw SnapcastException.malformed("not valid JSON");
        } catch (StackOverflowError e) {
            throw SnapcastException.malformed("nested too deeply");
        }
    }

    public static JSONObject parseObject(String text) throws SnapcastException {
        Object value = parse(text);
        if (!(value instanceof JSONObject)) {
            throw SnapcastException.malformed("not a JSON object");
        }
        return (JSONObject) value;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /**
     * Checks the structure without building anything: one object or array, balanced, not deeper than the limit, not more
     * tokens than the limit, none of the characters that only the lenient forms of the parser read outside a string,
     * and a quote only where a value or a name can begin (see the class description).
     */
    static void scan(String text) throws SnapcastException {
        char[] open = new char[SnapcastLimits.MAX_DEPTH + 1];
        int depth = 0;
        int tokens = 0;
        boolean inString = false;
        boolean finished = false;
        char previous = 0; // the last character outside a string that is no white space; a closed string counts as a quote
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                    previous = '"';
                }
                continue;
            }
            if (finished) {
                if (isWhitespace(c)) {
                    continue;
                }
                throw SnapcastException.malformed("data after the value");
            }
            switch (c) {
                case '"':
                    if (depth == 0) {
                        throw SnapcastException.malformed("not an object or array");
                    }
                    if (previous != '{' && previous != '[' && previous != ',' && previous != ':') {
                        throw SnapcastException.malformed("quote where no value begins");
                    }
                    inString = true;
                    break;
                case '{':
                case '[':
                    depth++;
                    if (depth > SnapcastLimits.MAX_DEPTH) {
                        throw SnapcastException.malformed("nested too deeply");
                    }
                    open[depth] = c;
                    tokens++;
                    break;
                case '}':
                case ']':
                    if (depth == 0 || open[depth] != (c == '}' ? '{' : '[')) {
                        throw SnapcastException.malformed("unbalanced brackets");
                    }
                    depth--;
                    if (depth == 0) {
                        finished = true;
                    }
                    break;
                case ',':
                case ':':
                    if (depth == 0) {
                        throw SnapcastException.malformed("not an object or array");
                    }
                    tokens++;
                    break;
                case '\'':
                case '/':
                case '#':
                case '=':
                case ';':
                    throw SnapcastException.malformed("not strict JSON");
                default:
                    if (depth == 0 && !isWhitespace(c)) {
                        throw SnapcastException.malformed("not an object or array");
                    }
                    break;
            }
            if (!isWhitespace(c)) {
                previous = c;
            }
            if (tokens > SnapcastLimits.MAX_TOKENS) {
                throw SnapcastException.limit("too many tokens");
            }
        }
        if (inString || !finished) {
            throw SnapcastException.malformed("incomplete JSON");
        }
    }

    // ---- tolerant accessors: a missing key, a JSON null or a value of an unexpected type gives the default

    private static Object value(JSONObject object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.opt(key);
        return value == JSONObject.NULL ? null : value;
    }

    /**
     * Server text made safe to show: control characters, line and paragraph separators, bidi controls, the C1 range,
     * the invisible format characters (zero width space and joiners U+200B to U+200D, word joiner and invisible
     * operators U+2060 to U+2064, the deprecated U+2065 to U+206F, soft hyphen U+00AD, U+180E, the byte order mark, the
     * interlinear annotation marks, the non-characters U+FFFE and U+FFFF), the letters that draw nothing ({@link
     * #isBlankLooking}), tag characters (U+E0000 to U+E007F) and unpaired surrogates become
     * {@code ?}; the text is cut at {@code max} characters (never inside a surrogate pair). So two names that look the
     * same on the screen are the same text, or show a {@code ?} where they differ. It is still the server's text (display
     * only, never an identity, never a log line). The result is never longer than {@code max}.
     */
    static String displayText(String text, int max) {
        if (text == null) {
            return null;
        }
        int length = Math.min(text.length(), max);
        if (length > 0 && length < text.length() && Character.isHighSurrogate(text.charAt(length - 1))
                && Character.isLowSurrogate(text.charAt(length))) {
            length--; // the cut would leave half of a pair (a lone surrogate shows as a replacement glyph)
        }
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, text.charAt(i + 1));
                    if (codePoint >= 0xE0000 && codePoint <= 0xE007F) {
                        out.append('?'); // a tag character: invisible
                    } else {
                        out.append(c).append(text.charAt(i + 1));
                    }
                    i++;
                } else {
                    out.append('?'); // unpaired
                }
                continue;
            }
            out.append(isUnsafeForDisplay(c) ? '?' : c);
        }
        return out.toString();
    }

    private static boolean isUnsafeForDisplay(char c) {
        return c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f) || c == 0x2028 || c == 0x2029
                || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c == 0x200e || c == 0x200f
                || c == 0x061c || c == 0xfeff
                || (c >= 0x200b && c <= 0x200d) || (c >= 0x2060 && c <= 0x2064) || c == 0x00ad || c == 0x180e
                || (c >= 0xfff9 && c <= 0xfffb) || c == 0xfffe || c == 0xffff
                || (c >= 0x2065 && c <= 0x206f) || isBlankLooking(c)
                || Character.isLowSurrogate(c);
    }

    /**
     * Letters and marks that draw nothing but a blank (security review S66): the Hangul fillers (as
     * {@code DisplayName.clean} drops them), the Braille blank, the Khmer inherent vowels and the combining grapheme joiner.
     * A name made of them looks empty or pads a line. (U+2065 to U+206F, the unassigned and deprecated format characters,
     * are listed in {@link #isUnsafeForDisplay}.)
     */
    static boolean isBlankLooking(int c) {
        return c == 0x115f || c == 0x1160 || c == 0x3164 || c == 0xffa0 || c == 0x2800 || c == 0x17b4 || c == 0x17b5
                || c == 0x034f;
    }

    /** The title of a server error: printable ASCII only and at most 64 characters (the titles are fixed texts). */
    static String errorTitle(String text) {
        if (text == null) {
            return "";
        }
        int length = Math.min(text.length(), 64);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            out.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return out.toString();
    }

    /** Cuts a string at {@link SnapcastLimits#MAX_STRING_LENGTH} characters, never inside a surrogate pair. */
    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        int max = SnapcastLimits.MAX_STRING_LENGTH;
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** A string (a number or boolean is rendered as text), truncated; the default if absent or another type. */
    public static String string(JSONObject object, String key, String defaultValue) {
        Object value = value(object, key);
        if (value instanceof String) {
            return truncate((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return truncate(String.valueOf(value));
        }
        return defaultValue;
    }

    /** Only a real JSON string, no rendering of other types (for ids and names that must not be guessed). */
    static String strictString(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof String ? truncate((String) value) : null;
    }

    public static boolean bool(JSONObject object, String key, boolean defaultValue) {
        Object value = value(object, key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    static boolean isNumber(JSONObject object, String key) {
        return value(object, key) instanceof Number;
    }

    static boolean isBoolean(JSONObject object, String key) {
        return value(object, key) instanceof Boolean;
    }

    static JSONObject object(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    /** The object elements of an array (other elements are skipped), at most {@code max} of them. */
    static List<JSONObject> objects(JSONObject object, String key, int max) {
        Object value = value(object, key);
        if (!(value instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray array = (JSONArray) value;
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length() && result.size() < max; i++) {
            Object element = array.opt(i);
            if (element instanceof JSONObject) {
                result.add((JSONObject) element);
            }
        }
        return result;
    }

    /**
     * A number clamped into {@code min..max}. Large and odd values are handled without {@code intValue()}: a
     * {@code BigDecimal} such as 1e2147483647 would take forever to turn into a whole number.
     */
    public static int integer(JSONObject object, String key, int min, int max, int defaultValue) {
        return clamp(value(object, key), min, max, defaultValue);
    }

    static int clamp(Object value, int min, int max, int defaultValue) {
        if (!(value instanceof Number)) {
            return defaultValue;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return clampLong(((Number) value).longValue(), min, max);
        }
        if (value instanceof BigInteger) {
            BigInteger big = (BigInteger) value;
            if (big.bitLength() > 62) {
                return big.signum() < 0 ? min : max;
            }
            return clampLong(big.longValue(), min, max);
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number)) {
            return defaultValue;
        }
        if (number <= min) {
            return min;
        }
        if (number >= max) {
            return max;
        }
        return clampLong(Math.round(number), min, max);
    }

    private static int clampLong(long number, int min, int max) {
        return (int) Math.max(min, Math.min(max, number));
    }
}
