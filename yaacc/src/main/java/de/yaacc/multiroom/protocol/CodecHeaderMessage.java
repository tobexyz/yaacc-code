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

import de.yaacc.multiroom.control.SnapcastDisplayText;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The codec and the header that initialises its decoder. Only the codecs of the allow-list ({@link Codec}) exist, and
 * the header is validated when the message is made, so a CodecHeaderMessage always describes a stream the engine
 * supports: {@link #getSampleRate()}, {@link #getChannels()} and {@link #getBitsPerSample()} are inside the ranges.
 */
public final class CodecHeaderMessage implements SnapcastBinaryMessage {

    private final Codec codec;
    private final byte[] payload;
    private final PcmFormat pcmFormat;
    private final FlacStreamInfo flacStreamInfo;

    private CodecHeaderMessage(Codec codec, byte[] payload, PcmFormat pcmFormat, FlacStreamInfo flacStreamInfo) {
        this.codec = codec;
        this.payload = payload;
        this.pcmFormat = pcmFormat;
        this.flacStreamInfo = flacStreamInfo;
    }

    /**
     * @throws IllegalArgumentException when the header is not a valid header of that codec
     */
    public static CodecHeaderMessage of(Codec codec, byte[] header) {
        try {
            return parse(codec, header);
        } catch (SnapcastProtocolException e) {
            throw new IllegalArgumentException("not a valid " + codec.wireName() + " header: " + e.getMessage());
        }
    }

    static CodecHeaderMessage parse(Codec codec, byte[] header) throws SnapcastProtocolException {
        if (header == null || header.length > SnapcastProtocolLimits.MAX_CODEC_HEADER_PAYLOAD) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TOO_LARGE, "codec header too large");
        }
        byte[] copy = header.clone();
        if (codec == Codec.PCM) {
            return new CodecHeaderMessage(codec, copy, PcmFormat.parse(copy), null);
        }
        return new CodecHeaderMessage(codec, copy, null, FlacStreamInfo.parse(copy));
    }

    public Codec getCodec() {
        return codec;
    }

    /** A copy of the header, to initialise the decoder with. */
    public byte[] getPayload() {
        return payload.clone();
    }

    /** The parsed WAVE header; null for another codec. */
    public PcmFormat getPcmFormat() {
        return pcmFormat;
    }

    /** The parsed STREAMINFO; null for another codec. */
    public FlacStreamInfo getFlacStreamInfo() {
        return flacStreamInfo;
    }

    public int getSampleRate() {
        return pcmFormat != null ? pcmFormat.getSampleRate() : flacStreamInfo.getSampleRate();
    }

    public int getChannels() {
        return pcmFormat != null ? pcmFormat.getChannels() : flacStreamInfo.getChannels();
    }

    public int getBitsPerSample() {
        return pcmFormat != null ? pcmFormat.getBitsPerSample() : flacStreamInfo.getBitsPerSample();
    }

    @Override
    public MessageType type() {
        return MessageType.CODEC_HEADER;
    }

    byte[] encodeBody() {
        byte[] name = codec.wireName().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(8 + name.length + payload.length).order(ByteOrder.LITTLE_ENDIAN).putInt(name.length)
                .put(name).putInt(payload.length).put(payload).array();
    }

    static CodecHeaderMessage decode(ByteReader reader) throws SnapcastProtocolException {
        byte[] name = reader.lengthPrefixed();
        Codec codec = name.length > SnapcastProtocolLimits.MAX_CODEC_NAME_BYTES ? null
                : Codec.fromName(new String(name, StandardCharsets.UTF_8));
        if (codec == null) {
            // the name is the server's text: cleaned and cut, and it only ends up in the message of this error
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.UNSUPPORTED_CODEC, "codec not supported: "
                    + SnapcastDisplayText.of(new String(name, 0, Math.min(name.length, SnapcastProtocolLimits.MAX_CODEC_NAME_BYTES),
                    StandardCharsets.UTF_8), SnapcastProtocolLimits.MAX_CODEC_NAME_BYTES));
        }
        return parse(codec, reader.lengthPrefixed());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CodecHeaderMessage && codec == ((CodecHeaderMessage) other).codec
                && Arrays.equals(payload, ((CodecHeaderMessage) other).payload);
    }

    @Override
    public int hashCode() {
        return codec.hashCode() * 31 + Arrays.hashCode(payload);
    }
}
