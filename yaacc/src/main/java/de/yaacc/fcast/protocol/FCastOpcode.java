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

/**
 * The opcodes of FCast protocol v1 to v3 (see {@code docs/tech.md}, "FCast"). The numbers are the wire values. An opcode a
 * receiver sends that is not in this list is not an error here: {@link FCastPacket} keeps the raw number and
 * {@link #fromCode} answers null.
 */
public enum FCastOpcode {
    NONE(0),
    PLAY(1),
    PAUSE(2),
    RESUME(3),
    STOP(4),
    SEEK(5),
    PLAYBACK_UPDATE(6),
    VOLUME_UPDATE(7),
    SET_VOLUME(8),
    PLAYBACK_ERROR(9),
    SET_SPEED(10),
    VERSION(11),
    PING(12),
    PONG(13),
    INITIAL(14),
    PLAY_UPDATE(15),
    SET_PLAYLIST_ITEM(16),
    SUBSCRIBE_EVENT(17),
    UNSUBSCRIBE_EVENT(18),
    EVENT(19);

    private final int code;

    FCastOpcode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** The first protocol version that has this opcode: 1 for the codes up to 8, 2 up to 13, 3 up to 19. */
    public int sinceVersion() {
        return code <= 8 ? 1 : code <= 13 ? 2 : 3;
    }

    /** @return the opcode with this wire value, or null if the protocol (up to v3) has none */
    public static FCastOpcode fromCode(int code) {
        for (FCastOpcode opcode : values()) {
            if (opcode.code == code) {
                return opcode;
            }
        }
        return null;
    }
}
