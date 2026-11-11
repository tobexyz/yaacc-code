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

/**
 * The one failure the binary protocol code throws for what a server sends (or for what the network does in the middle
 * of a message). Nothing else escapes the decoders: not a {@code BufferUnderflowException}, not a
 * {@code NegativeArraySizeException}, not an {@code OutOfMemoryError} from a size taken on trust. The message is a fixed
 * text of ours (it may name the kind of problem, never the bytes or the text that caused it).
 */
public final class SnapcastProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** The data ends before the message does. */
        TRUNCATED,
        /** The size in the header is above the cap for that type. */
        TOO_LARGE,
        /** The size is a u32 above {@link Integer#MAX_VALUE} (a negative number to a signed reader). */
        NEGATIVE_SIZE,
        /** A length inside a message does not fit into the message. */
        BAD_LENGTH,
        /** The content is not what the message should hold (JSON, header, magic numbers). */
        MALFORMED,
        /** The codec is not on the allow-list (pcm, flac). */
        UNSUPPORTED_CODEC,
        /** The header of an allowed codec has values the engine does not support. */
        UNSUPPORTED_FORMAT,
        /** A read timed out in the middle of a message. */
        TIMEOUT,
        /** A message took longer than the deadline from its first byte to its last. */
        DEADLINE
    }

    private final Kind kind;

    public SnapcastProtocolException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
