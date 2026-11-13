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
package de.yaacc.multiroom.client;

/** A decoder could not be made or could not decode; typed, with a text of ours. */
public final class DecoderException extends Exception {

    /** What went wrong. */
    public enum Reason {
        /** There is no decoder for the codec on this device. */
        UNAVAILABLE,
        /** The decoder could not be configured with the header of the stream. */
        BAD_HEADER,
        /** A chunk could not be decoded. */
        DECODE_FAILED,
        /** The chunk would decode to more audio than a chunk may hold. */
        OVERSIZED,
        /** The decoder gives samples in a format the engine does not know. */
        UNSUPPORTED_OUTPUT
    }

    private final Reason reason;

    public DecoderException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
