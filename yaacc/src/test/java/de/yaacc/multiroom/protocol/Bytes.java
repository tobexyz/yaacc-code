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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Byte helpers for the fixtures. They are written independently of the codec under test (plain shifts), so a fixture is
 * never produced by the encoder it checks.
 */
final class Bytes {

    private Bytes() {
    }

    static byte[] of(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    static byte[] u16(int value) {
        return of(value & 0xff, (value >>> 8) & 0xff);
    }

    /** A 32 bit little endian value (also for unsigned values above Integer.MAX_VALUE). */
    static byte[] u32(long value) {
        return of((int) (value & 0xff), (int) ((value >>> 8) & 0xff), (int) ((value >>> 16) & 0xff),
                (int) ((value >>> 24) & 0xff));
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The 26 byte base header with a size given as unsigned value. */
    static byte[] header(int type, int id, int refersTo, int sentSec, int sentUsec, int receivedSec, int receivedUsec,
                         long size) {
        return concat(u16(type), u16(id), u16(refersTo), u32(sentSec & 0xffffffffL), u32(sentUsec & 0xffffffffL),
                u32(receivedSec & 0xffffffffL), u32(receivedUsec & 0xffffffffL), u32(size));
    }

    /** A complete frame with a zero timestamp and the exact body size. */
    static byte[] frame(int type, byte[] body) {
        return concat(header(type, 0, 0, 0, 0, 0, 0, body.length), body);
    }

    /** A JSON body: u32 length and the text. */
    static byte[] jsonBody(String json) {
        byte[] text = utf8(json);
        return concat(u32(text.length), text);
    }

    /** A string with its u32 length. */
    static byte[] lengthPrefixed(byte[] data) {
        return concat(u32(data.length), data);
    }

    // ---- codec headers written out by hand

    /** "RIFF" size "WAVE" "fmt " 16 PCM 2 channels 48000 Hz, byte rate 192000, block align 4, 16 bit, "data" 0. */
    static final byte[] WAVE_48000_STEREO_16 = of(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45,
            0x66, 0x6d, 0x74, 0x20, 0x10, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x02, 0x00, 0x80, 0xbb, 0x00, 0x00, 0x00, 0xee, 0x02, 0x00, 0x04, 0x00, 0x10, 0x00,
            0x64, 0x61, 0x74, 0x61, 0x00, 0x00, 0x00, 0x00);

    /**
     * "fLaC", one STREAMINFO block (last-block flag, length 34): block sizes 1024, frame sizes unknown, 48000 Hz, 2
     * channels, 16 bit (20 + 3 + 5 bits: 0x0b 0xb8 0x02 0xf0), 0 samples, 16 bytes of MD5.
     */
    static final byte[] FLAC_48000_STEREO_16 = of(
            0x66, 0x4c, 0x61, 0x43,
            0x80, 0x00, 0x00, 0x22,
            0x04, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x0b, 0xb8, 0x02, 0xf0, 0x00, 0x00, 0x00, 0x00,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
