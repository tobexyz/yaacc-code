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

import de.yaacc.multiroom.protocol.PcmFormat;

/**
 * The decoder of the pcm codec: the payload is raw samples in the format of the (validated) WAVE header. A trailing
 * partial frame is dropped (counted), nothing is sized from the payload except through the validated frame size and
 * the frame limit, and samples of more than 16 bit are converted.
 */
public final class PcmFrameDecoder implements FrameDecoder {

    private final PcmConverter.Encoding encoding;
    private final int channels;
    private final int sourceFrameBytes;
    private final int maxFrames;
    private long droppedPartialBytes;

    public PcmFrameDecoder(PcmFormat format, int maxFramesPerChunk) {
        this.encoding = PcmConverter.Encoding.ofBits(format.getBitsPerSample());
        if (encoding == null) {
            throw new IllegalArgumentException("bit depth not supported");
        }
        this.channels = format.getChannels();
        this.sourceFrameBytes = format.getFrameBytes();
        this.maxFrames = maxFramesPerChunk;
    }

    @Override
    public byte[] decode(byte[] payload) throws DecoderException {
        int whole = payload.length / sourceFrameBytes;
        if (whole > maxFrames) {
            throw new DecoderException(DecoderException.Reason.OVERSIZED, "chunk longer than allowed");
        }
        int partial = payload.length - whole * sourceFrameBytes;
        if (partial != 0) {
            droppedPartialBytes += partial;
        }
        return PcmConverter.toPcm16(payload, 0, whole * channels, encoding);
    }

    @Override
    public void reset() {
        // no state between chunks
    }

    @Override
    public long droppedPartialBytes() {
        return droppedPartialBytes;
    }

    @Override
    public void close() {
        // nothing to free
    }
}
