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
package de.yaacc.yxc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Tolerant readers for the answers of a YXC device (the HTTP/JSON control API of Yamaha MusicCast devices).
 * A missing key, a JSON null or a value of an unexpected type reads as {@code null}; firmware differs and
 * unknown keys must never break a parser.
 */
final class YxcJson {

    private YxcJson() {
    }

    private static Object value(JSONObject object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.opt(key);
        return value == JSONObject.NULL ? null : value;
    }

    /** A string, or a number / boolean rendered as text (the API version is a JSON number). */
    static String string(JSONObject object, String key) {
        Object value = value(object, key);
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    static Integer integer(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    /** A whole number that may not fit an int (the {@code attribute} bit field of the play info). */
    static Long longInteger(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
    }

    static Double decimal(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    static Boolean bool(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /** True only for a JSON {@code true}; the update flags of events are absent or false otherwise. */
    static boolean flag(JSONObject object, String key) {
        return Boolean.TRUE.equals(bool(object, key));
    }

    static JSONObject object(JSONObject object, String key) {
        Object value = value(object, key);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    /** The string elements of an array; other elements are skipped. Empty when the key is absent. */
    static List<String> strings(JSONObject object, String key) {
        Object value = value(object, key);
        if (!(value instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray array = (JSONArray) value;
        List<String> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (element instanceof String) {
                result.add((String) element);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** The numeric elements of an array as ints; other elements are skipped. Empty when the key is absent. */
    static List<Integer> integers(JSONObject object, String key) {
        Object value = value(object, key);
        if (!(value instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray array = (JSONArray) value;
        List<Integer> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (element instanceof Number) {
                result.add(Integer.valueOf(((Number) element).intValue()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** The object elements of an array; other elements are skipped. Empty when the key is absent. */
    static List<JSONObject> objects(JSONObject object, String key) {
        Object value = value(object, key);
        if (!(value instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray array = (JSONArray) value;
        List<JSONObject> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (element instanceof JSONObject) {
                result.add((JSONObject) element);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }
}
