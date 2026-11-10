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
 * {@code de.yaacc.yxc.YxcJson}).
 */
final class SnapcastJson {

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
        } catch (JSONException e) {
            throw SnapcastException.malformed("not valid JSON", e);
        } catch (StackOverflowError e) {
            throw SnapcastException.malformed("nested too deeply");
        } catch (RuntimeException e) {
            // another implementation of org.json may throw more than JSONException for odd input
            throw SnapcastException.malformed("not valid JSON", e);
        }
    }

    static JSONObject parseObject(String text) throws SnapcastException {
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
     * tokens than the limit, and none of the characters that only the lenient forms of the parser read outside a string.
     */
    static void scan(String text) throws SnapcastException {
        char[] open = new char[SnapcastLimits.MAX_DEPTH + 1];
        int depth = 0;
        int tokens = 0;
        boolean inString = false;
        boolean finished = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
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
            if (tokens > SnapcastLimits.MAX_TOKENS) {
                throw SnapcastException.limit("too many tokens");
            }
        }
        if (inString || !finished) {
            throw SnapcastException.malformed("incomplete JSON");
        }
    }

    /** The number of opening brackets, commas and colons outside of strings. */
    static int tokenCount(String text) {
        int tokens = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[' || c == ',' || c == ':') {
                tokens++;
            }
        }
        return tokens;
    }

    // ---- tolerant accessors: a missing key, a JSON null or a value of an unexpected type gives the default

    private static Object value(JSONObject object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.opt(key);
        return value == JSONObject.NULL ? null : value;
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
    static String string(JSONObject object, String key, String defaultValue) {
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

    static boolean bool(JSONObject object, String key, boolean defaultValue) {
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
    static int integer(JSONObject object, String key, int min, int max, int defaultValue) {
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
