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
 * The limits of the binary protocol client: the numbers of "Security rules for Phase B" in the design. Every parser and
 * the connection read them from here.
 */
public final class SnapcastProtocolLimits {

    /** Bytes of the base header. */
    public static final int HEADER_SIZE = 26;
    /** The largest typed message of a WireChunk or a CodecHeader. */
    public static final int MAX_BULK_BODY = 8 * 1024 * 1024;
    /** The largest typed message of every other type, known or not. */
    public static final int MAX_SMALL_BODY = 64 * 1024;
    /** Bytes of a codec name; a longer one can not be on the allow-list. */
    public static final int MAX_CODEC_NAME_BYTES = 32;
    /** Bytes of the header payload of an allowed codec (a WAVE header is 44, a FLAC header about 42 bytes). */
    public static final int MAX_CODEC_HEADER_PAYLOAD = 64 * 1024;
    /** Characters of any text of the server that is kept (error text); longer text is cut. */
    public static final int MAX_TEXT_LENGTH = 1024;

    public static final int MIN_SAMPLE_RATE = 8000;
    public static final int MAX_SAMPLE_RATE = 192_000;
    public static final int MIN_CHANNELS = 1;
    public static final int MAX_CHANNELS = 8;

    public static final int MIN_BUFFER_MS = 0;
    public static final int MAX_BUFFER_MS = 10_000;
    public static final int DEFAULT_BUFFER_MS = 1000;
    public static final int MIN_LATENCY_MS = -10_000;
    public static final int MAX_LATENCY_MS = 10_000;
    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 100;

    /** The largest typed message size for a message type id (known or not). */
    static int maxBody(int type) {
        return type == MessageType.WIRE_CHUNK.id() || type == MessageType.CODEC_HEADER.id() ? MAX_BULK_BODY : MAX_SMALL_BODY;
    }

    private SnapcastProtocolLimits() {
    }
}
