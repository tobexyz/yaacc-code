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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the samples of {@code src/test/resources/snapcast} (see the README there for their provenance). */
final class SnapcastFixtures {

    private SnapcastFixtures() {
    }

    static String read(String name) {
        try (InputStream in = SnapcastFixtures.class.getResourceAsStream("/snapcast/" + name)) {
            if (in == null) {
                throw new AssertionError("fixture not found: " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("fixture not readable: " + name, e);
        }
    }

    /** The whole document of a fixture. */
    static JSONObject json(String name) {
        try {
            return new JSONObject(read(name));
        } catch (JSONException e) {
            throw new AssertionError("fixture is no JSON object: " + name, e);
        }
    }

    /** The {@code result} of a response fixture or the {@code params} of a notification fixture. */
    static JSONObject payload(String name) {
        JSONObject document = json(name);
        JSONObject payload = document.optJSONObject("result");
        if (payload == null) {
            payload = document.optJSONObject("params");
        }
        if (payload == null) {
            throw new AssertionError("fixture has neither result nor params: " + name);
        }
        return payload;
    }

    /** The status fixture parsed by the library. */
    static SnapcastServerStatus status(String name) {
        try {
            return SnapcastServerStatus.fromResult(payload(name));
        } catch (SnapcastException e) {
            throw new AssertionError("fixture does not parse: " + name, e);
        }
    }

    /** A deep copy, so that a test can edit a fixture without touching the shared document. */
    static JSONObject copy(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Structural equality of two JSON values (object key order does not matter). The compile classpath of the unit tests
     * sees the Android stubs of {@code org.json}, which have no {@code similar()}.
     */
    static boolean similar(Object a, Object b) {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject left = (JSONObject) a;
            JSONObject right = (JSONObject) b;
            if (left.length() != right.length()) {
                return false;
            }
            java.util.Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !similar(left.opt(key), right.opt(key))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof org.json.JSONArray && b instanceof org.json.JSONArray) {
            org.json.JSONArray left = (org.json.JSONArray) a;
            org.json.JSONArray right = (org.json.JSONArray) b;
            if (left.length() != right.length()) {
                return false;
            }
            for (int i = 0; i < left.length(); i++) {
                if (!similar(left.opt(i), right.opt(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }
}
