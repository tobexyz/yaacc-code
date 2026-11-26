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
package de.yaacc.sendspin.session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * The server's artwork messages (issue #325, K15; spec, "Artwork messages"): the artwork {@code stream/start} and {@code stream/end}
 * and the binary messages of an image (announce, parts, cancel) on the channel types 8 to 11. The bodies here are the bytes after the
 * type byte, as {@code SecureChannel.sendMessage} takes them. Pure.
 */
public final class ArtworkWire {

    /** The largest artwork message including its type byte, so that it fits one transport message without fragmentation. */
    public static final int MAX_MESSAGE_BYTES = 65519;
    /** The most image bytes of one part: the message less the type and the flags bytes. */
    public static final int MAX_PART_DATA = MAX_MESSAGE_BYTES - 2;
    /** The binary message type of channel 0. */
    public static final int FIRST_TYPE = 8;
    public static final int MAX_CHANNELS = 4;

    private ArtworkWire() {
    }

    public static int typeOf(int channel) {
        return FIRST_TYPE + channel;
    }

    /** {@code stream/start} with the artwork channels exactly as the client declared them. */
    public static String streamStart(long serverTransmittedMicros, List<ControllerMessages.ArtworkChannel> channels) {
        try {
            JSONArray array = new JSONArray();
            for (ControllerMessages.ArtworkChannel c : channels) {
                JSONObject o = new JSONObject().put("source", c.source);
                if (c.isActive()) {
                    o.put("format", c.format).put("width", c.width).put("height", c.height);
                }
                array.put(o);
            }
            return SendspinMessages.message("stream/start", new JSONObject().put("server_transmitted", serverTransmittedMicros)
                    .put("artwork", new JSONObject().put("channels", array)));
        } catch (JSONException e) {
            throw new IllegalStateException("a message of plain values is always valid");
        }
    }

    /** {@code stream/end} for the artwork role only. */
    public static String streamEnd() {
        try {
            return SendspinMessages.message("stream/end", new JSONObject().put("roles", new JSONArray(Collections.singletonList("artwork"))));
        } catch (JSONException e) {
            throw new IllegalStateException("a message of plain values is always valid");
        }
    }

    /** The announce of an image: flag bit 1, when it is to be shown (server clock) and its size; a size of 0 clears the channel. */
    public static byte[] announce(long totalSize, long timestampMicros) {
        return ByteBuffer.allocate(1 + 8 + 4).put((byte) 0x02).putLong(timestampMicros).putInt((int) totalSize).array();
    }

    /** A part of the image: no flag set, then the next bytes of it. */
    public static byte[] part(byte[] data, int offset, int length) {
        byte[] body = new byte[1 + length];
        System.arraycopy(data, offset, body, 1, length);
        return body;
    }

    /** The cancel of the pending image of a channel: flag bit 0 only. */
    public static byte[] cancel() {
        return new byte[]{0x01};
    }
}
