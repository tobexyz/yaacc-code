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

import de.yaacc.multiroom.protocol.CodecHeaderMessage;

/** Makes the payload of a chunk from its PCM, and the CodecHeader that goes with it (one encoder per stream). */
public interface ChunkEncoder {

    /** The CodecHeader that a client gets first. */
    CodecHeaderMessage header();

    /** The payload of the chunk for these PCM bytes (a whole chunk). Called by the thread of the stream only. */
    byte[] encode(byte[] pcm);

    /** The codec that passes the PCM as it is: the CodecHeader is a RIFF WAVE header. */
    final class Pcm implements ChunkEncoder {
        private final CodecHeaderMessage header;

        public Pcm(int sampleRate, int channels, int bitsPerSample) {
            this.header = CodecHeaderMessage.of(de.yaacc.multiroom.protocol.Codec.PCM, PcmWaveHeader.build(sampleRate, channels, bitsPerSample));
        }

        @Override
        public CodecHeaderMessage header() {
            return header;
        }

        @Override
        public byte[] encode(byte[] pcm) {
            return pcm;
        }
    }
}
