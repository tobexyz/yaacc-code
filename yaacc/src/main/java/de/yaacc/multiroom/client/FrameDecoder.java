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

/** Turns the payload of a chunk into 16 bit interleaved PCM (little endian) with the channel count of the stream. */
public interface FrameDecoder {

    /**
     * @return whole frames of PCM; empty when the chunk holds no complete audio yet
     * @throws DecoderException when the chunk can not be decoded; the caller drops the chunk
     */
    byte[] decode(byte[] payload) throws DecoderException;

    /** Forgets partial state (after a lost connection): the next chunk starts a fresh stream. */
    void reset();

    /** Bytes of incomplete frames thrown away so far (statistics). */
    long droppedPartialBytes();

    /** Frees the decoder. Safe to call twice. */
    void close();
}
