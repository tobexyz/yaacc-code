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
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One JSON-RPC 2.0 message from the server: an answer to a call (a result or an error), a notification, or (not
 * supported, ignored by the connection) a request of the server. A line holds one message or a batch of them.
 */
public final class SnapcastMessage {

    public enum Type {
        /** An answer with a {@code result} or an {@code error}. */
        RESPONSE,
        /** A message with a method and no id. */
        NOTIFICATION,
        /** A message with a method and an id: the server asks us something, which this client does not answer. */
        REQUEST
    }

    private final Type type;
    private final Long id;
    private final String method;
    private final JSONObject params;
    private final JSONObject result;
    private final SnapcastException error;

    private SnapcastMessage(Type type, Long id, String method, JSONObject params, JSONObject result, SnapcastException error) {
        this.type = type;
        this.id = id;
        this.method = method;
        this.params = params;
        this.result = result;
        this.error = error;
    }

    /** Reads a line with the default batch limit. */
    public static List<SnapcastMessage> parseLine(String line) throws SnapcastException {
        return parseLine(line, SnapcastLimits.MAX_BATCH);
    }

    /**
     * Reads one line: a message, or a batch of at most {@code maxBatch} messages. Elements of a batch that are no
     * messages are skipped; a line without a single message is an error.
     *
     * @throws SnapcastException of kind MALFORMED (not JSON, not a message, empty batch) or LIMIT (too long, too big a
     *                           batch, too deep, too many tokens)
     */
    public static List<SnapcastMessage> parseLine(String line, int maxBatch) throws SnapcastException {
        if (line == null) {
            throw SnapcastException.malformed("no line");
        }
        if (line.length() > SnapcastLimits.MAX_LINE_BYTES) {
            throw SnapcastException.limit("line too long");
        }
        Object parsed = SnapcastJson.parse(line);
        if (parsed instanceof JSONObject) {
            return Collections.singletonList(parseOne((JSONObject) parsed));
        }
        JSONArray batch = (JSONArray) parsed;
        if (batch.length() == 0) {
            throw SnapcastException.malformed("empty batch");
        }
        if (batch.length() > maxBatch) {
            throw SnapcastException.limit("batch too big");
        }
        List<SnapcastMessage> messages = new ArrayList<>(batch.length());
        for (int i = 0; i < batch.length(); i++) {
            Object element = batch.opt(i);
            if (element instanceof JSONObject) {
                try {
                    messages.add(parseOne((JSONObject) element));
                } catch (SnapcastException skipped) {
                    // an element that is no message: the others are still good
                }
            }
        }
        if (messages.isEmpty()) {
            throw SnapcastException.malformed("no message in the batch");
        }
        return Collections.unmodifiableList(messages);
    }

    private static SnapcastMessage parseOne(JSONObject json) throws SnapcastException {
        Object rawId = json.opt("id");
        Long id = idOf(rawId);
        boolean hasId = rawId != null && rawId != JSONObject.NULL;
        Object rawMethod = json.opt("method");
        if (rawMethod != null && rawMethod != JSONObject.NULL) {
            if (!(rawMethod instanceof String) || !isMethodName((String) rawMethod)) {
                throw SnapcastException.malformed("bad method name");
            }
            Object rawParams = json.opt("params");
            JSONObject params = rawParams instanceof JSONObject ? (JSONObject) rawParams : new JSONObject();
            return new SnapcastMessage(hasId ? Type.REQUEST : Type.NOTIFICATION, id, (String) rawMethod, params, null, null);
        }
        if (!hasId) {
            throw SnapcastException.malformed("neither a method nor an id");
        }
        Object rawError = json.opt("error");
        if (rawError != null && rawError != JSONObject.NULL) {
            return new SnapcastMessage(Type.RESPONSE, id, null, null, null, parseError(rawError));
        }
        if (!json.has("result")) {
            return new SnapcastMessage(Type.RESPONSE, id, null, null, null,
                    SnapcastException.malformed("answer without result or error"));
        }
        Object rawResult = json.opt("result");
        JSONObject result = rawResult instanceof JSONObject ? (JSONObject) rawResult : new JSONObject();
        return new SnapcastMessage(Type.RESPONSE, id, null, null, result, null);
    }

    private static boolean isMethodName(String name) {
        if (name.isEmpty() || name.length() > SnapcastLimits.MAX_METHOD_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Whole numbers (and digit strings) only; anything else can not be one of our ids. */
    private static Long idOf(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof BigInteger) {
            BigInteger big = (BigInteger) raw;
            return big.bitLength() <= 62 ? Long.valueOf(big.longValue()) : null;
        }
        if (raw instanceof String) {
            String text = (String) raw;
            if (text.isEmpty() || text.length() > 18) {
                return null;
            }
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                    return null;
                }
            }
            return Long.valueOf(text);
        }
        return null;
    }

    private static SnapcastException parseError(Object rawError) {
        if (!(rawError instanceof JSONObject)) {
            return SnapcastException.malformed("error is no object");
        }
        JSONObject error = (JSONObject) rawError;
        int code = SnapcastJson.integer(error, "code", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        String message = SnapcastJson.string(error, "message", "");
        Object rawData = error.opt("data");
        String data = null;
        if (rawData instanceof String) {
            data = SnapcastJson.truncate((String) rawData);
        } else if (rawData != null && rawData != JSONObject.NULL) {
            data = SnapcastJson.truncate(rawData.toString());
        }
        return new SnapcastException(SnapcastException.Kind.SERVER_ERROR, code, message, data, null);
    }

    public Type getType() {
        return type;
    }

    /** The id of an answer (or request); null when it has none or none we could have made. */
    public Long getId() {
        return id;
    }

    /** The method of a notification or request; null for an answer. */
    public String getMethod() {
        return method;
    }

    /** The parameters of a notification or request; an empty object when there are none, null for an answer. */
    public JSONObject getParams() {
        return params;
    }

    /** The result of an answer; an empty object when the server sent something else; null for the other types. */
    public JSONObject getResult() {
        return result;
    }

    /** The error of an answer, or null. */
    public SnapcastException getError() {
        return error;
    }
}
