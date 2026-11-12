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

import de.yaacc.multiroom.protocol.Codec;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;

/** pcm is decoded here; flac goes to the platform factory given (or is unavailable when there is none). */
public final class DefaultDecoderFactory implements DecoderFactory {

    private final DecoderFactory flac;

    /** @param flac makes the flac decoder; null when the platform has none */
    public DefaultDecoderFactory(DecoderFactory flac) {
        this.flac = flac;
    }

    @Override
    public FrameDecoder create(CodecHeaderMessage header, int maxFramesPerChunk) throws DecoderException {
        if (header.getCodec() == Codec.PCM) {
            return new PcmFrameDecoder(header.getPcmFormat(), maxFramesPerChunk);
        }
        if (header.getCodec() == Codec.FLAC && flac != null) {
            return flac.create(header, maxFramesPerChunk);
        }
        throw new DecoderException(DecoderException.Reason.UNAVAILABLE, "no decoder for this codec");
    }
}
