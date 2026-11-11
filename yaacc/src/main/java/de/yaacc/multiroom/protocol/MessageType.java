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
 * The typed messages of the protocol (Snapcast 0.35.0). Type 0 is the base message that every message starts with and
 * type 6 (StreamTags) is not used by this client, so both, like any other number, are "unknown" and skipped by size.
 */
public enum MessageType {
    CODEC_HEADER(1),
    WIRE_CHUNK(2),
    SERVER_SETTINGS(3),
    TIME(4),
    HELLO(5),
    CLIENT_INFO(7),
    ERROR(8);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** The type with this id, or null when the id is not one this client handles. */
    public static MessageType of(int id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
