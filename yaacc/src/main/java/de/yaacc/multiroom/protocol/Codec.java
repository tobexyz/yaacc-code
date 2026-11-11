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
 * The codecs this client accepts: a fixed allow-list. The name is compared exactly; no decoder is ever made from a
 * name the server sends.
 */
public enum Codec {
    PCM("pcm"),
    FLAC("flac");

    private final String wireName;

    Codec(String wireName) {
        this.wireName = wireName;
    }

    /** The name in the CodecHeader message. */
    public String wireName() {
        return wireName;
    }

    /** The codec with exactly this name, or null. */
    public static Codec fromName(String name) {
        for (Codec codec : values()) {
            if (codec.wireName.equals(name)) {
                return codec;
            }
        }
        return null;
    }
}
