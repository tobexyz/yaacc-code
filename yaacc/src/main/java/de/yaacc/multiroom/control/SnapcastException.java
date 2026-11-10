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

import java.io.IOException;

/**
 * Every failure of the control library: a JSON-RPC error of the server, an unreadable answer, a timeout, a lost
 * connection. The library throws nothing else out of its public methods for server input (a wrong argument of the
 * caller is an {@link IllegalArgumentException}).
 */
public final class SnapcastException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The JSON-RPC code of "unauthorized" in the Snapcast server (authorization is switched on and not given). */
    public static final int CODE_UNAUTHORIZED = 401;
    /** The JSON-RPC code of "forbidden" in the Snapcast server. */
    public static final int CODE_FORBIDDEN = 403;

    /** What kind of failure it is. */
    public enum Kind {
        /** The server answered with a JSON-RPC error; {@link #getCode()}, message and data are the server's. */
        SERVER_ERROR,
        /** The text could not be read as the expected JSON or message. */
        MALFORMED,
        /** The call or the line did not finish in time. */
        TIMEOUT,
        /** There is no connection (not started, waiting to reconnect, given up). */
        NOT_CONNECTED,
        /** The client was closed, or the connection ended while the call waited. */
        CLOSED,
        /** The network failed. */
        IO,
        /** A limit of {@link SnapcastLimits} was hit. */
        LIMIT,
        /** An unexpected error inside the library (also an {@link Error} caught at the worker boundary). */
        INTERNAL
    }

    private final Kind kind;
    private final int code;
    private final String data;

    public SnapcastException(Kind kind, int code, String message, String data, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
        this.data = data;
    }

    public Kind getKind() {
        return kind;
    }

    /** The JSON-RPC error code; 0 when the failure did not come from a JSON-RPC error. */
    public int getCode() {
        return code;
    }

    /** The {@code data} of the JSON-RPC error (the real reason for most errors of the server), or null. */
    public String getData() {
        return data;
    }

    public boolean isUnauthorized() {
        return kind == Kind.SERVER_ERROR && code == CODE_UNAUTHORIZED;
    }

    public boolean isForbidden() {
        return kind == Kind.SERVER_ERROR && code == CODE_FORBIDDEN;
    }

    /** True when the server wants credentials (401) or refuses these (403). */
    public boolean isAuthorizationError() {
        return isUnauthorized() || isForbidden();
    }

    /** The message and the data in one line, for a log or an error row (the text comes from the server: display only). */
    public String describe() {
        String message = getMessage() == null ? "" : getMessage();
        return data == null || data.isEmpty() ? message : message + ": " + data;
    }

    static SnapcastException malformed(String message) {
        return new SnapcastException(Kind.MALFORMED, 0, message, null, null);
    }

    static SnapcastException malformed(String message, Throwable cause) {
        return new SnapcastException(Kind.MALFORMED, 0, message, null, cause);
    }

    static SnapcastException limit(String message) {
        return new SnapcastException(Kind.LIMIT, 0, message, null, null);
    }

    static SnapcastException timeout(String message) {
        return new SnapcastException(Kind.TIMEOUT, 0, message, null, null);
    }

    static SnapcastException notConnected(String message) {
        return new SnapcastException(Kind.NOT_CONNECTED, 0, message, null, null);
    }

    static SnapcastException closed(String message) {
        return new SnapcastException(Kind.CLOSED, 0, message, null, null);
    }

    /** The message names the exception class only: the text of a network exception can carry an address. */
    static SnapcastException io(IOException cause) {
        return new SnapcastException(Kind.IO, 0, "I/O error (" + cause.getClass().getSimpleName() + ")", null, cause);
    }

    static SnapcastException internal(Throwable cause) {
        return new SnapcastException(Kind.INTERNAL, 0, "internal error (" + cause.getClass().getSimpleName() + ")", null, cause);
    }
}
