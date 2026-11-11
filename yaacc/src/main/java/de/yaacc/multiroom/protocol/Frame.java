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

/** A header and the message behind it; the message is null for a type that is skipped by size. */
public final class Frame {

    private final MessageHeader header;
    private final SnapcastBinaryMessage message;

    Frame(MessageHeader header, SnapcastBinaryMessage message) {
        this.header = header;
        this.message = message;
    }

    public MessageHeader getHeader() {
        return header;
    }

    /** The message, or null when {@link #isSkipped()}. */
    public SnapcastBinaryMessage getMessage() {
        return message;
    }

    /** True for a type this client does not handle: its body was read past and dropped. */
    public boolean isSkipped() {
        return message == null;
    }
}
