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

import java.io.IOException;

/**
 * Something the receiver (or the code that builds a message) got wrong at the protocol level: a packet that breaks the
 * framing rules, a body that is not valid for its opcode, or a message the negotiated version does not have. The
 * {@link Kind} says which, so that the caller decides: a framing error ends the connection, a bad message is dropped.
 */
public final class FCastProtocolException extends IOException {
    /** What went wrong. */
    public enum Kind {
        /** The announced packet size is over the limit; the stream can not be trusted any more. */
        OVERSIZE,
        /** A packet of size 0 (no opcode); the receivers end the connection on it too. */
        EMPTY_PACKET,
        /** The stream ended inside a packet. */
        TRUNCATED,
        /** A message to send does not fit into a packet. */
        TOO_LARGE,
        /** The body is not valid JSON, is nested too deep, or is not an object. */
        BAD_BODY,
        /** The body is JSON but a field has the wrong type or value for its opcode. */
        BAD_MESSAGE,
        /** The opcode does not exist in the negotiated protocol version. */
        UNSUPPORTED
    }

    private final Kind kind;

    public FCastProtocolException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FCastProtocolException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /** True if the connection can not go on after this error (the framing is lost). */
    public boolean endsConnection() {
        return kind == Kind.OVERSIZE || kind == Kind.EMPTY_PACKET || kind == Kind.TRUNCATED;
    }
}
