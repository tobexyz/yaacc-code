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
package de.yaacc.multiroom.server;

/**
 * The CodecHeader of a pcm stream is a RIFF WAVE header (docs/tech.md, "Binary protocol"): 44 bytes with a format chunk of integer PCM and
 * an empty data chunk. The lengths of a stream are not known, so the RIFF size is that of the header alone and the data length is 0; a
 * reader takes only the format from it (the app's own client does not trust the lengths either).
 */
public final class PcmWaveHeader {

    private PcmWaveHeader() {
    }

    public static byte[] build(int sampleRate, int channels, int bitsPerSample) {
        if (sampleRate < 1 || channels < 1 || channels > 8 || bitsPerSample <= 0 || bitsPerSample % 8 != 0 || bitsPerSample > 32) {
            throw new IllegalArgumentException("format");
        }
        int blockAlign = channels * bitsPerSample / 8;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, 36);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);
        le16(h, 20, 1); // integer PCM
        le16(h, 22, channels);
        le32(h, 24, sampleRate);
        le32(h, 28, sampleRate * blockAlign);
        le16(h, 32, blockAlign);
        le16(h, 34, bitsPerSample);
        put(h, 36, "data");
        le32(h, 40, 0);
        return h;
    }

    private static void put(byte[] to, int at, String tag) {
        for (int i = 0; i < 4; i++) {
            to[at + i] = (byte) tag.charAt(i);
        }
    }

    private static void le16(byte[] to, int at, int v) {
        to[at] = (byte) v;
        to[at + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] to, int at, int v) {
        le16(to, at, v);
        le16(to, at + 2, v >> 16);
    }
}
