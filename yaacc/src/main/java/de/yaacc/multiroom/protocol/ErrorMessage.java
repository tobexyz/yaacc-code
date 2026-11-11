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
package de.yaacc.multiroom.protocol;

import de.yaacc.multiroom.control.SnapcastDisplayText;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * An error of the server. The texts are the server's: display text only, cleaned of control and format characters and
 * cut, never an identity and never a log line.
 */
public final class ErrorMessage implements SnapcastBinaryMessage {

    /** The code of "unauthorized" (authorization is switched on at the server and was not given). */
    public static final long CODE_UNAUTHORIZED = 401;
    /** The code of "forbidden". */
    public static final long CODE_FORBIDDEN = 403;

    private final long code;
    private final String message;
    private final String details;

    /**
     * @param code an unsigned 32 bit value
     */
    public ErrorMessage(long code, String message, String details) {
        if (code < 0 || code > 0xffffffffL) {
            throw new IllegalArgumentException("error code out of range");
        }
        this.code = code;
        this.message = SnapcastDisplayText.of(message, SnapcastProtocolLimits.MAX_TEXT_LENGTH);
        this.details = SnapcastDisplayText.of(details, SnapcastProtocolLimits.MAX_TEXT_LENGTH);
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    /** True for 401 and 403: the server wants credentials this client does not have. */
    public boolean isAuthorizationError() {
        return code == CODE_UNAUTHORIZED || code == CODE_FORBIDDEN;
    }

    @Override
    public MessageType type() {
        return MessageType.ERROR;
    }

    byte[] encodeBody() {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        byte[] more = details.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(12 + text.length + more.length).order(ByteOrder.LITTLE_ENDIAN).putInt((int) code)
                .putInt(text.length).put(text).putInt(more.length).put(more).array();
    }

    static ErrorMessage decode(ByteReader reader) throws SnapcastProtocolException {
        long code = reader.u32();
        String message = reader.text();
        return new ErrorMessage(code, message, reader.text());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ErrorMessage)) {
            return false;
        }
        ErrorMessage that = (ErrorMessage) other;
        return code == that.code && message.equals(that.message) && details.equals(that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, details);
    }
}
